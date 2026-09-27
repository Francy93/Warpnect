[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [ValidateRange(1, 10)][int]$Cycles = 5,
    [string]$SdkPath,
    [string]$ApkPath,
    [string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^emulator-[0-9]+$') { throw 'Physical targets are forbidden.' }
if (-not $SdkPath) { $SdkPath = $env:ANDROID_SDK_ROOT }
if (-not $SdkPath) { $SdkPath = $env:ANDROID_HOME }
if (-not $SdkPath) { $SdkPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not $ApkPath) { $ApkPath = Join-Path $PSScriptRoot '..\..\app\build\outputs\apk\debug\app-debug.apk' }
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $PSScriptRoot '../../app/build/reports/root-lab/host-admission'
}
$adb = Join-Path $SdkPath 'platform-tools/adb.exe'
$null = New-Item -ItemType Directory -Force -Path $OutputDirectory
$results = @()
$logStart = ''

function Adb {
    param([string]$Arguments)
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo.FileName = $adb
    $process.StartInfo.Arguments = "-s $Serial $Arguments"
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.CreateNoWindow = $true
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    try {
        $null = $process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(20000)) { $process.Kill(); throw 'Observation timeout.' }
        $output = $stdout.GetAwaiter().GetResult()
        $errors = $stderr.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) {
            throw "Observation failure: command=$Arguments exit=$($process.ExitCode) stderr=$errors stdout=$output"
        }
        return $output.Trim()
    } finally { $process.Dispose() }
}
function Assert-Target {
    $avd = ((Adb 'emu avd name') -split '\r?\n')[0].Trim()
    if ($avd -ne 'Warpnect_Root_API26' -or
        (Adb 'shell getprop ro.kernel.qemu') -ne '1' -or
        (Adb 'shell getprop ro.build.version.sdk') -ne '26') { throw 'Wrong lab identity.' }
}
function Helper {
    param([string]$Action)
    & (Join-Path $PSScriptRoot 'RootHelper.ps1') -Action $Action -Serial $Serial -SdkPath $SdkPath -ApkPath $ApkPath
}
function Logs {
    Adb "logcat -d -T `"$logStart`" -s WarpnectDirectRoot:I WarpnectHostReadiness:I WarpnectPrivilegedRuntime:I WarpnectDiscovery:I *:S"
}
function Wait-Log {
    param([string]$Pattern, [int]$Seconds = 12)
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        $logs = Logs
        if ($logs -match $Pattern) { return }
        if ($logs -match 'host_admission_failed|HOST_PUBLICATION state=WITHDRAWN|direct_root_lab_action_failed') {
            throw "First product failure before expected boundary: $Pattern"
        }
        Start-Sleep -Milliseconds 150
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Expected boundary missing: $Pattern"
}
function Save-Resources {
    param([string]$Name, [string]$ProcessId)
    if ($ProcessId -notmatch '^[1-9][0-9]{0,8}$') { throw 'Unsafe process identity.' }
    Adb "shell ls -l /proc/$ProcessId/fd" | Set-Content (Join-Path $OutputDirectory "$Name-fds.txt")
    Adb "shell cat /proc/$ProcessId/maps" | Set-Content (Join-Path $OutputDirectory "$Name-maps.txt")
    return [int](Adb "shell ls /proc/$ProcessId/fd | wc -l")
}
function Assert-Clean {
    param($Identity)
    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    do {
        if ((Adb 'shell ps -A') -notmatch 'warpnect-root-') { break }
        Start-Sleep -Milliseconds 150
    } while ([DateTime]::UtcNow -lt $deadline)
    if ((Adb 'shell ps -A') -match 'warpnect-root-') { throw 'Root helper remains.' }
    $portHex = ([int]$Identity.controlPort).ToString('X4')
    if ((Adb 'shell cat /proc/net/tcp') -match "0100007F:$portHex\s+00000000:0000\s+0A") {
        throw 'Root listener remains.'
    }
    if (Adb 'shell ls /data/user/0/io.warpnect/files/root-lab') { throw 'Bootstrap files remain.' }
}

Assert-Target
Helper 'HostStatus' | Out-Null # Guard package and exact installed APK before any counted action.
$hash = (Get-FileHash $ApkPath -Algorithm SHA256).Hash.ToLowerInvariant()
$appPid = Adb 'shell pidof io.warpnect'
try {
    for ($attempt = 1; $attempt -le $Cycles; $attempt++) {
        $identity = $null
        $result = [ordered]@{ attempt=$attempt; apkSha256=$hash; result='FAIL'; boundary='not_started' }
        $logStart = Adb "shell date '+%m-%d %H:%M:%S.000'"
        try {
            Assert-Target
            Helper 'HostStatus' | Out-Null
            Wait-Log 'direct_root_normal_host_status role=null host=Idle advertising=false' 4
            $result.fdBefore = Save-Resources "cycle-$attempt-before" $appPid
            $result.evidenceBefore = (Adb 'shell sha256sum /data/user/0/io.warpnect/shared_prefs/privileged_capability_evidence.xml').Split(' ')[0]
            Helper 'Prepare' | Out-Null
            $start = (Helper 'Start') -join "`n"
            $line = @($start -split '\r?\n' | Where-Object { $_ -match '^direct_root_helper_identity ' })
            if ($line.Count -ne 1) { throw 'Missing unique helper identity.' }
            $identity = $line[0].Substring('direct_root_helper_identity '.Length) | ConvertFrom-Json
            if ($identity.uid -ne 0 -or $identity.gid -ne 0 -or $identity.api -ne 26 -or $identity.sha256 -ne $hash) {
                throw 'Wrong helper identity.'
            }
            if ($start -notmatch 'endpoint=127\.0\.0\.1:([0-9]+)') { throw 'Missing Root control endpoint.' }
            $identity | Add-Member -NotePropertyName controlPort -NotePropertyValue ([int]$Matches[1])
            if ($results.generation -contains $identity.generation) { throw 'Reused Root generation.' }
            $result.generation = $identity.generation
            $result.helperPid = $identity.pid
            Helper 'Acquire' | Out-Null
            Wait-Log "operation=acquire result=OK state=HELPER_READY generation=$($identity.generation) "
            Wait-Log 'prepared_plan_selected source=CACHED shape=SINGLE providers=DIRECT_ROOT'
            if ((Logs) -match 'direct_root_host_resources state=ACQUIRED') { throw 'Background started Host resources.' }
            $result.fdPrepared = Save-Resources "cycle-$attempt-prepared" $appPid
            Helper 'EnableHost' | Out-Null # Same application composition action as Enable Host UI.
            Wait-Log "HOST_PUBLICATION state=AVAILABLE .*root_generation=$($identity.generation) providers=\[DIRECT_ROOT\]"
            Wait-Log 'event=host_registration_active'
            Helper 'HostStatus' | Out-Null
            Wait-Log 'direct_root_normal_host_status role=Host host=Discovering advertising=true lan=Running readiness=Ready ui=Available'
            $result.fdActive = Save-Resources "cycle-$attempt-active" $appPid
            $result.fdHelper = Save-Resources "cycle-$attempt-helper" ([string]$identity.pid)
            if ($attempt -eq 1) {
                Adb 'shell screencap -p /data/local/tmp/warpnect-root-host-final.png' | Out-Null
                Adb "pull /data/local/tmp/warpnect-root-host-final.png `"$(Join-Path $OutputDirectory 'host-available.png')`"" | Out-Null
            }
            Helper 'DisableHost' | Out-Null
            Wait-Log "direct_root_host_resources state=RELEASED generation=$($identity.generation) .* errors=0 cleanup_errors=0"
            Wait-Log 'event=host_lifecycle_stopped'
            Assert-Clean $identity
            Helper 'HostStatus' | Out-Null
            Wait-Log 'direct_root_normal_host_status role=null host=Idle advertising=false'
            $result.fdAfter = Save-Resources "cycle-$attempt-after" $appPid
            $result.evidenceAfter = (Adb 'shell sha256sum /data/user/0/io.warpnect/shared_prefs/privileged_capability_evidence.xml').Split(' ')[0]
            if ($result.evidenceBefore -ne $result.evidenceAfter) { throw 'Host admission rewrote compatibility evidence.' }
            $result.result = 'PASS'
            $result.boundary = 'normal_host_available_publication_and_clean_disable'
        } catch {
            $result.boundary = $_.Exception.Message
            throw
        } finally {
            Logs | Set-Content (Join-Path $OutputDirectory "cycle-$attempt.log")
            $results += [pscustomobject]$result
            $results | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $OutputDirectory 'results.json')
            Write-Output ([pscustomobject]$result | ConvertTo-Json -Compress)
        }
    }
} finally {
    Helper 'DisableHost' | Out-Null
    Helper 'Stop' | Out-Null
    Write-Output "CAMPAIGN passes=$(@($results | Where-Object result -eq 'PASS').Count) attempts=$($results.Count) no_retries=true"
}
