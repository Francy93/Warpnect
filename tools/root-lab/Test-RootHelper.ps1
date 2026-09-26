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
    param([string[]]$Arguments)
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo.FileName = $adb
    $process.StartInfo.Arguments = "-s $Serial " + ($Arguments -join ' ')
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
        return $output.Trim() -split '\r?\n'
    } finally { $process.Dispose() }
}
function Assert-Target {
    $avd = (Adb @('emu','avd','name') | Select-Object -First 1).Trim()
    if ($avd -ne 'Warpnect_Root_API26' -or
        (Adb @('shell','getprop','ro.kernel.qemu')).Trim() -ne '1' -or
        (Adb @('shell','getprop','ro.build.version.sdk')).Trim() -ne '26') { throw 'Wrong lab identity.' }
}
function Helper {
    param([string]$Action)
    & (Join-Path $PSScriptRoot 'RootHelper.ps1') -Action $Action -Serial $Serial -SdkPath $SdkPath -ApkPath $ApkPath
}
function Wait-Log {
    param([string]$Pattern, [int]$Seconds = 8)
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        $logs = (Adb @('logcat','-d','-T',('"' + $logStart + '"'),'-s','WarpnectDirectRoot:I','WarpnectHostReadiness:I','*:S')) -join "`n"
        if ($logs -match $Pattern) { Write-Output "ASSERT PASS: $Pattern"; return }
        Start-Sleep -Milliseconds 150
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Expected log missing: $Pattern"
}
function Start-Generation {
    Helper 'Prepare' | ForEach-Object { Write-Host $_ }
    Start-Sleep -Milliseconds 300
    $started = (Helper 'Start') -join "`n"
    Write-Host $started
    $line = ($started -split '\r?\n' | Where-Object { $_ -match '^direct_root_helper_identity ' })
    if (@($line).Count -ne 1) { throw 'Missing unique helper-side identity.' }
    $identity = $line.Substring('direct_root_helper_identity '.Length) | ConvertFrom-Json
    if ($identity.uid -ne 0 -or $identity.gid -ne 0 -or $identity.api -ne 26) { throw 'Not the expected UID0 helper.' }
    if ($started -notmatch 'endpoint=127\.0\.0\.1:([0-9]+)') { throw 'Missing loopback endpoint.' }
    $identity | Add-Member -NotePropertyName controlPort -NotePropertyValue ([int]$Matches[1])
    return $identity
}
function Acquire {
    param($Identity)
    Helper 'Acquire'
    Wait-Log "operation=acquire result=OK state=HELPER_READY generation=$($Identity.generation) "
    Wait-Log 'direct_root_runtime_observed state=READY foundation=HELPER_READY'
    $fds = Adb @('shell', "ls /proc/$($Identity.pid)/fd | wc -l")
    Write-Output "RESOURCE helper_pid=$($Identity.pid) fd_count=$fds"
}
function Gone {
    param($Identity)
    $deadline = [DateTime]::UtcNow.AddSeconds(8)
    do {
        $processes = (Adb @('shell','ps','-A')) -join "`n"
        $name = 'warpnect-root-' + ([long]$Identity.generation).ToString('x')
        if ($processes -notmatch [regex]::Escape($name)) {
            $tcp = (Adb @('shell','cat','/proc/net/tcp')) -join "`n"
            $hexPort = ([int]$Identity.controlPort).ToString('X4')
            if ($tcp -match "0100007F:$hexPort\s+00000000:0000\s+0A") { throw 'Root listener remains.' }
            Write-Output "ASSERT PASS: helper $($Identity.pid) and listener disappeared"
            return
        }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'Orphan helper remains.'
}

Assert-Target
Helper 'Inspect' | Out-Null # Also checks package and exact APK identity before tests.
$logStart = (Adb @('shell','date',"'+%m-%d %H:%M:%S.000'")) -join ''
if ($logStart -notmatch '^[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\.000$') { throw 'Missing device log timestamp.' }
$attempts = 0
$passes = 0
try {
    $attempts++
    $a = Start-Generation
    Helper 'NegativeAuth'
    Wait-Log "direct_root_negative_auth result=REJECTED generation=$($a.generation)"
    Helper 'NegativeGeneration'
    Wait-Log 'direct_root_negative_generation result=REJECTED'
    Acquire $a
    Helper 'Duplicate'
    Wait-Log 'direct_root_duplicate_owner result=REJECTED'
    Helper 'Invalidate'
    Gone $a
    $passes++

    $attempts++
    $b = Start-Generation
    if ($a.generation -eq $b.generation) { throw 'Reused generation.' }
    Helper 'NegativeAuth' # Uses only the prior generation's in-memory credentials.
    Wait-Log "direct_root_negative_auth result=REJECTED generation=$($b.generation)"
    Acquire $b
    Helper 'Stop'
    Gone $b
    $passes++

    $attempts++
    $restart = Start-Generation
    Acquire $restart
    Assert-Target
    Adb @('shell','am','force-stop','io.warpnect')
    Gone $restart
    Adb @('shell','am','start','-W','-n','io.warpnect/.MainActivity')
    Helper 'Inspect' | Out-Null
    Wait-Log 'operation=status result=OK state=PRODUCTION_BOOTSTRAP_NOT_IMPLEMENTED generation=null '
    $passes++

    $attempts++
    $loss = Start-Generation
    Acquire $loss
    Helper 'LoseHelper'
    Gone $loss
    Start-Sleep -Seconds 2
    Helper 'Inspect' | Out-Null
    Wait-Log "operation=status result=OK state=HELPER_LOST generation=$($loss.generation)"
    $passes++

    $attempts++
    Helper 'Stop'
    Helper 'Prepare'
    Helper 'Acquire' # No helper is launched: bounded, truthful bootstrap failure.
    Wait-Log 'operation=acquire result=FAIL state=BOOTSTRAP_FAILED' 20
    Helper 'Stop'
    $passes++

    $attempts++
    $idle = Start-Generation
    Helper 'NegativeAuth'
    Start-Sleep -Seconds 11 # Invalid authentication must not refresh the helper's idle lease.
    Gone $idle
    Helper 'Stop'
    $passes++

    $attempts++
    Helper 'Prepare'
    Helper 'Stop' # Canceled before launch.
    if ((Adb @('shell','ls','/data/user/0/io.warpnect/files/root-lab')) -join '') { throw 'Canceled bootstrap files remain.' }
    $passes++

    $attempts++
    Assert-Target
    Adb @('unroot')
    Adb @('wait-for-device')
    Helper 'Prepare'
    Helper 'NegativeUid'
    Helper 'Stop'
    $passes++
} finally {
    Helper 'Stop'
    Write-Output "QUALIFICATION passes=$passes attempts=$attempts (no retries)"
}
