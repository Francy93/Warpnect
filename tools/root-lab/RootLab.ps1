[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Start', 'Inspect', 'Stop')]
    [string]$Action,
    [Parameter(Mandatory = $true)]
    [ValidateSet(25, 26)]
    [int]$Api,
    [string]$SdkPath,
    [int]$Port = 0,
    [ValidateRange(30, 600)]
    [int]$BootTimeoutSeconds = 300
)

$ErrorActionPreference = 'Stop'
if (-not $SdkPath) { $SdkPath = $env:ANDROID_SDK_ROOT }
if (-not $SdkPath) { $SdkPath = $env:ANDROID_HOME }
if (-not $SdkPath) { $SdkPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adb = Join-Path $SdkPath 'platform-tools\adb.exe'
$emulator = Join-Path $SdkPath 'emulator\emulator.exe'
foreach ($tool in @($adb, $emulator)) {
    if (-not (Test-Path -LiteralPath $tool)) { throw "Missing SDK tool: $tool" }
}
if ($Port -eq 0) { $Port = if ($Api -eq 25) { 5554 } else { 5556 } }
if ($Port -lt 5554 -or $Port -gt 5682 -or $Port % 2 -ne 0) {
    throw 'Port must be an even emulator console port between 5554 and 5682.'
}
$serial = "emulator-$Port"
$avd = "Warpnect_Root_API$Api"

function Invoke-LabAdb {
    param([string[]]$AdbArguments)
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo.FileName = $adb
    $process.StartInfo.Arguments = "-s $serial " + ($AdbArguments -join ' ')
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.CreateNoWindow = $true
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    try {
        $null = $process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(15000)) {
            $process.Kill()
            throw "ADB timed out for $serial."
        }
        $output = $stdout.GetAwaiter().GetResult()
        $errorOutput = $stderr.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) { throw "ADB failed for ${serial}: $errorOutput $output" }
        return $output.Trim()
    } finally {
        $process.Dispose()
    }
}

function Assert-LabTarget {
    if ($serial -notmatch '^emulator-\d+$') { throw 'Physical targets are forbidden.' }
    $name = (Invoke-LabAdb @('emu', 'avd', 'name')) -split '\r?\n' | Select-Object -First 1
    if ($name.Trim() -ne $avd) { throw "Wrong AVD at ${serial}: $name (expected $avd)." }
    if ((Invoke-LabAdb @('shell', 'getprop', 'ro.kernel.qemu')) -ne '1') {
        throw 'Target does not identify itself as an emulator.'
    }
    if ((Invoke-LabAdb @('shell', 'getprop', 'ro.build.version.sdk')) -ne "$Api") {
        throw 'Target API does not match the requested lab.'
    }
}

if ($Action -eq 'Start') {
    $names = & $emulator -list-avds
    if ($LASTEXITCODE -ne 0 -or $avd -notin $names) { throw "AVD is missing: $avd" }
    $occupied = Get-NetTCPConnection -LocalPort $Port, ($Port + 1) -State Listen -ErrorAction SilentlyContinue
    if ($occupied) { throw "Emulator port $Port or its ADB port is already in use." }
    $repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
    $logs = Join-Path $repoRoot 'app\build\reports\root-lab'
    New-Item -ItemType Directory -Force -Path $logs | Out-Null
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $arguments = @('-avd', $avd, '-port', $Port, '-no-window', '-no-audio',
        '-no-snapshot-load', '-no-snapshot-save', '-memory', 1536, '-cores', 2,
        '-gpu', 'swiftshader', '-qcow2-for-userdata')
    $started = Start-Process -FilePath $emulator -ArgumentList $arguments -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $logs "$avd-$stamp.out.log") `
        -RedirectStandardError (Join-Path $logs "$avd-$stamp.err.log") -PassThru
    $deadline = [DateTime]::UtcNow.AddSeconds($BootTimeoutSeconds)
    $booted = $false
    do {
        try { $booted = (Invoke-LabAdb @('shell', 'getprop', 'sys.boot_completed')) -eq '1' }
        catch { $booted = $false }
        $startupLog = Get-Content -LiteralPath (Join-Path $logs "$avd-$stamp.out.log") -Raw
        if ($startupLog -match '(?m)^FATAL\s*\|') {
            throw "Emulator startup failed; inspect $logs. No wipe or broad process kill performed."
        }
        if (-not $booted) { Start-Sleep -Seconds 2 }
    } while (-not $booted -and [DateTime]::UtcNow -lt $deadline)
    if (-not $booted) {
        throw "Boot timed out; inspect $logs. Launcher PID $($started.Id); no wipe or broad process kill performed."
    }
    Assert-LabTarget
    Write-Output "$avd -> $serial -> API$Api; cold boot complete. Logs: $logs"
}

if ($Action -eq 'Stop') {
    Assert-LabTarget
    Invoke-LabAdb @('emu', 'kill')
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        $present = $false
        try { $present = (Invoke-LabAdb @('get-state')) -eq 'device' } catch { }
        if ($present) { Start-Sleep -Seconds 1 }
    } while ($present -and [DateTime]::UtcNow -lt $deadline)
    if ($present) { throw "Emulator still present after bounded stop: $serial" }
    Write-Output "$avd stopped; physical devices were not targeted."
}

if ($Action -eq 'Inspect') {
    Assert-LabTarget
    Write-Output "$avd -> $serial -> API$Api"
    foreach ($property in @('ro.build.version.release', 'ro.build.version.sdk',
        'ro.build.fingerprint', 'ro.product.cpu.abi', 'ro.product.cpu.abilist',
        'ro.build.type', 'ro.build.tags', 'ro.debuggable', 'service.adb.root')) {
        Write-Output "$property=$(Invoke-LabAdb @('shell', 'getprop', $property))"
    }
    foreach ($command in @(@('shell', 'id'), @('shell', 'whoami'),
        @('shell', 'getenforce'), @('shell', 'id', '-Z'))) {
        Invoke-LabAdb $command
    }
}
