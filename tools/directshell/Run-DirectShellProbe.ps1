[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [string]$AdbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [int]$Port = 0,
    [int]$Cycles = 20,
    [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'

if ($Cycles -lt 1) { throw 'Cycles must be positive.' }
if ($Port -ne 0 -and ($Port -lt 1024 -or $Port -gt 65535)) {
    throw 'Port must be zero or between 1024 and 65535.'
}
if (-not (Test-Path -LiteralPath $AdbPath)) { throw "ADB was not found: $AdbPath" }

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$apk = Join-Path $repoRoot 'app\build\outputs\apk\debug\app-debug.apk'
$artifact = Join-Path $repoRoot 'app\build\outputs\directshell-probe\warpnect-directshell-probe.jar'
$localReportDirectory = Join-Path $repoRoot 'app\build\reports\directshell-probe'
$remoteArtifact = '/data/local/tmp/warpnect-directshell-probe.jar'
$packageName = 'io.warpnect'
$mainClass = 'io.warpnect.debug.directshell.WarpnectPrivilegedServer'
$activity = 'io.warpnect.debug.directshell.DirectShellProbeActivity'

if (-not (Test-Path -LiteralPath $apk)) { throw "Debug APK is missing: $apk" }
if (-not (Test-Path -LiteralPath $artifact)) { throw "DEX probe artifact is missing: $artifact" }

New-Item -ItemType Directory -Force -Path $localReportDirectory | Out-Null

function Invoke-Adb {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    & $AdbPath -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB command failed: $($Arguments -join ' ')" }
}

function Get-RemoteProcessMatch {
    param([Parameter(Mandatory = $true)][string]$ProcessName)
    $allProcesses = Invoke-Adb -Arguments @('shell', 'ps', '-A')
    return (($allProcesses | Select-String -SimpleMatch $ProcessName) -join "`n").Trim()
}

function Wait-ForClientMarker {
    param(
        [Parameter(Mandatory = $true)][string]$Marker,
        [int]$TimeoutMilliseconds = 7500
    )
    $deadline = [Environment]::TickCount64 + $TimeoutMilliseconds
    do {
        $logs = Invoke-Adb -Arguments @('logcat', '-d', '-v', 'brief', 'WarpnectDirectShell:I', '*:S')
        $match = $logs | Select-String -SimpleMatch $Marker | Select-Object -Last 1
        if ($null -ne $match) { return $match.ToString() }
        Start-Sleep -Milliseconds 150
    } while ([Environment]::TickCount64 -lt $deadline)
    throw "Timed out waiting for client marker: $Marker"
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
        Start-Sleep -Milliseconds 150
    } while ([Environment]::TickCount64 -lt $deadline)
    throw "Timed out waiting for server marker: $Marker"
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
    $port = if ($Port -eq 0) { Get-Random -Minimum 36000 -Maximum 46000 } else { $Port }
    $serverName = "warpnect-directshell-$cycle"
    $negativeRunId = "negative-$cycle-$([Guid]::NewGuid().ToString('N'))"
    $positiveRunId = "authenticated-$cycle-$([Guid]::NewGuid().ToString('N'))"
    $remoteLog = "/data/local/tmp/$serverName.log"
    $localLog = Join-Path $localReportDirectory "$serverName.log"
    $serverCommand = "CLASSPATH=$remoteArtifact app_process /system/bin --nice-name=$serverName $mainClass --port $port --secret-base64 $secret --declared-package $packageName --idle-timeout-ms 30000 >$remoteLog 2>&1 &"

    Invoke-Adb -Arguments @('shell', $serverCommand) | Out-Null
    $serverReady = $false
    try {
        Wait-ForServerMarker -RemoteLog $remoteLog -Marker 'START result=READY' | Out-Null
        $serverReady = $true
    } catch {
        $serverStartupFailure = $_.Exception.Message
    }

    # This deliberately opens a control connection with a different HMAC key. It must not
    # dispatch a privileged command and must not terminate the listener.
    $negativeClientLog = ''
    $positiveClientLog = ''
    $negativeClientCompleted = $false
    $positiveClientCompleted = $false
    if ($serverReady) {
        Invoke-Adb -Arguments @(
            'shell', 'am', 'start', '-W', '-n', "$packageName/$activity",
            '--ei', 'port', "$port",
            '--es', 'secretBase64', $secret,
            '--es', 'mode', 'unauthenticated',
            '--es', 'runId', $negativeRunId,
            '--ez', 'requestShutdown', 'false'
        ) | Out-Null
        try {
            $negativeClientLog = Wait-ForClientMarker -Marker "run_id=$negativeRunId DIRECT_SHELL_CLIENT_NEGATIVE_AUTH result=REJECTED"
            $negativeClientCompleted = $true
        } catch {
            $negativeClientLog = $_.Exception.Message
        }
    } else {
        $negativeClientLog = $serverStartupFailure
    }

    if ($negativeClientCompleted) {
        Invoke-Adb -Arguments @(
            'shell', 'am', 'start', '-W', '-n', "$packageName/$activity",
            '--ei', 'port', "$port",
            '--es', 'secretBase64', $secret,
            '--es', 'mode', 'authenticated',
            '--es', 'runId', $positiveRunId,
            '--ez', 'requestShutdown', 'true'
        ) | Out-Null
        try {
            $positiveClientLog = Wait-ForClientMarker -Marker "run_id=$positiveRunId DIRECT_SHELL_CLIENT_RESULT result=SUCCESS"
            $positiveClientCompleted = $true
        } catch {
            $positiveClientLog = $_.Exception.Message
        }
    }
    Start-Sleep -Milliseconds 400

    $serverLog = (Invoke-Adb -Arguments @('shell', 'cat', $remoteLog)) -join "`n"
    Set-Content -LiteralPath $localLog -Value "[server]`n$serverLog`n[negative-client]`n$negativeClientLog`n[authenticated-client]`n$positiveClientLog" -NoNewline
    $stillRunning = Get-RemoteProcessMatch -ProcessName $serverName
    $required = @(
        'START result=READY',
        'uid=2000',
        'uid_kind=SHELL',
        'transport=LOOPBACK_TCP',
        'AUTH result=UNAUTHENTICATED',
        'command=PING result=OK',
        'command=GET_RUNTIME_INFO result=OK',
        'command=SHUTDOWN result=OK',
        'SHUTDOWN result=REQUESTED reason=authenticated_shutdown',
        'ENDPOINT_RELEASED transport=LOOPBACK_TCP',
        'PROCESS_EXIT code=0'
    )
    $missing = @($required | Where-Object { $serverLog -notmatch [regex]::Escape($_) })
    if (-not $serverReady) { $missing += 'server_startup_completion' }
    if (-not $negativeClientCompleted) { $missing += 'negative_client_completion' }
    if (-not $positiveClientCompleted) { $missing += 'authenticated_client_completion' }

    if ($missing.Count -eq 0 -and [string]::IsNullOrWhiteSpace($stillRunning)) {
        $passed++
        Write-Host "DIRECTSHELL_CYCLE_$cycle=PASS"
    } else {
        $failed++
        Write-Host "DIRECTSHELL_CYCLE_$cycle=FAIL missing=$($missing -join ',') process=$stillRunning"
        # A failed server is allowed its bounded idle timeout; this harness never kills it as
        # recovery because that would hide a lifecycle ownership defect.
        Start-Sleep -Milliseconds 30500
        $afterTimeout = Get-RemoteProcessMatch -ProcessName $serverName
        if (-not [string]::IsNullOrWhiteSpace($afterTimeout)) {
            Write-Host "DIRECTSHELL_CYCLE_$cycle=ORPHAN process=$afterTimeout"
        }
    }
}

Write-Host "DIRECTSHELL_SOAK_RESULT pass=$passed fail=$failed total=$Cycles reports=$localReportDirectory"
if ($failed -ne 0) { exit 1 }
