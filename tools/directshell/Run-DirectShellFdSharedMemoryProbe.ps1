[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [string]$AdbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [int]$Cycles = 20,
    [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'

if ($Cycles -lt 1 -or $Cycles -gt 50) { throw 'Cycles must be between 1 and 50.' }
if (-not (Test-Path -LiteralPath $AdbPath)) { throw "ADB was not found: $AdbPath" }

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$apk = Join-Path $repoRoot 'app\build\outputs\apk\debug\app-debug.apk'
$artifact = Join-Path $repoRoot 'app\build\outputs\directshell-probe\warpnect-directshell-probe.jar'
$reportDirectory = Join-Path $repoRoot 'app\build\reports\directshell-fd-sharedmemory-probe'
$remoteArtifact = '/data/local/tmp/warpnect-directshell-probe.jar'
$packageName = 'io.warpnect'
$serverMainClass = 'io.warpnect.debug.directshell.WarpnectPrivilegedFdSharedMemoryProbeServer'
$controllerActivity = 'io.warpnect.debug.directshell.DirectShellFdProbeActivity'

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
    throw "Timed out waiting for server marker: $Marker"
}

function Wait-ForLogMarker {
    param(
        [Parameter(Mandatory = $true)][string]$Marker,
        [int]$TimeoutMilliseconds = 8000
    )
    $deadline = [Environment]::TickCount64 + $TimeoutMilliseconds
    do {
        $logs = Invoke-Adb -Arguments @('logcat', '-d', '-v', 'brief', 'WarpnectDirectShellFd:I', '*:S')
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
        'shell', 'am', 'start', '-W', '-n', "$packageName/$controllerActivity",
        '--ei', 'port', "$Port",
        '--es', 'secretBase64', $Secret,
        '--el', 'generationId', "$Generation",
        '--es', 'mode', $Mode,
        '--es', 'runId', $runId
    ) | Out-Null
    $result = Wait-ForLogMarker -Marker "run_id=$runId DIRECT_SHELL_FD_CLIENT_RESULT"
    if ($result -notmatch [regex]::Escape("command=$Mode result=OK")) {
        throw "FD probe app command failed: $result"
    }
    Start-Sleep -Milliseconds 350
    return $result
}

if (-not $SkipInstall) {
    Invoke-Adb -Arguments @('install', '-r', $apk) | Out-Host
}
Invoke-Adb -Arguments @('push', $artifact, $remoteArtifact) | Out-Host

$passed = 0
$failed = 0
$appFinishFdCeiling = $null

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
    $generation = [long]100000 + $cycle
    $serverName = "warpnect-directshell-fd-$cycle"
    $remoteLog = "/data/local/tmp/$serverName.log"
    $localLog = Join-Path $reportDirectory "$serverName.log"
    $serverCommand = "CLASSPATH=$remoteArtifact app_process /system/bin --nice-name=$serverName $serverMainClass --port $port --secret-base64 $secret --generation $generation --idle-timeout-ms 45000 >$remoteLog 2>&1 &"
    $cycleError = $null
    $events = [System.Collections.Generic.List[string]]::new()
    $appArmed = $false
    $appFinished = $false
    $serverShutdown = $false

    Invoke-Adb -Arguments @('logcat', '-c') | Out-Null
    Invoke-Adb -Arguments @('shell', $serverCommand) | Out-Null
    try {
        Wait-ForRemoteLog -RemoteLog $remoteLog -Marker 'START result=READY' | Out-Null
        $events.Add((Invoke-AppMode -Mode 'arm' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
        $appArmed = $true
        $events.Add((Invoke-AppMode -Mode 'negative-normal-caller' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
        $events.Add((Invoke-AppMode -Mode 'server-bind' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
        $events.Add((Invoke-AppMode -Mode 'server-verify-a' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
        $events.Add((Invoke-AppMode -Mode 'server-negative-stale' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
        $events.Add((Invoke-AppMode -Mode 'server-negative-magic' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
        $events.Add((Invoke-AppMode -Mode 'server-negative-size' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
        $events.Add((Invoke-AppMode -Mode 'server-negative-replay' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
        $events.Add((Invoke-AppMode -Mode 'server-negative-proof' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
        $events.Add((Invoke-AppMode -Mode 'close-original' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
        $events.Add((Invoke-AppMode -Mode 'server-write-b' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
        $events.Add((Invoke-AppMode -Mode 'verify-b' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
        $events.Add((Invoke-AppMode -Mode 'write-c' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
        $events.Add((Invoke-AppMode -Mode 'server-verify-c' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
        $events.Add((Invoke-AppMode -Mode 'server-close' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
        $events.Add((Invoke-AppMode -Mode 'finish' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
        $appFinished = $true
        $events.Add((Invoke-AppMode -Mode 'shutdown' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
        $serverShutdown = $true
    } catch {
        $cycleError = $_.Exception.Message
    } finally {
        if ($appArmed -and -not $appFinished) {
            try {
                $events.Add((Invoke-AppMode -Mode 'finish' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
                $appFinished = $true
            } catch {
                $events.Add("cleanup_finish=$($_.Exception.Message)")
            }
        }
        if (-not $serverShutdown) {
            try {
                $events.Add((Invoke-AppMode -Mode 'shutdown' -Port $port -Secret $secret -Generation $generation -Cycle $cycle))
                $serverShutdown = $true
            } catch {
                $events.Add("cleanup_shutdown=$($_.Exception.Message)")
            }
        }
    }

    Start-Sleep -Milliseconds 450
    $serverLog = (Invoke-Adb -Arguments @('shell', 'cat', $remoteLog)) -join "`n"
    $appLog = (Invoke-Adb -Arguments @('logcat', '-d', '-v', 'brief', 'WarpnectDirectShellFd:I', '*:S')) -join "`n"
    $stillRunning = Get-RemoteProcessMatch -ProcessName $serverName
    $requiredServer = @(
        'START result=READY',
        'uid=2000',
        'uid_kind=SHELL',
        'framework_context=AVAILABLE',
        'FD_HANDOFF_OK transport=CONTENT_PROVIDER_EXTERNAL reply_has_fd=true',
        'command=FD_VERIFY_APP_A result=OK',
        'command=FD_WRITE_SHELL_B result=OK',
        'command=FD_VERIFY_APP_C result=OK',
        'command=FD_REQUEST_STALE_GENERATION result=OK',
        'command=FD_REQUEST_INVALID_MAGIC result=OK',
        'command=FD_REQUEST_OVERSIZED_METADATA result=OK',
        'command=FD_REQUEST_REPLAY result=OK',
        'command=FD_REQUEST_INVALID_PROOF result=OK',
        'command=FD_CLOSE_SHARED_MEMORY result=OK',
        'command=SHUTDOWN result=OK',
        'ENDPOINT_RELEASED transport=LOOPBACK_TCP',
        'PROCESS_EXIT code=0'
    )
    $requiredApp = @(
        'BRIDGE_REJECT transport=provider result=CALLER_UID_REJECTED',
        'BRIDGE_DESCRIPTOR_ISSUED',
        'caller_uid=2000',
        'APP_CLOSE_ORIGINAL',
        'APP_VERIFY_B',
        'APP_WRITE_C',
        'APP_FINISH'
    )
    $missing = @($requiredServer | Where-Object { $serverLog -notmatch [regex]::Escape($_) })
    $missing += @($requiredApp | Where-Object { $appLog -notmatch [regex]::Escape($_) })
    if ($null -ne $cycleError) { $missing += "client=$cycleError" }
    $appArm = [regex]::Match($appLog, "APP_ARM generation=$generation .*?app_fd_before_arm=(\d+)")
    $appFinish = [regex]::Match($appLog, "APP_FINISH generation=$generation fd_before_release=(\d+) fd_after_release=(\d+)")
    $serverHandoff = [regex]::Match($serverLog, "FD_HANDOFF_OK .*?fd_before_bind=(\d+) fd_after_handoff=(\d+)")
    $serverClose = [regex]::Match($serverLog, "REMOTE_CLOSED fd_after_remote_close=(\d+) provider_release=EXTERNAL_PROVIDER_RELEASED")
    $resourceSummary = 'unavailable'
    if (-not $appArm.Success -or -not $appFinish.Success -or -not $serverHandoff.Success -or -not $serverClose.Success) {
        $missing += 'resource_metrics_missing'
    } else {
        $armFd = [int]$appArm.Groups[1].Value
        $finishFd = [int]$appFinish.Groups[2].Value
        $shellBeforeFd = [int]$serverHandoff.Groups[1].Value
        $shellAfterFd = [int]$serverHandoff.Groups[2].Value
        $shellClosedFd = [int]$serverClose.Groups[1].Value
        $resourceSummary = "app_arm=$armFd app_finish=$finishFd shell_before=$shellBeforeFd shell_after=$shellAfterFd shell_closed=$shellClosedFd"
        if ($shellAfterFd -ne ($shellBeforeFd + 1) -or $shellClosedFd -ne $shellBeforeFd) {
            $missing += "shell_fd_lifecycle=$resourceSummary"
        }
        # Activity launch may release framework-owned descriptors after the first probe. A lower
        # quiescent count is convergence, while any later increase is observable leak evidence.
        if ($null -eq $appFinishFdCeiling) {
            $appFinishFdCeiling = $finishFd
        } elseif ($finishFd -gt $appFinishFdCeiling) {
            $missing += "app_fd_growth=$resourceSummary finish_ceiling=$appFinishFdCeiling"
        } elseif ($finishFd -lt $appFinishFdCeiling) {
            $appFinishFdCeiling = $finishFd
        }
    }
    Set-Content -LiteralPath $localLog -Value (
        "[server]`n$serverLog`n[events]`n$($events -join "`n")`n[app]`n$appLog`n[resources]`n$resourceSummary`n[error]`n$cycleError"
    ) -NoNewline

    if ($missing.Count -eq 0 -and [string]::IsNullOrWhiteSpace($stillRunning)) {
        $passed++
        Write-Host "DIRECTSHELL_FD_SHARED_MEMORY_CYCLE_$cycle=PASS generation=$generation"
    } else {
        $failed++
        Write-Host "DIRECTSHELL_FD_SHARED_MEMORY_CYCLE_$cycle=FAIL missing=$($missing -join ',') process=$stillRunning"
        if (-not [string]::IsNullOrWhiteSpace($stillRunning)) {
            Write-Host "DIRECTSHELL_FD_SHARED_MEMORY_CYCLE_$cycle=ORPHAN process=$stillRunning"
        }
    }
}

Write-Host "DIRECTSHELL_FD_SHARED_MEMORY_SOAK_RESULT pass=$passed fail=$failed total=$Cycles app_finish_fd_ceiling=$appFinishFdCeiling reports=$reportDirectory"
if ($failed -ne 0) { exit 1 }
