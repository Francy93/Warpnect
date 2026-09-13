[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [string]$AdbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [int]$Cycles = 1,
    [int]$ToneDurationSeconds = 5,
    [int]$SampleDurationMilliseconds = 1500,
    [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'

if ($Cycles -lt 1) { throw 'Cycles must be positive.' }
if ($ToneDurationSeconds -lt 5 -or $ToneDurationSeconds -gt 120) {
    throw 'ToneDurationSeconds must be between 5 and 120.'
}
if ($SampleDurationMilliseconds -lt 100 -or $SampleDurationMilliseconds -gt 5000) {
    throw 'SampleDurationMilliseconds must be between 100 and 5000.'
}
if (-not (Test-Path -LiteralPath $AdbPath)) { throw "ADB was not found: $AdbPath" }

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$apk = Join-Path $repoRoot 'app\build\outputs\apk\debug\app-debug.apk'
$artifact = Join-Path $repoRoot 'app\build\outputs\directshell-probe\warpnect-directshell-probe.jar'
$reportDirectory = Join-Path $repoRoot 'app\build\reports\directshell-systemaudio-probe'
$remoteArtifact = '/data/local/tmp/warpnect-directshell-probe.jar'
$packageName = 'io.warpnect'
$serverMainClass = 'io.warpnect.debug.directshell.WarpnectPrivilegedSystemAudioProbeServer'
$controllerReceiver = 'io.warpnect.debug.directshell.DirectShellSystemAudioProbeReceiver'
$toneActivity = 'io.warpnect.debug.audio.SystemAudioE2eToneActivity'

if (-not (Test-Path -LiteralPath $apk)) { throw "Debug APK is missing: $apk" }
if (-not (Test-Path -LiteralPath $artifact)) { throw "DEX probe artifact is missing: $artifact" }
New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null

function Invoke-Adb {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    & $AdbPath -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB command failed: $($Arguments -join ' ')" }
}

function Get-RemoteProcessMatch {
    param([Parameter(Mandatory = $true)][string]$ProcessName)
    $processes = Invoke-Adb -Arguments @('shell', 'ps', '-A')
    return (($processes | Select-String -SimpleMatch $ProcessName) -join "`n").Trim()
}

function Wait-ForRemoteLog {
    param(
        [Parameter(Mandatory = $true)][string]$RemoteLog,
        [Parameter(Mandatory = $true)][string]$Marker,
        [int]$TimeoutMilliseconds = 7500
    )
    $deadline = [Environment]::TickCount64 + $TimeoutMilliseconds
    do {
        $log = (Invoke-Adb -Arguments @('shell', 'cat', $RemoteLog)) -join "`n"
        if ($log -match [regex]::Escape($Marker)) { return $log }
        Start-Sleep -Milliseconds 150
    } while ([Environment]::TickCount64 -lt $deadline)
    throw "Timed out waiting for server marker: $Marker"
}

function Wait-ForLogMarker {
    param(
        [Parameter(Mandatory = $true)][string]$Marker,
        [int]$TimeoutMilliseconds = 7500
    )
    $deadline = [Environment]::TickCount64 + $TimeoutMilliseconds
    do {
        $logs = Invoke-Adb -Arguments @(
            'logcat', '-d', '-v', 'brief',
            'WarpnectDirectShellAudio:I', 'WarpnectSystemAudio:I', '*:S'
        )
        $match = $logs | Select-String -SimpleMatch $Marker | Select-Object -Last 1
        if ($null -ne $match) { return $match.ToString() }
        Start-Sleep -Milliseconds 150
    } while ([Environment]::TickCount64 -lt $deadline)
    throw "Timed out waiting for device log marker: $Marker"
}

function Invoke-AudioCommand {
    param(
        [Parameter(Mandatory = $true)][string]$Mode,
        [Parameter(Mandatory = $true)][string]$ExpectedCommand,
        [Parameter(Mandatory = $true)][int]$Port,
        [Parameter(Mandatory = $true)][string]$Secret,
        [Parameter(Mandatory = $true)][int]$Cycle
    )
    $runId = "$Mode-$Cycle-$([Guid]::NewGuid().ToString('N'))"
    Invoke-Adb -Arguments @(
        'shell', 'am', 'broadcast', '-n', "$packageName/$controllerReceiver",
        '--ei', 'port', "$Port",
        '--es', 'secretBase64', $Secret,
        '--es', 'mode', $Mode,
        '--es', 'runId', $runId,
        '--ei', 'sampleDurationMillis', "$SampleDurationMilliseconds"
    ) | Out-Null
    $result = Wait-ForLogMarker -Marker "run_id=$runId DIRECT_SHELL_AUDIO_CLIENT_RESULT"
    if ($result -notmatch [regex]::Escape("command=$ExpectedCommand result=OK")) {
        throw "Audio command failed: $result"
    }
    return $result
}

if (-not $SkipInstall) {
    Invoke-Adb -Arguments @('install', '-r', $apk) | Out-Host
}
Invoke-Adb -Arguments @('push', $artifact, $remoteArtifact) | Out-Host

$passed = 0
$failed = 0

for ($cycle = 1; $cycle -le $Cycles; $cycle++) {
    $secretBytes = [byte[]]::new(32)
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $random.GetBytes($secretBytes)
    } finally {
        $random.Dispose()
    }
    $secret = [Convert]::ToBase64String($secretBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    $port = Get-Random -Minimum 36000 -Maximum 46000
    $serverName = "warpnect-directshell-audio-$cycle"
    $remoteLog = "/data/local/tmp/$serverName.log"
    $localLog = Join-Path $reportDirectory "$serverName.log"
    $serverCommand = "CLASSPATH=$remoteArtifact app_process /system/bin --nice-name=$serverName $serverMainClass --port $port --secret-base64 $secret --idle-timeout-ms 30000 >$remoteLog 2>&1 &"
    $cycleError = $null
    $clientEvents = [System.Collections.Generic.List[string]]::new()
    $routingObservation = ''

    Invoke-Adb -Arguments @('logcat', '-c') | Out-Null
    Invoke-Adb -Arguments @('shell', $serverCommand) | Out-Null
    try {
        Wait-ForRemoteLog -RemoteLog $remoteLog -Marker 'START result=READY' | Out-Null
        $clientEvents.Add((Invoke-AudioCommand -Mode 'prepare' -ExpectedCommand 'AUDIO_PREPARE' -Port $port -Secret $secret -Cycle $cycle))
        $clientEvents.Add((Invoke-AudioCommand -Mode 'start' -ExpectedCommand 'AUDIO_START' -Port $port -Secret $secret -Cycle $cycle))
        # This bounded pre-tone window proves the only controlled playback has not begun yet.
        $clientEvents.Add((Invoke-AudioCommand -Mode 'sample' -ExpectedCommand 'AUDIO_SAMPLE' -Port $port -Secret $secret -Cycle $cycle))

        Invoke-Adb -Arguments @(
            'shell', 'am', 'start', '-W', '-n', "$packageName/$toneActivity",
            '--ei', 'durationSeconds', "$ToneDurationSeconds"
        ) | Out-Null
        $clientEvents.Add((Wait-ForLogMarker -Marker 'SYSTEM_AUDIO_E2E_TONE_STARTED'))
        $clientEvents.Add((Invoke-AudioCommand -Mode 'sample' -ExpectedCommand 'AUDIO_SAMPLE' -Port $port -Secret $secret -Cycle $cycle))
        $routingObservation = (Invoke-Adb -Arguments @('shell', 'dumpsys', 'media.audio_flinger') |
            Select-String -Pattern 'RemoteSubmix|r_submix' | Select-Object -First 20) -join "`n"
        $clientEvents.Add((Wait-ForLogMarker -Marker 'SYSTEM_AUDIO_E2E_TONE_STOPPED' -TimeoutMilliseconds (($ToneDurationSeconds + 4) * 1000)))
        $clientEvents.Add((Invoke-AudioCommand -Mode 'stop' -ExpectedCommand 'AUDIO_STOP' -Port $port -Secret $secret -Cycle $cycle))
        $clientEvents.Add((Invoke-AudioCommand -Mode 'shutdown' -ExpectedCommand 'SHUTDOWN' -Port $port -Secret $secret -Cycle $cycle))
    } catch {
        $cycleError = $_.Exception.Message
    }

    Start-Sleep -Milliseconds 400
    $serverLog = (Invoke-Adb -Arguments @('shell', 'cat', $remoteLog)) -join "`n"
    $clientLog = (Invoke-Adb -Arguments @(
        'logcat', '-d', '-v', 'brief',
        'WarpnectDirectShellAudio:I', 'WarpnectSystemAudio:I', '*:S'
    )) -join "`n"
    $stillRunning = Get-RemoteProcessMatch -ProcessName $serverName
    $required = @(
        'START result=READY',
        'uid=2000',
        'uid_kind=SHELL',
        'command=AUDIO_PREPARE result=AUDIO_PREPARED',
        'command=AUDIO_START result=AUDIO_RECORDING',
        'command=AUDIO_SAMPLE result=REAL_PCM_CAPTURED',
        'AUDIO_SAMPLE_METRICS',
        'real_pcm=true',
        'command=AUDIO_STOP result=AUDIO_STOPPED',
        'command=SHUTDOWN result=OK',
        'ENDPOINT_RELEASED transport=LOOPBACK_TCP',
        'PROCESS_EXIT code=0'
    )
    $missing = @($required | Where-Object { $serverLog -notmatch [regex]::Escape($_) })
    $baselineMetrics = @($serverLog -split "`n" | Where-Object { $_ -match 'AUDIO_SAMPLE_METRICS sample_index=1 ' })
    $toneMetrics = @($serverLog -split "`n" | Where-Object { $_ -match 'AUDIO_SAMPLE_METRICS sample_index=2 ' })
    if ($baselineMetrics.Count -ne 1 -or $baselineMetrics[0] -notmatch 'classification=PCM_ZERO_ONLY') {
        $missing += 'pre_tone_pcm_was_not_quiet'
    }
    if ($toneMetrics.Count -ne 1 -or $toneMetrics[0] -notmatch 'classification=REAL_PCM_CAPTURED') {
        $missing += 'tone_pcm_was_not_captured'
    }
    if ($toneMetrics.Count -eq 1) {
        $rmsMatch = [regex]::Match($toneMetrics[0], 'rms=([0-9.]+)')
        if (-not $rmsMatch.Success -or [double]$rmsMatch.Groups[1].Value -lt 1000.0) {
            $missing += 'tone_pcm_rms_below_threshold'
        }
    }
    if ($null -ne $cycleError) { $missing += "client=$cycleError" }
    Set-Content -LiteralPath $localLog -Value (
        "[server]`n$serverLog`n[client_results]`n$($clientEvents -join "`n")`n[client_log]`n$clientLog`n[routing]`n$routingObservation`n[error]`n$cycleError"
    ) -NoNewline

    if ($missing.Count -eq 0 -and [string]::IsNullOrWhiteSpace($stillRunning)) {
        $passed++
        Write-Host "DIRECTSHELL_SYSTEMAUDIO_CYCLE_$cycle=PASS"
    } else {
        $failed++
        Write-Host "DIRECTSHELL_SYSTEMAUDIO_CYCLE_$cycle=FAIL missing=$($missing -join ',') process=$stillRunning"
        # No recovery kill: the server must self-terminate through its bounded idle lifecycle.
        Start-Sleep -Milliseconds 30500
        $afterTimeout = Get-RemoteProcessMatch -ProcessName $serverName
        if (-not [string]::IsNullOrWhiteSpace($afterTimeout)) {
            Write-Host "DIRECTSHELL_SYSTEMAUDIO_CYCLE_$cycle=ORPHAN process=$afterTimeout"
        }
    }
}

Write-Host "DIRECTSHELL_SYSTEMAUDIO_SOAK_RESULT pass=$passed fail=$failed total=$Cycles reports=$reportDirectory"
if ($failed -ne 0) { exit 1 }
