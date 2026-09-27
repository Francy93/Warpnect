[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$SdkPath,
    [string]$ApkPath
)
$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^emulator-[0-9]+$') { throw 'Physical targets are forbidden.' }
if (-not $SdkPath) { $SdkPath = $env:ANDROID_SDK_ROOT }
if (-not $SdkPath) { $SdkPath = $env:ANDROID_HOME }
if (-not $SdkPath) { $SdkPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adb = Join-Path $SdkPath 'platform-tools\adb.exe'
if (-not $ApkPath) { $ApkPath = Join-Path $PSScriptRoot '..\..\app\build\outputs\apk\debug\app-debug.apk' }

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
        if ($process.ExitCode -ne 0) { throw "Observation failure: $errors $output" }
        return $output.Trim()
    } finally { $process.Dispose() }
}
function Assert-Target {
    $avd = ((Adb 'emu avd name') -split '\r?\n' | Select-Object -First 1).Trim()
    if ($avd -ne 'Warpnect_Root_API26' -or (Adb 'shell getprop ro.kernel.qemu') -ne '1' -or
        (Adb 'shell getprop ro.build.version.sdk') -ne '26' -or (Adb 'shell getenforce') -ne 'Enforcing') {
        throw 'Wrong laboratory environment.'
    }
}
function Helper {
    param([string]$Action)
    & (Join-Path $PSScriptRoot 'RootHelper.ps1') -Action $Action -Serial $Serial -SdkPath $SdkPath -ApkPath $ApkPath
}
function Logs { Adb ('logcat -d -T "' + $logStart + '" -s WarpnectDirectRoot:I WarpnectInputSessionTarget:I WarpnectPrivilegedInput:D *:S') }
function Wait-Log {
    param([string]$Pattern)
    $deadline = [DateTime]::UtcNow.AddSeconds(8)
    do {
        $logs = Logs
        if ($logs -match $Pattern) { Write-Host "ASSERT PASS: $Pattern"; return }
        Start-Sleep -Milliseconds 100
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Missing evidence: $Pattern"
}
function Gone {
    param($Identity)
    $deadline = [DateTime]::UtcNow.AddSeconds(8)
    do {
        $name = 'warpnect-root-' + ([long]$Identity.generation).ToString('x')
        if ((Adb 'shell ps -A') -notmatch [regex]::Escape($name)) {
            $port = ([int]$Identity.port).ToString('X4')
            if ((Adb 'shell cat /proc/net/tcp') -match "0100007F:$port\s+00000000:0000\s+0A") { throw 'Leaked listener.' }
            Write-Host "ASSERT PASS: helper/listener gone generation=$($Identity.generation)"
            return
        }
        Start-Sleep -Milliseconds 100
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'Leaked helper.'
}

Assert-Target
Helper 'Inspect' | Out-Null # Package/hash checks before starting any test.
$logStart = Adb "shell date '+%m-%d %H:%M:%S.000'"
if ($logStart -notmatch '^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.000$') { throw 'Invalid log boundary.' }
$passes = 0
$attempts = 0
$generations = @()
try {
    foreach ($cycle in 1..3) {
        $attempts++
        Helper 'Prepare' | Out-Host
        $started = (Helper 'Start') -join "`n"
        Write-Host $started
        $line = ($started -split '\r?\n' | Where-Object { $_ -match '^direct_root_helper_identity ' })
        $identity = $line.Substring('direct_root_helper_identity '.Length) | ConvertFrom-Json
        if ($identity.uid -ne 0 -or $identity.gid -ne 0 -or $identity.api -ne 26 -or
            $identity.provenance -ne 'LABORATORY_EXTERNAL_ROOT' -or $generations -contains $identity.generation) {
            throw 'Invalid/fresh-generation identity.'
        }
        if ($started -notmatch 'endpoint=127\.0\.0\.1:([0-9]+)') { throw 'Missing endpoint.' }
        $identity | Add-Member -NotePropertyName port -NotePropertyValue ([int]$Matches[1])
        $generations += $identity.generation
        if ($cycle -eq 1) {
            Helper 'NegativeAuth' | Out-Host
            Wait-Log "direct_root_negative_auth result=REJECTED generation=$($identity.generation) typed_input=true"
        }
        Helper 'Acquire' | Out-Host
        Wait-Log "operation=acquire result=OK state=HELPER_READY generation=$($identity.generation) "
        if ($cycle -eq 2) {
            Helper 'StaleInput' | Out-Host
            Wait-Log 'direct_root_input_stale_gateway result=REJECTED code=22'
        }
        $before = Adb "shell ls /proc/$($identity.pid)/fd"
        Helper 'QualifyInput' | Out-Host
        Wait-Log "direct_root_input_qualification result=PASS generation=$($identity.generation) "
        $after = Adb "shell ls /proc/$($identity.pid)/fd"
        $appPid = Adb 'shell pidof io.warpnect'
        $appFds = Adb "shell ls /proc/$appPid/fd"
        Write-Host "RESOURCE cycle=$cycle pid=$($identity.pid) helper_fds_before=$(@($before -split '\r?\n').Count) helper_fds_after=$(@($after -split '\r?\n').Count) app_fds=$(@($appFds -split '\r?\n').Count)"
        if ($cycle -eq 1) { Helper 'Invalidate' | Out-Host }
        elseif ($cycle -eq 3) {
            Helper 'LoseHelper' | Out-Host
            Helper 'InputStatus' | Out-Host
            Wait-Log 'root_records=1 runtime_ready=false state=HELPER_LOST.*lastError=ServiceDied'
            Helper 'Stop' | Out-Host
        } else { Helper 'Stop' | Out-Host }
        Gone $identity
        $files = Adb 'shell ls /data/user/0/io.warpnect/files/root-lab'
        if ($files -match '(request\.json|\.secret)') { throw 'Bootstrap file remains.' }
        $passes++
    }
    Helper 'Prepare' | Out-Host
    $started = (Helper 'Start') -join "`n"
    Write-Host $started
    $line = ($started -split '\r?\n' | Where-Object { $_ -match '^direct_root_helper_identity ' })
    $disconnect = $line.Substring('direct_root_helper_identity '.Length) | ConvertFrom-Json
    if ($started -notmatch 'endpoint=127\.0\.0\.1:([0-9]+)') { throw 'Missing disconnect endpoint.' }
    $disconnect | Add-Member -NotePropertyName port -NotePropertyValue ([int]$Matches[1])
    Helper 'Acquire' | Out-Host
    Wait-Log "operation=acquire result=OK state=HELPER_READY generation=$($disconnect.generation) "
    Helper 'HoldInput' | Out-Host
    Wait-Log 'direct_root_input_held result=PASS'
    Helper 'DropInput' | Out-Host
    Wait-Log 'direct_root_input_disconnect_reset result=PASS'
    Gone $disconnect
    Helper 'Stop' | Out-Host
    Write-Host 'ABNORMAL INPUT DISCONNECT 1 / 1 (target observed releases)'
    Assert-Target
    Adb 'shell am force-stop io.warpnect' | Out-Host
    Adb 'shell am start -W -n io.warpnect/.MainActivity' | Out-Host
    Helper 'InputStatus' | Out-Host
    Wait-Log 'root_records=1 runtime_ready=false state=PRODUCTION_BOOTSTRAP_NOT_IMPLEMENTED'
    if ((Adb 'shell ps -A') -match 'warpnect-root-') { throw 'Root helper retained after restart.' }
    Write-Host 'ASSERT PASS: cache survived process restart; runtime absent; no background injection'
    Write-Output (Logs)
} finally {
    Helper 'Stop' | Out-Host
    Write-Host "ROOT INPUT QUALIFICATIONS $passes / $attempts (no retry)"
}
