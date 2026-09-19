[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [string]$AdbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $AdbPath)) { throw "ADB was not found: $AdbPath" }

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$apk = Join-Path $repoRoot 'app\build\outputs\apk\debug\app-debug.apk'
$artifact = Join-Path $repoRoot 'app\build\outputs\directshell-probe\warpnect-directshell-probe.jar'
$reportDirectory = Join-Path $repoRoot 'app\build\reports\directshell-input-probe'
$remoteArtifact = '/data/local/tmp/warpnect-directshell-probe.jar'
$packageName = 'io.warpnect'
$serverMainClass = 'io.warpnect.debug.directshell.WarpnectPrivilegedInputProbeServer'
$targetActivity = 'io.warpnect.debug.input.InputSessionTargetActivity'
$controllerReceiver = 'io.warpnect.debug.directshell.DirectShellInputProbeReceiver'

if (-not (Test-Path -LiteralPath $apk)) { throw "Debug APK is missing: $apk" }
if (-not (Test-Path -LiteralPath $artifact)) { throw "DEX probe artifact is missing: $artifact" }
New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null

function Invoke-Adb {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    & $AdbPath -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB command failed: $($Arguments -join ' ')" }
}

function Wait-ForTargetMarker {
    param(
        [Parameter(Mandatory = $true)][string]$Marker,
        [int]$TimeoutMilliseconds = 7500
    )
    $deadline = [Environment]::TickCount64 + $TimeoutMilliseconds
    do {
        $logs = Invoke-Adb -Arguments @(
            'logcat', '-d', '-v', 'brief',
            'WarpnectInputSessionTarget:I', 'WarpnectDirectShellInput:I', '*:S'
        )
        $match = $logs | Select-String -SimpleMatch $Marker | Select-Object -Last 1
        if ($null -ne $match) { return $match.ToString() }
        Start-Sleep -Milliseconds 100
    } while ([Environment]::TickCount64 -lt $deadline)
    throw "Timed out waiting for marker: $Marker"
}

function Wait-ForServerMarker {
    param(
        [Parameter(Mandatory = $true)][string]$RemoteLog,
        [Parameter(Mandatory = $true)][string]$Marker,
        [int]$TimeoutMilliseconds = 7500
    )
    $deadline = [Environment]::TickCount64 + $TimeoutMilliseconds
    do {
        $log = (Invoke-Adb -Arguments @('shell', 'cat', $RemoteLog)) -join "`n"
        if ($log -match [regex]::Escape($Marker)) { return $log }
        Start-Sleep -Milliseconds 100
    } while ([Environment]::TickCount64 -lt $deadline)
    throw "Timed out waiting for server marker: $Marker"
}

function Invoke-NormalAppCommand {
    param(
        [Parameter(Mandatory = $true)][string]$Mode,
        [Parameter(Mandatory = $true)][int]$Port,
        [Parameter(Mandatory = $true)][string]$Secret
    )
    $runId = "$Mode-$([Guid]::NewGuid().ToString('N'))"
    Invoke-Adb -Arguments @(
        'shell', 'am', 'broadcast', '-n', "$packageName/$controllerReceiver",
        '--ei', 'port', "$Port",
        '--es', 'secretBase64', $Secret,
        '--es', 'mode', $Mode,
        '--es', 'runId', $runId
    ) | Out-Null
    return Wait-ForTargetMarker -Marker "run_id=$runId DIRECT_SHELL_INPUT_CLIENT_RESULT command=$Mode result=OK" -TimeoutMilliseconds 30000
}

function Get-RemoteProcessMatch {
    param([Parameter(Mandatory = $true)][string]$ProcessName)
    $processes = Invoke-Adb -Arguments @('shell', 'ps', '-A')
    return (($processes | Select-String -SimpleMatch $ProcessName) -join "`n").Trim()
}

if (-not $SkipInstall) {
    Invoke-Adb -Arguments @('install', '-r', $apk) | Out-Host
}
Invoke-Adb -Arguments @('push', $artifact, $remoteArtifact) | Out-Host

$secretBytes = [byte[]]::new(32)
$random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $random.GetBytes($secretBytes)
} finally {
    $random.Dispose()
}
$secret = [Convert]::ToBase64String($secretBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
$port = Get-Random -Minimum 36000 -Maximum 46000
$serverName = "warpnect-directshell-input-$([Guid]::NewGuid().ToString('N').Substring(0, 8))"
$remoteLog = "/data/local/tmp/$serverName.log"
$localLog = Join-Path $reportDirectory "$serverName.log"
$serverCommand = "CLASSPATH=$remoteArtifact app_process /system/bin --nice-name=$serverName $serverMainClass --port $port --secret-base64 $secret --idle-timeout-ms 60000 --startup-local-key >$remoteLog 2>&1 &"
$commands = [System.Collections.Generic.List[string]]::new()
$failure = $null

Invoke-Adb -Arguments @('logcat', '-c') | Out-Null
# `-W` can wait indefinitely when an already visible target Activity does not transition idle.
# The deterministic target-ready marker below is the actual test prerequisite.
Invoke-Adb -Arguments @('shell', 'am', 'start', '-n', "$packageName/$targetActivity") | Out-Null
Wait-ForTargetMarker -Marker 'INPUT_SESSION_TARGET_READY' | Out-Null
Invoke-Adb -Arguments @('shell', $serverCommand) | Out-Null

try {
    Wait-ForServerMarker -RemoteLog $remoteLog -Marker 'START result=READY' | Out-Null
    Wait-ForTargetMarker -Marker 'kind=KEY_DOWN' | Out-Null
    Wait-ForTargetMarker -Marker 'kind=KEY_UP' | Out-Null

    $commands.Add((Invoke-NormalAppCommand -Mode 'prepare' -Port $port -Secret $secret))
    $commands.Add((Invoke-NormalAppCommand -Mode 'key' -Port $port -Secret $secret))
    Wait-ForTargetMarker -Marker 'kind=KEY_DOWN' | Out-Null
    Wait-ForTargetMarker -Marker 'kind=KEY_UP' | Out-Null
    $commands.Add((Invoke-NormalAppCommand -Mode 'touch' -Port $port -Secret $secret))
    Wait-ForTargetMarker -Marker 'kind=TOUCH_DOWN' | Out-Null
    Wait-ForTargetMarker -Marker 'kind=TOUCH_MOVE' | Out-Null
    Wait-ForTargetMarker -Marker 'kind=TOUCH_UP' | Out-Null
    $commands.Add((Invoke-NormalAppCommand -Mode 'pointer' -Port $port -Secret $secret))
    Wait-ForTargetMarker -Marker 'kind=POINTER' | Out-Null
    $commands.Add((Invoke-NormalAppCommand -Mode 'joystick' -Port $port -Secret $secret))
    Wait-ForTargetMarker -Marker 'kind=JOYSTICK' | Out-Null
    $commands.Add((Invoke-NormalAppCommand -Mode 'reset' -Port $port -Secret $secret))
    $commands.Add((Invoke-NormalAppCommand -Mode 'key-soak' -Port $port -Secret $secret))
    $commands.Add((Invoke-NormalAppCommand -Mode 'touch-soak' -Port $port -Secret $secret))
    $commands.Add((Invoke-NormalAppCommand -Mode 'diagnostics' -Port $port -Secret $secret))
    $commands.Add((Invoke-NormalAppCommand -Mode 'shutdown' -Port $port -Secret $secret))
} catch {
    $failure = $_.Exception.Message
}

Start-Sleep -Milliseconds 600
$serverLog = (Invoke-Adb -Arguments @('shell', 'cat', $remoteLog)) -join "`n"
$targetLog = (Invoke-Adb -Arguments @(
    'logcat', '-d', '-v', 'brief',
    'WarpnectInputSessionTarget:I', 'WarpnectDirectShellInput:I', '*:S'
)) -join "`n"
$stillRunning = Get-RemoteProcessMatch -ProcessName $serverName
Set-Content -LiteralPath $localLog -Value (
    "[server]`n$serverLog`n[normal_app_commands]`n$($commands -join "`n")`n[target]`n$targetLog`n[failure]`n$failure"
) -NoNewline

$requiredServer = @(
    'START result=READY',
    'uid=2000',
    'uid_kind=SHELL',
    'LOCAL_PROBE command=INPUT_PREPARE result=INPUT_PREPARED',
    'LOCAL_PROBE command=INPUT_KEY_SEQUENCE result=KEY_SEQUENCE_OK',
    'command=INPUT_PREPARE result=INPUT_PREPARED',
    'command=INPUT_KEY_SEQUENCE result=KEY_SEQUENCE_OK',
    'command=INPUT_TOUCH_GESTURE result=TOUCH_GESTURE_OK',
    'command=INPUT_POINTER result=POINTER_OK',
    'command=INPUT_JOYSTICK result=JOYSTICK_OK',
    'command=INPUT_RESET result=RESET_OK',
    'key_sequences=102',
    'touch_gestures=101',
    'synthetic_key_ups=1',
    'synthetic_joystick_neutral_events=1',
    'active_pressed_keys=0',
    'active_touch_streams=0',
    'command=SHUTDOWN result=INPUT_STOPPED',
    'ENDPOINT_RELEASED transport=LOOPBACK_TCP',
    'PROCESS_EXIT code=0'
)
$missing = @($requiredServer | Where-Object { $serverLog -notmatch [regex]::Escape($_) })
if ($targetLog -notmatch 'kind=KEY_DOWN' -or $targetLog -notmatch 'kind=KEY_UP') { $missing += 'target_key_receipt' }
if ($targetLog -notmatch 'kind=TOUCH_DOWN' -or $targetLog -notmatch 'kind=TOUCH_MOVE' -or $targetLog -notmatch 'kind=TOUCH_UP') {
    $missing += 'target_touch_receipt'
}
if ($targetLog -notmatch 'kind=POINTER') { $missing += 'target_pointer_receipt' }
if ($targetLog -notmatch 'kind=JOYSTICK') { $missing += 'target_joystick_receipt' }
if ($null -ne $failure) { $missing += "harness=$failure" }

if ($missing.Count -eq 0 -and [string]::IsNullOrWhiteSpace($stillRunning)) {
    Write-Host "DIRECTSHELL_INPUT_PROBE_RESULT=PASS report=$localLog"
    exit 0
}

Write-Host "DIRECTSHELL_INPUT_PROBE_RESULT=FAIL missing=$($missing -join ',') process=$stillRunning report=$localLog"
exit 1
