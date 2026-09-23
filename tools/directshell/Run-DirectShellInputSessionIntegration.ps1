[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [string]$AdbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [ValidateRange(1, 20)]
    [int]$Cycles = 1,
    [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $AdbPath)) { throw "ADB was not found: $AdbPath" }
$pendingInstalls = Get-CimInstance Win32_Process -Filter "Name = 'adb.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -match '(?i)(?:^|\s)install(?:\s|$)' }
if ($pendingInstalls) {
    throw 'A pending adb install exists; do not start a counted DirectShell Input integration attempt.'
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$apk = Join-Path $repoRoot 'app\build\outputs\apk\debug\app-debug.apk'
$artifact = Join-Path $repoRoot 'app\build\outputs\directshell-probe\warpnect-directshell-probe.jar'
$reportRoot = Join-Path $repoRoot 'app\build\reports\directshell-input-session-integration'
$runTimestampUtc = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ', [Globalization.CultureInfo]::InvariantCulture)
$reportDirectory = Join-Path $reportRoot "run-$runTimestampUtc"
$remoteArtifact = '/data/local/tmp/warpnect-directshell-probe.jar'
$packageName = 'io.warpnect'
$serverMainClass = 'io.warpnect.debug.directshell.WarpnectPrivilegedInputProbeServer'
$receiver = 'io.warpnect.debug.directshell.DirectShellInputSessionIntegrationReceiver'
$targetActivity = 'io.warpnect.debug.input.InputSessionTargetActivity'

if (-not (Test-Path -LiteralPath $apk)) { throw "Debug APK is missing: $apk" }
if (-not (Test-Path -LiteralPath $artifact)) { throw "DEX probe artifact is missing: $artifact" }
New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null

function Invoke-Adb {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    & $AdbPath -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB command failed: $($Arguments -join ' ')" }
}

function Invoke-AdbText {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    $result = & $AdbPath -s $Serial @Arguments 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) { throw "ADB command failed: $($Arguments -join ' ')" }
    return $result.Trim()
}

function Get-AppLog {
    return (Invoke-AdbText -Arguments @(
        'logcat', '-d', '-v', 'brief',
        'WarpnectInputSessionTarget:I', 'WarpnectDirectShellInput:I', 'WarpnectPrivilegedInput:D', '*:S'
    ))
}

function Wait-ForAppMarker {
    param(
        [Parameter(Mandatory = $true)][string]$Marker,
        [int]$TimeoutMilliseconds = 15000
    )
    $deadline = [Environment]::TickCount64 + $TimeoutMilliseconds
    do {
        $line = ((Get-AppLog) -split "`r?`n") |
            Select-String -SimpleMatch $Marker |
            Select-Object -Last 1
        if ($null -ne $line) { return $line.ToString() }
        Start-Sleep -Milliseconds 150
    } while ([Environment]::TickCount64 -lt $deadline)
    throw "Timed out waiting for app log marker: $Marker"
}

function Wait-ForServerMarker {
    param(
        [Parameter(Mandatory = $true)][string]$RemoteLog,
        [Parameter(Mandatory = $true)][string]$Marker,
        [int]$TimeoutMilliseconds = 15000
    )
    $deadline = [Environment]::TickCount64 + $TimeoutMilliseconds
    do {
        $contents = try {
            Invoke-AdbText -Arguments @('shell', 'cat', $RemoteLog)
        } catch {
            $null
        }
        if ($contents -match [regex]::Escape($Marker)) { return $contents }
        Start-Sleep -Milliseconds 150
    } while ([Environment]::TickCount64 -lt $deadline)
    throw "Timed out waiting for shell server marker: $Marker"
}

function Invoke-IntegrationCommand {
    param(
        [Parameter(Mandatory = $true)][string]$Mode,
        [Parameter(Mandatory = $true)][int]$Port,
        [Parameter(Mandatory = $true)][string]$Secret,
        [Parameter(Mandatory = $true)][long]$Generation,
        [Parameter(Mandatory = $true)][int]$Cycle
    )
    $runId = "input-session-$Mode-$Cycle-$([Guid]::NewGuid().ToString('N'))"
    Invoke-Adb -Arguments @(
        'shell', 'am', 'broadcast', '-n', "$packageName/$receiver",
        '--ei', 'port', "$Port",
        '--es', 'secretBase64', $Secret,
        '--el', 'generationId', "$Generation",
        '--es', 'mode', $Mode,
        '--es', 'runId', $runId
    ) | Out-Null
    $diagnostic = Wait-ForAppMarker -Marker "run_id=$runId DIRECT_SHELL_INPUT_SESSION_DIAGNOSTICS"
    $result = Wait-ForAppMarker -Marker "run_id=$runId DIRECT_SHELL_INPUT_SESSION_RESULT"
    if ($result -notmatch [regex]::Escape("command=$Mode result=OK")) {
        throw "DirectShell Input integration command failed: $result"
    }
    return [PSCustomObject]@{ Mode = $Mode; RunId = $runId; Diagnostic = $diagnostic; Result = $result }
}

function Get-RemoteProcessMatch {
    param([Parameter(Mandatory = $true)][string]$ProcessName)
    $processes = Invoke-AdbText -Arguments @('shell', 'ps', '-A')
    return (($processes -split "`r?`n") | Select-String -SimpleMatch $ProcessName | Out-String).Trim()
}

function Ensure-TargetForeground {
    $component = "$packageName/.debug.input.InputSessionTargetActivity"
    $foregroundPattern = '(?m)^\s*(?:topResumedActivity|mResumedActivity|ResumedActivity|mCurrentFocus)=.*InputSessionTargetActivity'
    $activities = Invoke-AdbText -Arguments @('shell', 'dumpsys', 'activity', 'activities')
    if ($activities -match $foregroundPattern) { return }

    Invoke-Adb -Arguments @('shell', 'am', 'start', '-n', "$packageName/$targetActivity") | Out-Null
    $deadline = [Environment]::TickCount64 + 7500
    do {
        $activities = Invoke-AdbText -Arguments @('shell', 'dumpsys', 'activity', 'activities')
        if ($activities -match $foregroundPattern) { return }
        Start-Sleep -Milliseconds 150
    } while ([Environment]::TickCount64 -lt $deadline)
    throw "Input target Activity did not become foreground: $component"
}

if (-not $SkipInstall) {
    Invoke-Adb -Arguments @('install', '-r', $apk) | Out-Host
}
Invoke-Adb -Arguments @('push', $artifact, $remoteArtifact) | Out-Host

$results = [System.Collections.Generic.List[object]]::new()
for ($cycle = 1; $cycle -le $Cycles; $cycle += 1) {
    $secretBytes = [byte[]]::new(32)
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $random.GetBytes($secretBytes)
    } finally {
        $random.Dispose()
    }
    $secret = [Convert]::ToBase64String($secretBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    $port = Get-Random -Minimum 37000 -Maximum 47000
    $generation = 960000 + $cycle
    $serverName = "warpnect-directshell-input-session-$cycle-$([Guid]::NewGuid().ToString('N').Substring(0, 8))"
    $remoteLog = "/data/local/tmp/$serverName.log"
    $localLog = Join-Path $reportDirectory "cycle-$($cycle.ToString('00')).log"
    $serverCommand = "CLASSPATH=$remoteArtifact app_process /system/bin --nice-name=$serverName " +
        "$serverMainClass --port $port --secret-base64 $secret --generation $generation " +
        "--idle-timeout-ms 900000 >$remoteLog 2>&1 &"
    $failure = $null
    $armed = $false
    $commands = [System.Collections.Generic.List[object]]::new()

    try {
        Invoke-Adb -Arguments @('logcat', '-c') | Out-Null
        Ensure-TargetForeground
        Invoke-Adb -Arguments @('shell', $serverCommand) | Out-Null
        Wait-ForServerMarker -RemoteLog $remoteLog -Marker 'START result=READY' | Out-Null
        $commands.Add((Invoke-IntegrationCommand -Mode 'arm' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
        $armed = $true
        $commands.Add((Invoke-IntegrationCommand -Mode 'exercise' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
        foreach ($marker in @('kind=KEY_DOWN', 'kind=KEY_UP', 'kind=TOUCH_DOWN', 'kind=TOUCH_MOVE', 'kind=TOUCH_UP', 'kind=POINTER', 'kind=JOYSTICK')) {
            Wait-ForAppMarker -Marker $marker | Out-Null
        }
    } catch {
        $failure = $_.Exception.Message
    } finally {
        if ($armed) {
            try {
                $commands.Add((Invoke-IntegrationCommand -Mode 'disarm' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
            } catch {
                if ($null -eq $failure) { $failure = "disarm=$($_.Exception.Message)" }
            }
        }
    }

    Start-Sleep -Milliseconds 600
    $serverLog = try {
        Invoke-AdbText -Arguments @('shell', 'cat', $remoteLog)
    } catch {
        "SERVER_LOG_UNAVAILABLE $($_.Exception.Message)"
    }
    $appLog = Get-AppLog
    $stillRunning = Get-RemoteProcessMatch -ProcessName $serverName
    Set-Content -LiteralPath $localLog -Value (
        "[server]`n$serverLog`n[commands]`n$($commands | ConvertTo-Json -Depth 4)`n" +
        "[app]`n$appLog`n[failure]`n$failure"
    ) -NoNewline

    $requiredServer = @(
        'START result=READY',
        'uid=2000',
        'uid_kind=SHELL',
        'command=INPUT_SESSION_CAPABILITIES result=INPUT_SESSION_CAPABILITIES status=OK',
        'command=INPUT_SESSION_PREPARE result=INPUT_SESSION_PREPARE status=OK',
        'command=INPUT_SESSION_START result=INPUT_SESSION_START status=OK',
        'command=INPUT_SESSION_KEY result=INPUT_SESSION_KEY status=OK',
        'command=INPUT_SESSION_TOUCH result=INPUT_SESSION_TOUCH status=OK',
        'command=INPUT_SESSION_POINTER result=INPUT_SESSION_POINTER status=OK',
        'command=INPUT_SESSION_JOYSTICK result=INPUT_SESSION_JOYSTICK status=OK',
        'command=INPUT_SESSION_RESET result=INPUT_SESSION_RESET status=OK',
        'command=INPUT_SESSION_STOP result=INPUT_SESSION_STOP status=OK',
        'SHUTDOWN result=REQUESTED reason=authenticated_shutdown',
        'ENDPOINT_RELEASED transport=LOOPBACK_TCP',
        'PROCESS_EXIT code=0'
    )
    $missing = [System.Collections.Generic.List[string]]::new()
    foreach ($marker in $requiredServer) {
        if ($serverLog -notmatch [regex]::Escape($marker)) { $missing.Add("server:$marker") }
    }
    foreach ($marker in @('kind=KEY_DOWN', 'kind=KEY_UP', 'kind=TOUCH_DOWN', 'kind=TOUCH_MOVE', 'kind=TOUCH_UP', 'kind=POINTER', 'kind=JOYSTICK')) {
        if ($appLog -notmatch [regex]::Escape($marker)) { $missing.Add("target:$marker") }
    }
    if ($appLog -notmatch 'command=exercise result=OK') { $missing.Add('normal_process_exercise') }
    if ($appLog -notmatch 'backend=DirectShell shizuku_input_producer=false') { $missing.Add('directshell_source_identity') }
    if ($null -ne $failure) { $missing.Add("harness:$failure") }
    if (-not [string]::IsNullOrWhiteSpace($stillRunning)) { $missing.Add("orphan:$stillRunning") }

    $passed = $missing.Count -eq 0
    $results.Add([PSCustomObject]@{
        Cycle = $cycle
        Passed = $passed
        Missing = @($missing)
        Report = $localLog
        Process = $stillRunning
    })
    if ($passed) {
        Write-Host "DIRECTSHELL_INPUT_SESSION_INTEGRATION_CYCLE=$cycle PASS report=$localLog"
    } else {
        Write-Host "DIRECTSHELL_INPUT_SESSION_INTEGRATION_CYCLE=$cycle FAIL missing=$($missing -join ',') report=$localLog"
    }
}

$summaryPath = Join-Path $reportDirectory 'summary.json'
$results | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $summaryPath -NoNewline
$passedCycles = @($results | Where-Object Passed).Count
if ($passedCycles -eq $Cycles) {
    Write-Host "DIRECTSHELL_INPUT_SESSION_INTEGRATION_RESULT=PASS passes=$passedCycles attempts=$Cycles report=$summaryPath"
    exit 0
}

Write-Host "DIRECTSHELL_INPUT_SESSION_INTEGRATION_RESULT=FAIL passes=$passedCycles attempts=$Cycles report=$summaryPath"
exit 1
