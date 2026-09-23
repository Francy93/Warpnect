[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [string]$AdbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [int]$Cycles = 1,
    [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'

if ($Cycles -lt 1 -or $Cycles -gt 10) { throw 'Cycles must be between 1 and 10.' }
if (-not (Test-Path -LiteralPath $AdbPath)) { throw "ADB was not found: $AdbPath" }

$pendingInstalls = Get-CimInstance Win32_Process -Filter "Name = 'adb.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -match '(?i)(?:^|\s)install(?:\s|$)' }
if ($pendingInstalls) {
    throw 'A pending adb install exists; do not start a counted probe while package-manager work is active.'
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$apk = Join-Path $repoRoot 'app\build\outputs\apk\debug\app-debug.apk'
$artifact = Join-Path $repoRoot 'app\build\outputs\directshell-probe\warpnect-directshell-probe.jar'
$reportDirectory = Join-Path $repoRoot 'app\build\reports\directshell-video-capture-probe'
$remoteArtifact = '/data/local/tmp/warpnect-directshell-probe.jar'
$packageName = 'io.warpnect'
$serverMainClass = 'io.warpnect.debug.directshell.WarpnectPrivilegedVideoCaptureProbeServer'
$controllerReceiver = 'io.warpnect.debug.directshell.DirectShellVideoCaptureProbeReceiver'
$fixtureActivity = 'io.warpnect.debug.directshell.DirectShellVideoCaptureProbeActivity'

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
    throw "Timed out waiting for shell marker: $Marker"
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
            'WarpnectDirectShellVideo:I', 'WarpnectCapture:D', '*:S'
        )
        $match = $logs | Select-String -SimpleMatch $Marker | Select-Object -Last 1
        if ($null -ne $match) { return $match.ToString() }
        Start-Sleep -Milliseconds 150
    } while ([Environment]::TickCount64 -lt $deadline)
    throw "Timed out waiting for application marker: $Marker"
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
        '--es', 'runId', $runId,
        '--ei', 'displayId', '0',
        '--ei', 'width', '1280',
        '--ei', 'height', '720',
        '--ez', 'followRotation', 'true'
    ) | Out-Null
    $diagnostic = Wait-ForAppLog -Marker "run_id=$runId DIRECT_SHELL_VIDEO_CLIENT_DIAGNOSTICS"
    $result = Wait-ForAppLog -Marker "run_id=$runId DIRECT_SHELL_VIDEO_CLIENT_RESULT"
    if ($result -notmatch [regex]::Escape("command=$Mode result=OK")) {
        throw "Video probe app command failed: $result"
    }
    return [PSCustomObject]@{ Mode = $Mode; RunId = $runId; Diagnostic = $diagnostic; Result = $result }
}

function Get-MetricValue {
    param([Parameter(Mandatory = $true)][string]$Text, [Parameter(Mandatory = $true)][string]$Name)
    $match = [regex]::Match($Text, "(?:^|[;\s])$([regex]::Escape($Name))=([^;\s]+)")
    if ($match.Success) { return $match.Groups[1].Value }
    return $null
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

$passed = 0
$failed = 0

for ($cycle = 1; $cycle -le $Cycles; $cycle++) {
    $secretBytes = [byte[]]::new(32)
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($secretBytes) } finally { $random.Dispose() }
    $secret = [Convert]::ToBase64String($secretBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    $port = Get-Random -Minimum 36000 -Maximum 46000
    $generation = [long]910000 + $cycle
    $serverName = "warpnect-directshell-video-$cycle-$([Guid]::NewGuid().ToString('N').Substring(0, 8))"
    $remoteLog = "/data/local/tmp/$serverName.log"
    $localLog = Join-Path $reportDirectory "$serverName.log"
    $stateA = Join-Path $reportDirectory "$serverName-state-a.png"
    $stateB = Join-Path $reportDirectory "$serverName-state-b.png"
    $serverCommand = "CLASSPATH=$remoteArtifact app_process /system/bin --nice-name=$serverName $serverMainClass --port $port --secret-base64 $secret --generation $generation --idle-timeout-ms 60000 >$remoteLog 2>&1 &"
    $events = [System.Collections.Generic.List[object]]::new()
    $cycleError = $null
    $armed = $false
    $started = $false
    $finished = $false
    $shutdown = $false

    Invoke-Adb -Arguments @('logcat', '-c') | Out-Null
    Invoke-Adb -Arguments @('shell', 'am', 'start', '-W', '-n', "$packageName/$fixtureActivity", '--es', 'state', 'a') | Out-Null
    $events.Add([PSCustomObject]@{ Mode = 'fixture-a'; RunId = 'none'; Diagnostic = (Wait-ForAppLog -Marker 'DIRECT_SHELL_VIDEO_FIXTURE_STATE state=a'); Result = 'OK' })
    Invoke-Adb -Arguments @('shell', $serverCommand) | Out-Null
    try {
        Wait-ForRemoteLog -RemoteLog $remoteLog -Marker 'START result=READY' | Out-Null
        $events.Add((Invoke-AppMode -Mode 'arm' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
        $armed = $true
        $events.Add((Invoke-AppMode -Mode 'qualify' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
        $events.Add((Invoke-AppMode -Mode 'start' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
        $started = $true
        Start-Sleep -Milliseconds 1800
        & $AdbPath -s $Serial exec-out screencap -p > $stateA
        if ($LASTEXITCODE -ne 0) { throw 'ADB screenshot state A failed.' }
        $events.Add((Invoke-AppMode -Mode 'metrics' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
        Invoke-Adb -Arguments @('shell', 'am', 'start', '-W', '-n', "$packageName/$fixtureActivity", '--es', 'state', 'b') | Out-Null
        $events.Add([PSCustomObject]@{ Mode = 'fixture-b'; RunId = 'none'; Diagnostic = (Wait-ForAppLog -Marker 'DIRECT_SHELL_VIDEO_FIXTURE_STATE state=b'); Result = 'OK' })
        Start-Sleep -Milliseconds 1800
        & $AdbPath -s $Serial exec-out screencap -p > $stateB
        if ($LASTEXITCODE -ne 0) { throw 'ADB screenshot state B failed.' }
        $events.Add((Invoke-AppMode -Mode 'metrics' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
        $events.Add((Invoke-AppMode -Mode 'diagnostics' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
        $events.Add((Invoke-AppMode -Mode 'stop' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
        $started = $false
        $events.Add((Invoke-AppMode -Mode 'finish' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
        $finished = $true
        $events.Add((Invoke-AppMode -Mode 'shutdown' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
        $shutdown = $true
    } catch {
        $cycleError = $_.Exception.Message
    } finally {
        if ($started) {
            try { $events.Add((Invoke-AppMode -Mode 'stop' -Port $port -Secret $secret -Generation $generation -Cycle $cycle)) } catch { $events.Add("cleanup_stop=$($_.Exception.Message)") }
        }
        if ($armed -and -not $finished) {
            try { $events.Add((Invoke-AppMode -Mode 'finish' -Port $port -Secret $secret -Generation $generation -Cycle $cycle)); $finished = $true } catch { $events.Add("cleanup_finish=$($_.Exception.Message)") }
        }
        if (-not $shutdown) {
            try { $events.Add((Invoke-AppMode -Mode 'shutdown' -Port $port -Secret $secret -Generation $generation -Cycle $cycle)); $shutdown = $true } catch { $events.Add("cleanup_shutdown=$($_.Exception.Message)") }
        }
    }

    Start-Sleep -Milliseconds 500
    $serverLog = (Invoke-Adb -Arguments @('shell', 'cat', $remoteLog)) -join "`n"
    $appLog = (Invoke-Adb -Arguments @('logcat', '-d', '-v', 'brief', 'WarpnectDirectShellVideo:I', 'WarpnectCapture:D', '*:S')) -join "`n"
    $stillRunning = Get-RemoteProcessMatch -ProcessName $serverName
    $missing = [System.Collections.Generic.List[string]]::new()
    $requiredServer = @(
        'START result=READY', 'uid=2000', 'uid_kind=SHELL',
        'VIDEO_QUALIFIED', 'VIDEO_MIRROR_STARTED', 'command=VIDEO_START result=DISPLAY_MIRROR_RUNNING',
        'command=VIDEO_STOP result=DISPLAY_MIRROR_STOPPED', 'command=SHUTDOWN result=OK',
        'ENDPOINT_RELEASED transport=LOOPBACK_TCP', 'PROCESS_EXIT code=0'
    )
    $requiredApp = @('APP_VIDEO_ENCODER_ARM', 'APP_VIDEO_SURFACE_ISSUED', 'caller_uid=2000', 'APP_VIDEO_ENCODER_FINISH')
    foreach ($required in $requiredServer) { if ($serverLog -notmatch [regex]::Escape($required)) { $missing.Add("server=$required") } }
    foreach ($required in $requiredApp) { if ($appLog -notmatch [regex]::Escape($required)) { $missing.Add("app=$required") } }
    $metrics = @($events | Where-Object { $_ -is [PSCustomObject] -and $_.Mode -eq 'metrics' })
    if ($metrics.Count -ne 2) {
        $missing.Add('expected_two_encoder_metric_snapshots')
    } else {
        $aUnits = Get-MetricValue -Text $metrics[0].Diagnostic -Name 'access_units'
        $bUnits = Get-MetricValue -Text $metrics[1].Diagnostic -Name 'access_units'
        $aFormats = Get-MetricValue -Text $metrics[0].Diagnostic -Name 'output_formats'
        $aHash = Get-MetricValue -Text $metrics[0].Diagnostic -Name 'sample_hash'
        $bHash = Get-MetricValue -Text $metrics[1].Diagnostic -Name 'sample_hash'
        if ($null -eq $aUnits -or [long]$aUnits -le 0) { $missing.Add('state_a_access_units_missing') }
        if ($null -eq $bUnits -or [long]$bUnits -le [long]$aUnits) { $missing.Add('state_b_access_units_not_advancing') }
        if ($null -eq $aFormats -or [long]$aFormats -le 0) { $missing.Add('codec_configuration_missing') }
        if ($null -eq $aHash -or $null -eq $bHash -or $aHash -eq $bHash) { $missing.Add('visual_state_hash_not_changed') }
    }
    if (-not (Test-Path -LiteralPath $stateA) -or -not (Test-Path -LiteralPath $stateB)) { $missing.Add('host_screenshots_missing') }
    if ($null -ne $cycleError) { $missing.Add("client=$cycleError") }
    Set-Content -LiteralPath $localLog -Value (
        "[server]`n$serverLog`n[events]`n$($events | Out-String)`n[app_log]`n$appLog`n[error]`n$cycleError"
    ) -NoNewline
    if ($missing.Count -eq 0 -and [string]::IsNullOrWhiteSpace($stillRunning)) {
        $passed++
        Write-Host "DIRECTSHELL_VIDEO_CAPTURE_CYCLE_$cycle=PASS"
    } else {
        $failed++
        Write-Host "DIRECTSHELL_VIDEO_CAPTURE_CYCLE_$cycle=FAIL missing=$($missing -join ',') process=$stillRunning"
    }
}

Write-Host "DIRECTSHELL_VIDEO_CAPTURE_RESULT pass=$passed fail=$failed total=$Cycles reports=$reportDirectory"
if ($failed -ne 0) { exit 1 }
