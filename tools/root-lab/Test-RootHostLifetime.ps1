[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$SdkPath,
    [string]$ApkPath,
    [string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^emulator-[0-9]+$') { throw 'Physical targets are forbidden.' }
if (-not $SdkPath) { $SdkPath = $env:ANDROID_SDK_ROOT }
if (-not $SdkPath) { $SdkPath = $env:ANDROID_HOME }
if (-not $SdkPath) { $SdkPath = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
if (-not $ApkPath) { $ApkPath = Join-Path $PSScriptRoot '../../app/build/outputs/apk/debug/app-debug.apk' }
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $PSScriptRoot '../../app/build/reports/root-lab/host-lifetime' }
$adb = Join-Path $SdkPath 'platform-tools/adb.exe'
$null = New-Item -ItemType Directory -Force -Path $OutputDirectory
$results = @()
$logStart = ''

function Adb {
    param([string]$Arguments)
    $p = New-Object System.Diagnostics.Process
    $p.StartInfo.FileName = $adb
    $p.StartInfo.Arguments = "-s $Serial $Arguments"
    $p.StartInfo.UseShellExecute = $false
    $p.StartInfo.CreateNoWindow = $true
    $p.StartInfo.RedirectStandardOutput = $true
    $p.StartInfo.RedirectStandardError = $true
    try {
        $null = $p.Start()
        $output = $p.StandardOutput.ReadToEndAsync()
        $errors = $p.StandardError.ReadToEndAsync()
        if (-not $p.WaitForExit(20000)) { $p.Kill(); throw 'Observation timeout.' }
        $text = $output.GetAwaiter().GetResult()
        $errorText = $errors.GetAwaiter().GetResult()
        if ($p.ExitCode -ne 0) { throw "Observation failure: command=$Arguments exit=$($p.ExitCode) $errorText $text" }
        return $text.Trim()
    } finally { $p.Dispose() }
}
function Helper {
    param([string]$Action)
    & (Join-Path $PSScriptRoot 'RootHelper.ps1') -Action $Action -Serial $Serial -SdkPath $SdkPath -ApkPath $ApkPath
}
function Logs {
    Adb "logcat -d -T `"$logStart`" -s WarpnectDirectRoot:I WarpnectPrivilegedRuntime:I WarpnectHostReadiness:I WarpnectDiscovery:I *:S"
}
function Wait-Log {
    param([string]$Pattern)
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        if ((Logs) -match $Pattern) { return }
        Start-Sleep -Milliseconds 150
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Expected boundary missing: $Pattern"
}
function Assert-Target {
    if ((((Adb 'emu avd name') -split '\r?\n')[0]).Trim() -ne 'Warpnect_Root_API26' -or
        (Adb 'shell getprop ro.kernel.qemu') -ne '1' -or
        (Adb 'shell getprop ro.build.version.sdk') -ne '26') { throw 'Wrong lab identity.' }
    Helper 'HostStatus' | Out-Null # Includes exact installed APK verification.
}
function Assert-Clean {
    param($Identity)
    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    do {
        if ((Adb 'shell ps -A') -notmatch 'warpnect-root-') { break }
        Start-Sleep -Milliseconds 150
    } while ([DateTime]::UtcNow -lt $deadline)
    if ((Adb 'shell ps -A') -match 'warpnect-root-') { throw 'Root helper remains.' }
    $port = ([int]$Identity.controlPort).ToString('X4')
    if ((Adb 'shell cat /proc/net/tcp') -match "0100007F:$port\s+00000000:0000\s+0A") { throw 'Root listener remains.' }
    if (Adb 'shell ls /data/user/0/io.warpnect/files/root-lab') { throw 'Bootstrap files remain.' }
}

Assert-Target
foreach ($case in @('frozen-helper-loss', 'pre-freeze-helper-loss')) {
    $result = [ordered]@{ case=$case; result='FAIL'; boundary='not_started' }
    $logStart = Adb "shell date '+%m-%d %H:%M:%S.000'"
    if ($logStart -notmatch '^\d\d-\d\d \d\d:\d\d:\d\d\.000$') { throw 'Invalid observation timestamp.' }
    try {
        $cacheBefore = (Adb 'shell sha256sum /data/user/0/io.warpnect/shared_prefs/privileged_capability_evidence.xml').Split(' ')[0]
        Helper 'Prepare' | Out-Null
        $start = (Helper 'Start') -join "`n"
        $line = @($start -split '\r?\n' | Where-Object { $_ -match '^direct_root_helper_identity ' })
        if ($line.Count -ne 1) { throw 'Missing unique helper identity.' }
        $identity = $line[0].Substring('direct_root_helper_identity '.Length) | ConvertFrom-Json
        if ($identity.uid -ne 0 -or $identity.gid -ne 0 -or $identity.api -ne 26) { throw 'Wrong Root helper identity.' }
        if ($start -notmatch 'endpoint=127\.0\.0\.1:([0-9]+)') { throw 'Missing Root endpoint.' }
        $identity | Add-Member -NotePropertyName controlPort -NotePropertyValue ([int]$Matches[1])
        $result.generation = $identity.generation
        $result.helperPid = $identity.pid
        Helper 'Acquire' | Out-Null
        Wait-Log "operation=acquire result=OK state=HELPER_READY generation=$($identity.generation) "
        Wait-Log 'prepared_plan_selected source=CACHED shape=SINGLE providers=DIRECT_ROOT'
        if ($case -eq 'frozen-helper-loss') {
            Helper 'EnableHost' | Out-Null
            Wait-Log "HOST_PUBLICATION state=AVAILABLE .*root_generation=$($identity.generation) "
        }
        Helper 'LoseHelper' | Out-Null # Guarded UID0/PID/generation-specific termination only.
        if ($case -eq 'frozen-helper-loss') {
            Wait-Log 'HOST_PUBLICATION state=WITHDRAWN reason=ROOT_HELPER_UNAVAILABLE migration=false'
            Wait-Log 'event=host_lifecycle_stopped'
            Wait-Log "direct_root_host_resources state=RELEASED generation=$($identity.generation) "
        } else {
            Wait-Log 'foundation=HELPER_LOST'
            Helper 'EnableHost' | Out-Null
            Wait-Log 'HOST_READINESS_STATE state=FAILED .*reason=ROOT_HELPER_UNAVAILABLE'
            if ((Logs) -match 'HOST_PUBLICATION state=AVAILABLE|direct_root_host_resources state=ACQUIRED') {
                throw 'Pre-freeze helper loss published Host or started capability resources.'
            }
        }
        Helper 'HostStatus' | Out-Null
        Wait-Log 'direct_root_normal_host_status role=null host=Idle advertising=false'
        Helper 'Stop' | Out-Null
        Assert-Clean $identity
        $cacheAfter = (Adb 'shell sha256sum /data/user/0/io.warpnect/shared_prefs/privileged_capability_evidence.xml').Split(' ')[0]
        if ($cacheAfter -ne $cacheBefore) { throw 'Volatile helper loss rewrote compatibility evidence.' }
        $result.result = 'PASS'
        $result.boundary = 'safe_unavailable_no_migration_no_cache_poisoning'
    } catch {
        $result.boundary = $_.Exception.Message
        throw
    } finally {
        Logs | Set-Content (Join-Path $OutputDirectory "$case.log")
        $results += [pscustomobject]$result
        $results | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $OutputDirectory 'results.json')
        Helper 'DisableHost' | Out-Null
        Helper 'Stop' | Out-Null
        Write-Output ([pscustomobject]$result | ConvertTo-Json -Compress)
    }
}
