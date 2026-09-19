[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$HostSerial,
    [Parameter(Mandatory = $true)]
    [string]$ClientSerial,
    [string]$AdbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [ValidateRange(1, 10)]
    [int]$Cycles = 5,
    [ValidateRange(30, 120)]
    [int]$ToneDurationSeconds = 60,
    [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $AdbPath)) { throw "ADB was not found: $AdbPath" }
$pendingInstalls = Get-CimInstance Win32_Process -Filter "Name = 'adb.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -match '(?i)(?:^|\s)install(?:\s|$)' }
if ($pendingInstalls) {
    throw 'A pending adb install exists; do not start a counted DirectShell remote E2E attempt.'
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$apk = Join-Path $repoRoot 'app\build\outputs\apk\debug\app-debug.apk'
$artifact = Join-Path $repoRoot 'app\build\outputs\directshell-probe\warpnect-directshell-probe.jar'
$sessionHarness = Join-Path $repoRoot 'tools\android-h1\run.ps1'
$reportRoot = Join-Path $repoRoot 'app\build\reports\directshell-systemaudio-remote-e2e'
$runTimestampUtc = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ', [Globalization.CultureInfo]::InvariantCulture)
$reportDirectory = Join-Path $reportRoot "run-$runTimestampUtc"
$remoteArtifact = '/data/local/tmp/warpnect-directshell-probe.jar'
$packageName = 'io.warpnect'
$serverMainClass = 'io.warpnect.debug.directshell.WarpnectPrivilegedPcmSharedRingProbeServer'
$sourceReceiver = 'io.warpnect.debug.directshell.DirectShellSystemAudioRemoteE2eReceiver'
$ringReceiver = 'io.warpnect.debug.directshell.DirectShellPcmRingProbeReceiver'
$toneActivity = 'io.warpnect.debug.audio.SystemAudioE2eToneActivity'

if (-not (Test-Path -LiteralPath $apk)) { throw "Debug APK is missing: $apk" }
if (-not (Test-Path -LiteralPath $artifact)) { throw "DEX probe artifact is missing: $artifact" }
if (-not (Test-Path -LiteralPath $sessionHarness)) { throw "Official Session harness is missing: $sessionHarness" }
New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null

function Invoke-Adb {
    param([Parameter(Mandatory = $true)][string]$Serial, [Parameter(Mandatory = $true)][string[]]$Arguments)
    & $AdbPath -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB failed for ${Serial}: $($Arguments -join ' ')" }
}

function Invoke-AdbText {
    param([Parameter(Mandatory = $true)][string]$Serial, [Parameter(Mandatory = $true)][string[]]$Arguments)
    $value = & $AdbPath -s $Serial @Arguments 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) { throw "ADB failed for ${Serial}: $($Arguments -join ' ')" }
    return $value.Trim()
}

function Get-LocalSha256 {
    param([Parameter(Mandatory = $true)][string]$Path)
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash
}

function Get-InstalledBaseApkHash {
    param([Parameter(Mandatory = $true)][string]$Serial)
    $pathLine = (Invoke-AdbText $Serial @('shell', 'pm', 'path', $packageName)) -split "`r?`n" |
        Where-Object { $_ -match '^package:' } |
        Select-Object -First 1
    if ([string]::IsNullOrWhiteSpace($pathLine)) { return 'unavailable' }
    $baseApk = $pathLine.Substring('package:'.Length)
    $hashLine = Invoke-AdbText $Serial @('shell', 'sha256sum', $baseApk)
    return ($hashLine -split '\s+')[0]
}

function Get-AppLog {
    param([Parameter(Mandatory = $true)][string]$Serial)
    return (& $AdbPath -s $Serial logcat -d -v brief `
        'WarpnectDirectShellE2e:I' 'WarpnectDirectShellRing:I' 'WarpnectDiscovery:I' `
        'WarpnectSystemAudio:I' '*:S' 2>&1 | Out-String)
}

function Wait-ForAppLog {
    param(
        [Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)][string]$Marker,
        [int]$TimeoutMilliseconds = 15000
    )
    $elapsed = [Diagnostics.Stopwatch]::StartNew()
    do {
        $logs = Get-AppLog $Serial
        # Split first: Select-String treats a multi-line string as one input record, which would
        # return the entire buffer instead of the matching log line for per-window metrics.
        $match = ($logs -split "`r?`n") | Select-String -SimpleMatch $Marker | Select-Object -Last 1
        if ($null -ne $match) { return $match.ToString() }
        Start-Sleep -Milliseconds 200
    } while ($elapsed.ElapsedMilliseconds -lt $TimeoutMilliseconds)
    throw "Timed out waiting for app log marker: $Marker"
}

function Wait-ForServerLog {
    param(
        [Parameter(Mandatory = $true)][string]$RemoteLog,
        [Parameter(Mandatory = $true)][string]$Marker,
        [int]$TimeoutMilliseconds = 15000
    )
    $elapsed = [Diagnostics.Stopwatch]::StartNew()
    do {
        $value = Invoke-AdbText $HostSerial @('shell', 'cat', $RemoteLog)
        if ($value -match [regex]::Escape($Marker)) { return $value }
        Start-Sleep -Milliseconds 200
    } while ($elapsed.ElapsedMilliseconds -lt $TimeoutMilliseconds)
    throw "Timed out waiting for shell server marker: $Marker"
}

function Get-UiDocument {
    param([Parameter(Mandatory = $true)][string]$Serial)
    $raw = Invoke-AdbText $Serial @('exec-out', 'uiautomator', 'dump', '/dev/tty')
    $end = $raw.LastIndexOf('</hierarchy>', [System.StringComparison]::Ordinal)
    if ($end -lt 0) { throw "UI hierarchy was unavailable on $Serial" }
    [xml]$document = $raw.Substring(0, $end + '</hierarchy>'.Length)
    return $document
}

function Get-UiTapPoint {
    param([Parameter(Mandatory = $true)][string]$Serial, [Parameter(Mandatory = $true)][string]$Text)
    $node = @((Get-UiDocument $Serial).SelectNodes('//node')) | Where-Object {
        $_.text -eq $Text -or $_.'content-desc' -eq $Text
    } | Select-Object -First 1
    if ($null -eq $node) { return $null }
    $current = $node
    while ($null -ne $current -and $current.clickable -ne 'true') { $current = $current.ParentNode }
    if ($null -eq $current) { $current = $node }
    $bounds = $current.bounds
    if ($bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { return $null }
    return [PSCustomObject]@{
        X = [int](([int]$matches[1] + [int]$matches[3]) / 2)
        Y = [int](([int]$matches[2] + [int]$matches[4]) / 2)
    }
}

function Tap-IfPresent {
    param([Parameter(Mandatory = $true)][string]$Serial, [Parameter(Mandatory = $true)][string]$Text)
    $point = Get-UiTapPoint $Serial $Text
    if ($null -eq $point) {
        Invoke-Adb $Serial @('shell', 'input', 'swipe', '540', '1750', '540', '700', '250')
        Start-Sleep -Milliseconds 250
        $point = Get-UiTapPoint $Serial $Text
    }
    if ($null -eq $point) { return $false }
    Invoke-Adb $Serial @('shell', 'input', 'tap', "$($point.X)", "$($point.Y)")
    return $true
}

function Test-AppReady {
    param([Parameter(Mandatory = $true)][string]$Serial)
    return @((Get-UiDocument $Serial).SelectNodes('//node')) | Where-Object {
        $_.text -eq 'Ready' -or $_.'content-desc' -eq 'Ready'
    } | Select-Object -First 1
}

function Stop-SessionSemantically {
    [void](Tap-IfPresent $ClientSerial 'Disconnect')
    [void](Tap-IfPresent $ClientSerial 'Cancel search')
    [void](Tap-IfPresent $HostSerial 'Disable Host')
}

function Invoke-SourceMode {
    param(
        [Parameter(Mandatory = $true)][string]$Mode,
        [Parameter(Mandatory = $true)][int]$Port,
        [Parameter(Mandatory = $true)][string]$Secret,
        [Parameter(Mandatory = $true)][long]$Generation,
        [Parameter(Mandatory = $true)][int]$Cycle
    )
    $runId = "source-$Mode-$Cycle-$([Guid]::NewGuid().ToString('N'))"
    Invoke-Adb $HostSerial @(
        'shell', 'am', 'broadcast', '-n', "$packageName/$sourceReceiver",
        '--ei', 'port', "$Port",
        '--es', 'secretBase64', $Secret,
        '--el', 'generationId', "$Generation",
        '--es', 'mode', $Mode,
        '--es', 'runId', $runId
    ) | Out-Null
    $diagnostic = Wait-ForAppLog $HostSerial "run_id=$runId DIRECT_SHELL_REMOTE_E2E_DIAGNOSTICS"
    $result = Wait-ForAppLog $HostSerial "run_id=$runId DIRECT_SHELL_REMOTE_E2E_RESULT"
    if ($result -notmatch [regex]::Escape("command=$Mode result=OK")) {
        throw "DirectShell source command failed: $result"
    }
    return [PSCustomObject]@{ Mode = $Mode; RunId = $runId; Diagnostic = $diagnostic; Result = $result }
}

function Invoke-RingMode {
    param(
        [Parameter(Mandatory = $true)][string]$Mode,
        [Parameter(Mandatory = $true)][int]$Port,
        [Parameter(Mandatory = $true)][string]$Secret,
        [Parameter(Mandatory = $true)][long]$Generation,
        [Parameter(Mandatory = $true)][int]$Cycle
    )
    $runId = "ring-$Mode-$Cycle-$([Guid]::NewGuid().ToString('N'))"
    Invoke-Adb $HostSerial @(
        'shell', 'am', 'broadcast', '-n', "$packageName/$ringReceiver",
        '--ei', 'port', "$Port",
        '--es', 'secretBase64', $Secret,
        '--el', 'generationId', "$Generation",
        '--es', 'mode', $Mode,
        '--es', 'runId', $runId
    ) | Out-Null
    $diagnostic = Wait-ForAppLog $HostSerial "run_id=$runId DIRECT_SHELL_PCM_RING_CLIENT_DIAGNOSTICS"
    $result = Wait-ForAppLog $HostSerial "run_id=$runId DIRECT_SHELL_PCM_RING_CLIENT_RESULT"
    if ($result -notmatch [regex]::Escape("command=$Mode result=OK")) {
        throw "DirectShell ring command failed: $result"
    }
    return [PSCustomObject]@{ Mode = $Mode; RunId = $runId; Diagnostic = $diagnostic; Result = $result }
}

function Get-Metric {
    param([string]$Text, [string]$Name)
    $match = [regex]::Match($Text, "(?:^|[;\s])$([regex]::Escape($Name))=([^;\s]+)")
    if ($match.Success) { return $match.Groups[1].Value }
    return $null
}

function Test-PositiveMetric {
    param([string]$Text, [string]$Name)
    $value = Get-Metric $Text $Name
    if ($null -eq $value) { return $false }
    [double]$number = 0
    return [double]::TryParse($value, [ref]$number) -and $number -gt 0
}

function Get-NumericMetric {
    param([string]$Text, [string]$Name)
    $value = Get-Metric $Text $Name
    if ($null -eq $value) { return $null }
    [double]$number = 0
    if (-not [double]::TryParse($value, [ref]$number)) { return $null }
    return $number
}

function Get-LastLine {
    param([string]$Text, [string]$Marker)
    return ($Text -split "`r?`n" | Where-Object { $_ -like "*$Marker*" } | Select-Object -Last 1)
}

function Get-RemoteProcessMatch {
    param([string]$ProcessName)
    return ((Invoke-AdbText $HostSerial @('shell', 'ps', '-A')) | Select-String -SimpleMatch $ProcessName | Out-String).Trim()
}

if (-not $SkipInstall) {
    Invoke-Adb $HostSerial @('install', '-r', $apk) | Out-Host
    Invoke-Adb $ClientSerial @('install', '-r', $apk) | Out-Host
}

$postInstallPending = Get-CimInstance Win32_Process -Filter "Name = 'adb.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -match '(?i)(?:^|\s)install(?:\s|$)' }
if ($postInstallPending) { throw 'An adb install remains active after installation completed.' }

$localHash = Get-LocalSha256 $apk
$apkEvidence = [PSCustomObject]@{
    local_sha256 = $localHash
    local_bytes = (Get-Item -LiteralPath $apk).Length
    host_installed_sha256 = Get-InstalledBaseApkHash $HostSerial
    client_installed_sha256 = Get-InstalledBaseApkHash $ClientSerial
}
if ($apkEvidence.host_installed_sha256 -ne $localHash -or $apkEvidence.client_installed_sha256 -ne $localHash) {
    throw 'The exact same local debug APK is not installed on both devices.'
}
foreach ($serial in @($HostSerial, $ClientSerial)) {
    if ($null -eq (Test-AppReady $serial)) {
        throw "INVALID_HARNESS_ATTEMPT: -ReuseRunningApps requires io.warpnect Ready on $serial before a counted cycle."
    }
}
Invoke-Adb $HostSerial @('push', $artifact, $remoteArtifact) | Out-Null

$passed = 0
$failed = 0
$cycleEvidence = [System.Collections.Generic.List[object]]::new()

for ($cycle = 1; $cycle -le $Cycles; $cycle++) {
    $secretBytes = [byte[]]::new(32)
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($secretBytes) } finally { $random.Dispose() }
    $secret = [Convert]::ToBase64String($secretBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    $port = Get-Random -Minimum 36000 -Maximum 46000
    $generation = [long](900000 + $cycle)
    $serverName = "warpnect-directshell-remote-e2e-$cycle"
    $remoteLog = "/data/local/tmp/$serverName.log"
    $events = [System.Collections.Generic.List[object]]::new()
    $cycleError = $null
    $sessionRunning = $false
    $sourceArmed = $false

    if (-not [string]::IsNullOrWhiteSpace((Get-RemoteProcessMatch $serverName))) {
        throw "A stale DirectShell server exists before cycle $cycle; no cleanup by process kill is permitted."
    }

    $serverCommand = "CLASSPATH=$remoteArtifact app_process /system/bin --nice-name=$serverName $serverMainClass --port $port --secret-base64 $secret --generation $generation --idle-timeout-ms 90000 >$remoteLog 2>&1 &"
    Invoke-Adb $HostSerial @('logcat', '-c') | Out-Null
    Invoke-Adb $ClientSerial @('logcat', '-c') | Out-Null
    Invoke-Adb $HostSerial @('shell', $serverCommand) | Out-Null

    try {
        Wait-ForServerLog $remoteLog 'START result=READY' | Out-Null
        $events.Add((Invoke-SourceMode 'arm' $port $secret $generation $cycle))
        $sourceArmed = $true

        $sessionOutput = & $sessionHarness -Scenario MediaStartupTrace -DeviceA $HostSerial -DeviceB $ClientSerial `
            -SkipBuild -SkipInstall -ReuseRunningApps -LeaveSessionRunning 2>&1 | Out-String
        if ($LASTEXITCODE -ne 0) { throw "Official Session harness failed: $sessionOutput" }
        $events.Add([PSCustomObject]@{ Mode = 'session'; Diagnostic = $sessionOutput; Result = 'PASS' })
        $sessionRunning = $true
        [void](Wait-ForAppLog $HostSerial 'DIRECT_SHELL_REMOTE_E2E_SOURCE_SELECTED' 20000)

        $events.Add((Invoke-RingMode 'reset-metrics' $port $secret $generation $cycle))
        Start-Sleep -Seconds 6
        $silentMetrics = Invoke-RingMode 'metrics' $port $secret $generation $cycle
        $events.Add($silentMetrics)

        Invoke-Adb $HostSerial @(
            'shell', 'am', 'start', '-W', '-n', "$packageName/$toneActivity",
            '--ei', 'durationSeconds', "$ToneDurationSeconds"
        ) | Out-Null
        $events.Add([PSCustomObject]@{
            Mode = 'tone-start'
            Diagnostic = Wait-ForAppLog $HostSerial 'SYSTEM_AUDIO_E2E_TONE_STARTED' 15000
            Result = 'PASS'
        })
        Start-Sleep -Milliseconds 350
        $events.Add((Invoke-RingMode 'reset-metrics' $port $secret $generation $cycle))
        Start-Sleep -Seconds ([Math]::Min(30, $ToneDurationSeconds - 5))
        $toneMetrics = Invoke-RingMode 'metrics' $port $secret $generation $cycle
        $events.Add($toneMetrics)
        $remainingToneSeconds = $ToneDurationSeconds - [Math]::Min(30, $ToneDurationSeconds - 5)
        if ($remainingToneSeconds -gt 0) { Start-Sleep -Seconds $remainingToneSeconds }
        $events.Add([PSCustomObject]@{
            Mode = 'tone-stop'
            Diagnostic = Wait-ForAppLog $HostSerial 'SYSTEM_AUDIO_E2E_TONE_STOPPED' 15000
            Result = 'PASS'
        })
    } catch {
        $cycleError = $_.Exception.Message
    } finally {
        # Always restore the real UI state, including failures before Session RUNNING. This does
        # not retry the attempt; it prevents an abandoned Client scan from contaminating the next one.
        try { Stop-SessionSemantically } catch { $events.Add("semantic_teardown=$($_.Exception.Message)") }
        if ($sourceArmed) {
            try { $events.Add((Invoke-SourceMode 'disarm' $port $secret $generation $cycle)) } catch {
                $events.Add("source_disarm=$($_.Exception.Message)")
            }
        }
        # If startup failed before a Session owned shutdown, request the bounded server shutdown.
        if (-not $sessionRunning) {
            try {
                $events.Add((Invoke-RingMode 'shutdown' $port $secret $generation $cycle))
            } catch {
                $events.Add("server_shutdown=$($_.Exception.Message)")
            }
        }
    }

    Start-Sleep -Milliseconds 800
    $serverLog = Invoke-AdbText $HostSerial @('shell', 'cat', $remoteLog)
    $hostLog = Get-AppLog $HostSerial
    $clientLog = Get-AppLog $ClientSerial
    $hostRuntime = Get-LastLine $hostLog 'event=audio_runtime role=host'
    $clientRuntime = Get-LastLine $clientLog 'event=audio_runtime role=client'
    $directServerMetrics = Get-LastLine $hostLog 'DIRECT_SHELL_REMOTE_E2E_SERVER_DIAGNOSTICS generation='
    $directAppMetrics = Get-LastLine $hostLog 'DIRECT_SHELL_REMOTE_E2E_APP_RING_METRICS generation='
    $silentEvent = $events | Where-Object { $_ -is [PSCustomObject] -and $_.Mode -eq 'metrics' } | Select-Object -First 1
    $toneEvent = $events | Where-Object { $_ -is [PSCustomObject] -and $_.Mode -eq 'metrics' } | Select-Object -Last 1
    $missing = [System.Collections.Generic.List[string]]::new()
    $boundedConditions = [System.Collections.Generic.List[string]]::new()

    if ($null -ne $cycleError) { $missing.Add("attempt=$cycleError") }
    foreach ($required in @(
        'DIRECT_SHELL_REMOTE_E2E_SOURCE_SELECTED',
        'shizuku_audio_producer=false',
        'DIRECT_SHELL_REMOTE_E2E_CAPTURE_RUNNING'
    )) {
        if ($hostLog -notmatch [regex]::Escape($required)) { $missing.Add("host_log=$required") }
    }
    foreach ($required in @('uid=2000', 'uid_kind=SHELL', 'PCM_RING_FD_HANDOFF_OK')) {
        if ($serverLog -notmatch [regex]::Escape($required)) { $missing.Add("server_log=$required") }
    }
    $silentRms = if ($null -eq $silentEvent) { $null } else { Get-NumericMetric $silentEvent.Diagnostic 'consumer_rms' }
    $toneRms = if ($null -eq $toneEvent) { $null } else { Get-NumericMetric $toneEvent.Diagnostic 'consumer_rms' }
    if ($null -eq $silentRms -or $silentRms -gt 100.0) {
        $missing.Add('pre_tone_normal_process_pcm_was_not_quiet')
    }
    if ($null -eq $toneEvent -or -not (Test-PositiveMetric $toneEvent.Diagnostic 'consumer_frames')) {
        $missing.Add('tone_normal_process_drain_missing')
    }
    if ($null -eq $toneEvent -or -not (Test-PositiveMetric $toneEvent.Diagnostic 'consumer_non_zero_samples')) {
        $missing.Add('tone_normal_process_non_zero_pcm_missing')
    }
    if ($null -eq $toneRms -or $toneRms -le 1000.0) {
        $missing.Add('tone_normal_process_rms_below_threshold')
    }
    foreach ($metric in @('shell_frames_captured', 'shell_frames_written', 'shell_notifications_sent')) {
        if (-not (Test-PositiveMetric $directServerMetrics $metric)) { $missing.Add("shell_metric=$metric") }
    }
    foreach ($metric in @('capture_frames', 'encoder_pcm_frames', 'encoder_frames', 'payload_frames', 'datagrams_sent')) {
        if (-not (Test-PositiveMetric $hostRuntime $metric)) { $missing.Add("host_metric=$metric") }
    }
    foreach ($metric in @('audio_datagrams_received', 'frames_received', 'frames_decoded', 'playback_written', 'playback_consumed')) {
        if (-not (Test-PositiveMetric $clientRuntime $metric)) { $missing.Add("client_metric=$metric") }
    }
    if ($clientRuntime -notmatch 'receiver_state=Running') { $missing.Add('client_receiver_not_running') }
    if ($clientRuntime -notmatch 'playback_state=Running') { $missing.Add('client_playback_not_running') }
    if ($hostRuntime -notmatch 'transport_error=None') { $missing.Add('host_transport_error') }
    if ($clientRuntime -notmatch 'receiver_error=None') { $missing.Add('client_receiver_error') }
    $playbackError = Get-Metric $clientRuntime 'playback_error'
    if ($playbackError -eq 'PlaybackRingFull') {
        # The production receiver deliberately treats a full bounded playback ring as a real-time
        # drop, not a terminal pipeline failure. Require an active consumer and zero xruns above.
        $boundedConditions.Add('client_playback_ring_full_drop')
    } elseif ($playbackError -ne 'None') {
        $missing.Add('client_playback_error')
    }
    if ((Get-Metric $clientRuntime 'playback_xruns') -ne '0') { $missing.Add('client_playback_xruns') }
    if ($serverLog -match 'shell_read_errors=[1-9]') { $missing.Add('shell_read_errors') }
    $orphan = Get-RemoteProcessMatch $serverName
    if (-not [string]::IsNullOrWhiteSpace($orphan)) { $missing.Add("orphan=$orphan") }

    $eventText = $events | ForEach-Object {
        if ($_ -is [PSCustomObject]) { "[$($_.Mode)]`n$($_.Diagnostic)`n$($_.Result)" } else { "$_" }
    }
    $cycleReport = Join-Path $reportDirectory ("cycle-{0:D2}.log" -f $cycle)
    Set-Content -LiteralPath $cycleReport -Value (
        "[apk]`n$($apkEvidence | ConvertTo-Json)`n[server]`n$serverLog`n[events]`n$($eventText -join "`n")" +
        "`n[host_runtime]`n$hostRuntime`n[client_runtime]`n$clientRuntime`n[host_direct_server]`n$directServerMetrics" +
        "`n[host_direct_app]`n$directAppMetrics`n[error]`n$cycleError"
    ) -NoNewline

    if ($missing.Count -eq 0) {
        $passed++
        Write-Host "DIRECTSHELL_REMOTE_E2E_CYCLE_$cycle=PASS generation=$generation"
    } else {
        $failed++
        Write-Host "DIRECTSHELL_REMOTE_E2E_CYCLE_$cycle=FAIL missing=$($missing -join ',')"
    }
    $cycleEvidence.Add([PSCustomObject]@{
        cycle = $cycle
        generation = $generation
        result = if ($missing.Count -eq 0) { 'PASS' } else { 'FAIL' }
        missing = @($missing)
        bounded_conditions = @($boundedConditions)
        report = $cycleReport
    })
}

$summary = [PSCustomObject]@{
    host_serial = $HostSerial
    client_serial = $ClientSerial
    tone_frequency_hz = 997
    tone_duration_seconds = $ToneDurationSeconds
    run_started_utc = $runTimestampUtc
    cycles = $Cycles
    passed = $passed
    failed = $failed
    apk = $apkEvidence
    evidence = @($cycleEvidence)
}
$summaryPath = Join-Path $reportDirectory 'summary.json'
$summary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $summaryPath -NoNewline
Write-Host "DIRECTSHELL_REMOTE_E2E_SUMMARY pass=$passed fail=$failed total=$Cycles reports=$reportDirectory"
if ($failed -ne 0) { exit 1 }
