[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [string]$AdbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [int]$Cycles = 10,
    [int]$ToneDurationSeconds = 5,
    [int]$SilentWindowMilliseconds = 1200,
    [int]$ToneWindowMilliseconds = 1500,
    [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'

if ($Cycles -lt 1 -or $Cycles -gt 20) { throw 'Cycles must be between 1 and 20.' }
if ($ToneDurationSeconds -lt 5 -or $ToneDurationSeconds -gt 120) {
    throw 'ToneDurationSeconds must be between 5 and 120.'
}
if ($SilentWindowMilliseconds -lt 500 -or $SilentWindowMilliseconds -gt 5000) {
    throw 'SilentWindowMilliseconds must be between 500 and 5000.'
}
if ($ToneWindowMilliseconds -lt 500 -or $ToneWindowMilliseconds -gt 5000) {
    throw 'ToneWindowMilliseconds must be between 500 and 5000.'
}
if (-not (Test-Path -LiteralPath $AdbPath)) { throw "ADB was not found: $AdbPath" }
$pendingInstalls = Get-CimInstance Win32_Process -Filter "Name = 'adb.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -match '(?i)(?:^|\s)install(?:\s|$)' }
if ($pendingInstalls) {
    throw 'A pending adb install exists; do not start a counted probe while package-manager work is active.'
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$apk = Join-Path $repoRoot 'app\build\outputs\apk\debug\app-debug.apk'
$artifact = Join-Path $repoRoot 'app\build\outputs\directshell-probe\warpnect-directshell-probe.jar'
$reportDirectory = Join-Path $repoRoot 'app\build\reports\directshell-pcm-shared-ring-probe'
$remoteArtifact = '/data/local/tmp/warpnect-directshell-probe.jar'
$packageName = 'io.warpnect'
$serverMainClass = 'io.warpnect.debug.directshell.WarpnectPrivilegedPcmSharedRingProbeServer'
$controllerReceiver = 'io.warpnect.debug.directshell.DirectShellPcmRingProbeReceiver'
$toneActivity = 'io.warpnect.debug.audio.SystemAudioE2eToneActivity'

if (-not (Test-Path -LiteralPath $apk)) { throw "Debug APK is missing: $apk" }
if (-not (Test-Path -LiteralPath $artifact)) { throw "DEX probe artifact is missing: $artifact" }
New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null

function Invoke-Adb {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    & $AdbPath -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB command failed: $($Arguments -join ' ')" }
}

function Wait-ForRemoteLog {
    param(
        [Parameter(Mandatory = $true)][string]$RemoteLog,
        [Parameter(Mandatory = $true)][string]$Marker,
        [int]$TimeoutMilliseconds = 8000
    )
    $deadline = [Environment]::TickCount64 + $TimeoutMilliseconds
    do {
        $log = (Invoke-Adb -Arguments @('shell', 'cat', $RemoteLog)) -join "`n"
        if ($log -match [regex]::Escape($Marker)) { return $log }
        Start-Sleep -Milliseconds 150
    } while ([Environment]::TickCount64 -lt $deadline)
    throw "Timed out waiting for shell server marker: $Marker"
}

function Wait-ForAppLog {
    param(
        [Parameter(Mandatory = $true)][string]$Marker,
        [int]$TimeoutMilliseconds = 8000
    )
    $deadline = [Environment]::TickCount64 + $TimeoutMilliseconds
    do {
        $logs = Invoke-Adb -Arguments @(
            'logcat', '-d', '-v', 'brief',
            'WarpnectDirectShellRing:I', 'WarpnectSystemAudio:I', '*:S'
        )
        $match = $logs | Select-String -SimpleMatch $Marker | Select-Object -Last 1
        if ($null -ne $match) { return $match.ToString() }
        Start-Sleep -Milliseconds 150
    } while ([Environment]::TickCount64 -lt $deadline)
    throw "Timed out waiting for application marker: $Marker"
}

function Get-RemoteProcessMatch {
    param([Parameter(Mandatory = $true)][string]$ProcessName)
    $processes = Invoke-Adb -Arguments @('shell', 'ps', '-A')
    return (($processes | Select-String -SimpleMatch $ProcessName) -join "`n").Trim()
}

function Invoke-AppMode {
    param(
        [Parameter(Mandatory = $true)][string]$Mode,
        [Parameter(Mandatory = $true)][int]$Port,
        [Parameter(Mandatory = $true)][string]$Secret,
        [Parameter(Mandatory = $true)][long]$Generation,
        [Parameter(Mandatory = $true)][int]$Cycle
    )
    $runId = "$Mode-$Cycle-$([Guid]::NewGuid().ToString('N'))"
    Invoke-Adb -Arguments @(
        'shell', 'am', 'broadcast', '-n', "$packageName/$controllerReceiver",
        '--ei', 'port', "$Port",
        '--es', 'secretBase64', $Secret,
        '--el', 'generationId', "$Generation",
        '--es', 'mode', $Mode,
        '--es', 'runId', $runId
    ) | Out-Null
    $diagnostic = Wait-ForAppLog -Marker "run_id=$runId DIRECT_SHELL_PCM_RING_CLIENT_DIAGNOSTICS"
    $result = Wait-ForAppLog -Marker "run_id=$runId DIRECT_SHELL_PCM_RING_CLIENT_RESULT"
    if ($result -notmatch [regex]::Escape("command=$Mode result=OK")) {
        throw "PCM ring probe app command failed: $result"
    }
    return [PSCustomObject]@{
        Mode = $Mode
        RunId = $runId
        Diagnostic = $diagnostic
        Result = $result
    }
}

function Get-MetricValue {
    param([Parameter(Mandatory = $true)][string]$Text, [Parameter(Mandatory = $true)][string]$Name)
    $match = [regex]::Match($Text, "(?:^|[;\s])$([regex]::Escape($Name))=([^;\s]+)")
    if ($match.Success) { return $match.Groups[1].Value }
    return $null
}

if (-not $SkipInstall) {
    Invoke-Adb -Arguments @('install', '-r', $apk) | Out-Host
}
Invoke-Adb -Arguments @('push', $artifact, $remoteArtifact) | Out-Host

$passed = 0
$failed = 0
$appFdCeiling = $null

for ($cycle = 1; $cycle -le $Cycles; $cycle++) {
    $secretBytes = [byte[]]::new(32)
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($secretBytes) } finally { $random.Dispose() }
    $secret = [Convert]::ToBase64String($secretBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    $port = Get-Random -Minimum 36000 -Maximum 46000
    $generation = [long]500000 + $cycle
    $serverName = "warpnect-directshell-ring-$cycle"
    $remoteLog = "/data/local/tmp/$serverName.log"
    $localLog = Join-Path $reportDirectory "$serverName.log"
    $serverCommand = "CLASSPATH=$remoteArtifact app_process /system/bin --nice-name=$serverName $serverMainClass --port $port --secret-base64 $secret --generation $generation --idle-timeout-ms 60000 >$remoteLog 2>&1 &"
    $events = [System.Collections.Generic.List[object]]::new()
    $cycleError = $null
    $armed = $false
    $finished = $false
    $shutdown = $false

    Invoke-Adb -Arguments @('logcat', '-c') | Out-Null
    Invoke-Adb -Arguments @('shell', $serverCommand) | Out-Null
    try {
        Wait-ForRemoteLog -RemoteLog $remoteLog -Marker 'START result=READY' | Out-Null
        $events.Add((Invoke-AppMode -Mode 'arm' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
        $armed = $true
        $events.Add((Invoke-AppMode -Mode 'negative-normal-caller' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
        $events.Add((Invoke-AppMode -Mode 'server-negative-stale' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
        $events.Add((Invoke-AppMode -Mode 'server-negative-magic' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
        $events.Add((Invoke-AppMode -Mode 'server-negative-size' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
        $events.Add((Invoke-AppMode -Mode 'server-negative-proof' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
        $events.Add((Invoke-AppMode -Mode 'server-bind' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
        $events.Add((Invoke-AppMode -Mode 'server-negative-replay' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
        $events.Add((Invoke-AppMode -Mode 'server-signal-test' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
        $events.Add((Invoke-AppMode -Mode 'reset-metrics' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
        $events.Add((Invoke-AppMode -Mode 'server-audio-prepare' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
        $events.Add((Invoke-AppMode -Mode 'server-audio-start' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
        Start-Sleep -Milliseconds $SilentWindowMilliseconds
        $silentMetrics = Invoke-AppMode -Mode 'metrics' -Port $port -Secret $secret -Generation $generation -Cycle $cycle
        $events.Add($silentMetrics)

        Invoke-Adb -Arguments @(
            'shell', 'am', 'start', '-W', '-n', "$packageName/$toneActivity",
            '--ei', 'durationSeconds', "$ToneDurationSeconds"
        ) | Out-Null
        $events.Add([PSCustomObject]@{ Mode = 'tone-start'; RunId = 'none'; Diagnostic = (Wait-ForAppLog -Marker 'SYSTEM_AUDIO_E2E_TONE_STARTED'); Result = '' })
        Start-Sleep -Milliseconds 350
        $events.Add((Invoke-AppMode -Mode 'reset-metrics' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
        Start-Sleep -Milliseconds $ToneWindowMilliseconds
        $toneMetrics = Invoke-AppMode -Mode 'metrics' -Port $port -Secret $secret -Generation $generation -Cycle $cycle
        $events.Add($toneMetrics)
        $serverMetrics = Invoke-AppMode -Mode 'server-diagnostics' -Port $port -Secret $secret -Generation $generation -Cycle $cycle
        $events.Add($serverMetrics)
        $events.Add([PSCustomObject]@{ Mode = 'tone-stop'; RunId = 'none'; Diagnostic = (Wait-ForAppLog -Marker 'SYSTEM_AUDIO_E2E_TONE_STOPPED' -TimeoutMilliseconds (($ToneDurationSeconds + 4) * 1000)); Result = '' })
        $events.Add((Invoke-AppMode -Mode 'server-audio-stop' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
        $events.Add((Invoke-AppMode -Mode 'server-close' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
        $events.Add((Invoke-AppMode -Mode 'finish' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
        $finished = $true
        $events.Add((Invoke-AppMode -Mode 'shutdown' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
        $shutdown = $true
    } catch {
        $cycleError = $_.Exception.Message
    } finally {
        if ($armed -and -not $finished) {
            try {
                $events.Add((Invoke-AppMode -Mode 'finish' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
                $finished = $true
            } catch { $events.Add("cleanup_finish=$($_.Exception.Message)") }
        }
        if (-not $shutdown) {
            try {
                $events.Add((Invoke-AppMode -Mode 'shutdown' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
                $shutdown = $true
            } catch { $events.Add("cleanup_shutdown=$($_.Exception.Message)") }
        }
    }

    Start-Sleep -Milliseconds 500
    $serverLog = (Invoke-Adb -Arguments @('shell', 'cat', $remoteLog)) -join "`n"
    $appLog = (Invoke-Adb -Arguments @(
        'logcat', '-d', '-v', 'brief',
        'WarpnectDirectShellRing:I', 'WarpnectSystemAudio:I', '*:S'
    )) -join "`n"
    $stillRunning = Get-RemoteProcessMatch -ProcessName $serverName
    $missing = [System.Collections.Generic.List[string]]::new()
    $requiredServer = @(
        'START result=READY',
        'uid=2000',
        'uid_kind=SHELL',
        'PCM_RING_FD_HANDOFF_OK transport=CONTENT_PROVIDER_EXTERNAL',
        'command=PCM_RING_REQUEST_STALE_GENERATION result=STALE_GENERATION_REJECTED',
        'command=PCM_RING_REQUEST_INVALID_MAGIC result=INVALID_MAGIC_REJECTED',
        'command=PCM_RING_REQUEST_OVERSIZED_METADATA result=OVERSIZED_METADATA_REJECTED',
        'command=PCM_RING_REQUEST_INVALID_PROOF result=INVALID_PROOF_REJECTED',
        'command=PCM_RING_REQUEST_REPLAY result=REPLAY_REJECTED',
        'SHELL_WRITE',
        'NOTIFY generation=',
        'SHELL_ACK_OBSERVED',
        'command=PCM_RING_AUDIO_PREPARE result=AUDIO_PREPARED',
        'command=PCM_RING_AUDIO_START result=PCM_RING_AUDIO_RECORDING',
        'command=PCM_RING_AUDIO_STOP result=PCM_RING_AUDIO_STOPPED',
        'command=PCM_RING_CLOSE result=REMOTE_CLOSED',
        'command=SHUTDOWN result=OK',
        'ENDPOINT_RELEASED transport=LOOPBACK_TCP',
        'PROCESS_EXIT code=0'
    )
    $requiredApp = @(
        'APP_RING_BRIDGE_REJECT result=CALLER_UID_REJECTED',
        'APP_RING_DESCRIPTOR_ISSUED',
        'caller_uid=2000',
        'APP_WAKE_APP_READ',
        'APP_RING_FINISH'
    )
    foreach ($required in $requiredServer) {
        if ($serverLog -notmatch [regex]::Escape($required)) { $missing.Add("server=$required") }
    }
    foreach ($required in $requiredApp) {
        if ($appLog -notmatch [regex]::Escape($required)) { $missing.Add("app=$required") }
    }
    if ($null -ne $cycleError) { $missing.Add("client=$cycleError") }

    $silentEvent = $events | Where-Object { $_ -is [PSCustomObject] -and $_.Mode -eq 'metrics' } | Select-Object -First 1
    $toneEvent = $events | Where-Object { $_ -is [PSCustomObject] -and $_.Mode -eq 'metrics' } | Select-Object -Last 1
    $serverEvent = $events | Where-Object { $_ -is [PSCustomObject] -and $_.Mode -eq 'server-diagnostics' } | Select-Object -First 1
    if ($null -eq $silentEvent -or $silentEvent.Diagnostic -notmatch 'consumer_real_pcm=false') {
        $missing.Add('pre_tone_consumer_pcm_was_not_quiet')
    }
    if ($null -eq $toneEvent -or $toneEvent.Diagnostic -notmatch 'consumer_real_pcm=true') {
        $missing.Add('tone_consumer_real_pcm_missing')
    }
    if ($null -ne $toneEvent) {
        $toneRms = Get-MetricValue -Text $toneEvent.Diagnostic -Name 'consumer_rms'
        if ($null -eq $toneRms -or [double]$toneRms -lt 1000.0) {
            $missing.Add('tone_consumer_rms_below_threshold')
        }
        $wakeups = Get-MetricValue -Text $toneEvent.Diagnostic -Name 'consumer_notification_wakeups'
        if ($null -eq $wakeups -or [long]$wakeups -le 0L) {
            $missing.Add('consumer_notification_wakeups_missing')
        }
    }
    if ($null -eq $serverEvent) {
        $missing.Add('server_diagnostics_missing')
    } else {
        $written = Get-MetricValue -Text $serverEvent.Diagnostic -Name 'shell_frames_written'
        $notifications = Get-MetricValue -Text $serverEvent.Diagnostic -Name 'shell_notifications_sent'
        $readErrors = Get-MetricValue -Text $serverEvent.Diagnostic -Name 'shell_read_errors'
        if ($null -eq $written -or [long]$written -le 0L) { $missing.Add('shell_frames_written_missing') }
        if ($null -eq $notifications -or [long]$notifications -le 0L) { $missing.Add('shell_notifications_missing') }
        if ($null -eq $readErrors -or [long]$readErrors -ne 0L) { $missing.Add('shell_read_errors_present') }
    }
    $finishMatch = [regex]::Match($appLog, "APP_RING_FINISH generation=$generation fd_before_release=(\d+) fd_after_release=(\d+)")
    if (-not $finishMatch.Success) {
        $missing.Add('app_fd_finish_metrics_missing')
    } else {
        $finishFd = [int]$finishMatch.Groups[2].Value
        if ($null -eq $appFdCeiling) {
            $appFdCeiling = $finishFd
        } elseif ($finishFd -gt $appFdCeiling) {
            $missing.Add("app_fd_growth=finish_$finishFd ceiling_$appFdCeiling")
        } elseif ($finishFd -lt $appFdCeiling) {
            $appFdCeiling = $finishFd
        }
    }

    $eventText = $events | ForEach-Object {
        if ($_ -is [PSCustomObject]) {
            "[$($_.Mode)]`n$($_.Diagnostic)`n$($_.Result)"
        } else { "$_" }
    }
    Set-Content -LiteralPath $localLog -Value (
        "[server]`n$serverLog`n[events]`n$($eventText -join "`n")`n[app]`n$appLog`n[error]`n$cycleError"
    ) -NoNewline

    if ($missing.Count -eq 0 -and [string]::IsNullOrWhiteSpace($stillRunning)) {
        $passed++
        Write-Host "DIRECTSHELL_PCM_SHARED_RING_CYCLE_$cycle=PASS generation=$generation"
    } else {
        $failed++
        Write-Host "DIRECTSHELL_PCM_SHARED_RING_CYCLE_$cycle=FAIL missing=$($missing -join ',') process=$stillRunning"
        if (-not [string]::IsNullOrWhiteSpace($stillRunning)) {
            Write-Host "DIRECTSHELL_PCM_SHARED_RING_CYCLE_$cycle=ORPHAN process=$stillRunning"
        }
    }
}

Write-Host "DIRECTSHELL_PCM_SHARED_RING_SOAK_RESULT pass=$passed fail=$failed total=$Cycles app_finish_fd_ceiling=$appFdCeiling reports=$reportDirectory"
if ($failed -ne 0) { exit 1 }
