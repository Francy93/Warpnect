[CmdletBinding()]
param(
    [ValidateSet("Resolution", "MirrorLifecycle", "EncoderFrame", "VideoCapability", "VideoMetadata", "InputCapability", "LegacyLifecycle", "LegacyCompatibility", "LegacyEncoderFrame", "LegacyVbrEncoderFrame", "All")]
    [string]$Probe = "All",
    [string[]]$Serial = @(),
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [switch]$AllowRiskyCodecProbe
)

$ErrorActionPreference = "Stop"

if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw "PowerShell 7 or later is required for the bounded ADB process runner."
}

$script:Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$script:ArtifactRoot = Join-Path $PSScriptRoot "artifacts"
$script:RunDirectory = Join-Path $script:ArtifactRoot ((Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ"))
$script:PackageName = "io.warpnect"
$script:ActivityName = "io.warpnect/.platform.capture.experimental.ExperimentalDisplayMirrorActivity"
$script:LogTag = "WarpnectCaptureExperiment"

function Find-Adb {
    $candidates = @()
    if ($env:ANDROID_HOME) { $candidates += (Join-Path $env:ANDROID_HOME "platform-tools\adb.exe") }
    if ($env:ANDROID_SDK_ROOT) { $candidates += (Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe") }
    $candidates += "C:\Users\Francy\AppData\Local\Android\Sdk\platform-tools\adb.exe"
    $adb = $candidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if ($null -eq $adb) { throw "Android Debug Bridge was not found. Set ANDROID_HOME or ANDROID_SDK_ROOT." }
    return $adb
}

$script:Adb = Find-Adb

if ($Probe -eq "LegacyVbrEncoderFrame" -and -not $AllowRiskyCodecProbe) {
    throw "LegacyVbrEncoderFrame can abort a vendor codec process. Re-run only with -AllowRiskyCodecProbe."
}

function Invoke-AdbText {
    param([string]$DeviceSerial, [string[]]$Arguments, [int]$TimeoutMilliseconds = 10000)
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $script:Adb
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    foreach ($argument in @("-s", $DeviceSerial) + $Arguments) {
        [void]$startInfo.ArgumentList.Add($argument)
    }
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    [void]$process.Start()
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    if (-not $process.WaitForExit($TimeoutMilliseconds)) {
        $process.Kill($true)
        $process.WaitForExit()
        throw "ADB_TIMEOUT on ${DeviceSerial}: $($Arguments -join ' ')"
    }
    $output = (($stdout.GetAwaiter().GetResult()) + ($stderr.GetAwaiter().GetResult())).Trim()
    if ($process.ExitCode -ne 0) {
        throw "ADB_FAILED on ${DeviceSerial}: $($Arguments -join ' ') :: $output"
    }
    return $output
}

function Get-PhysicalDevices {
    $lines = & $script:Adb devices -l
    if ($LASTEXITCODE -ne 0) { throw "adb devices -l failed" }
    $devices = foreach ($line in $lines) {
        if ($line -match "^(\S+)\s+device(?:\s+(.*))?$") {
            $deviceSerial = $Matches[1]
            $attributes = $Matches[2]
            $emulator = $deviceSerial -match "^emulator-" -or $attributes -match "(?i)(emulator|sdk_gphone|goldfish|ranchu)"
            if (-not $emulator) {
                [pscustomobject]@{ serial = $deviceSerial; attributes = $attributes }
            }
        }
    }
    $requested = @($Serial + ($env:WARPNECT_CAPTURE_DEVICES -split ",")) |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        Select-Object -Unique
    if ($requested.Count -gt 0) {
        $available = @{}
        foreach ($device in $devices) { $available[$device.serial] = $device }
        foreach ($requestedSerial in $requested) {
            if (-not $available.ContainsKey($requestedSerial)) {
                throw "Requested device '$requestedSerial' is not a physical adb device in state device."
            }
        }
        return @($requested | ForEach-Object { $available[$_] })
    }
    return @($devices | Sort-Object serial)
}

function Get-DeviceInfo {
    param([pscustomobject]$Device)
    return [pscustomobject]@{
        serial = $Device.serial
        manufacturer = Get-DeviceProperty $Device.serial "ro.product.manufacturer"
        model = Get-DeviceProperty $Device.serial "ro.product.model"
        android_release = Get-DeviceProperty $Device.serial "ro.build.version.release"
        api_level = Get-DeviceProperty $Device.serial "ro.build.version.sdk"
        abi = Get-DeviceProperty $Device.serial "ro.product.cpu.abi"
        connection = "adb"
    }
}

function Get-DeviceProperty {
    param([string]$DeviceSerial, [string]$Name)
    return Invoke-AdbText $DeviceSerial @("shell", "getprop", $Name)
}

function Write-ArtifactJson {
    param([string]$RelativePath, [object]$Value)
    $path = Join-Path $script:RunDirectory $RelativePath
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $path) | Out-Null
    [System.IO.File]::WriteAllText(
        $path,
        ($Value | ConvertTo-Json -Depth 6),
        [System.Text.UTF8Encoding]::new($false)
    )
}

function Get-ApkInfo {
    $apk = Join-Path $script:Root "app\build\outputs\apk\debug\app-debug.apk"
    if (-not (Test-Path -LiteralPath $apk)) { throw "Expected debug APK does not exist: $apk" }
    $item = Get-Item -LiteralPath $apk
    return [pscustomobject]@{
        path = (Resolve-Path $apk).Path
        bytes = $item.Length
        sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $apk).Hash
        last_modified_utc = $item.LastWriteTimeUtc.ToString("o")
    }
}

function Install-Apk {
    param([pscustomobject]$Device, [string]$Apk)
    $output = Invoke-AdbText $Device.serial @("install", "-r", $Apk) 120000
    if ($output -notmatch "Success") { throw "INSTALL_FAILED on $($Device.serial): $output" }
    $packagePath = Invoke-AdbText $Device.serial @("shell", "pm", "path", $script:PackageName)
    if ($packagePath -notmatch "package:") { throw "Warpnect package is missing on $($Device.serial)." }
}

function Get-ProbeCode {
    param([string]$ProbeName)
    switch ($ProbeName) {
        "Resolution" { return 1 }
        "MirrorLifecycle" { return 2 }
        "EncoderFrame" { return 3 }
        "VideoCapability" { return 4 }
        "InputCapability" { return 5 }
        "LegacyLifecycle" { return 6 }
        "LegacyEncoderFrame" { return 7 }
        "VideoMetadata" { return 8 }
        "LegacyCompatibility" { return 9 }
        "LegacyVbrEncoderFrame" { return 10 }
        default { throw "Unsupported probe '$ProbeName'." }
    }
}

function Convert-ExperimentLogLine {
    param([string]$Line)
    $result = [ordered]@{}
    foreach ($match in [regex]::Matches($Line, "(?<key>[a-z0-9_]+)=(?<value>[^\s]+)")) {
        $result[$match.Groups["key"].Value] = $match.Groups["value"].Value
    }
    return [pscustomobject]$result
}

function Convert-ExperimentResultFile {
    param([string]$Text)
    $result = [ordered]@{}
    foreach ($line in $Text -split "`r?`n") {
        if ($line -match "^(?<key>[a-z0-9_]+)=(?<value>.*)$") {
            $result[$Matches["key"]] = $Matches["value"]
        }
    }
    return [pscustomobject]$result
}

function Invoke-Probe {
    param([pscustomobject]$Device, [string]$ProbeName, [int]$Sequence)
    $runId = "capture$Sequence$([DateTime]::UtcNow.ToString('HHmmssfff'))"
    $probeCode = Get-ProbeCode $ProbeName
    Invoke-AdbText $Device.serial @(
        "shell", "am", "start", "-n", $script:ActivityName,
        "--es", "io.warpnect.capture.experiment.RUN_ID", $runId,
        "--ei", "io.warpnect.capture.experiment.PROBE_KIND", "$probeCode"
    ) | Out-Null
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    do {
        $resultPath = "cache/capture-experiment/$runId.txt"
        $text = try {
            Invoke-AdbText $Device.serial @("shell", "run-as", $script:PackageName, "cat", $resultPath) 2000
        } catch {
            $null
        }
        if ($text -match "event=capture_experiment_result") {
            return Convert-ExperimentResultFile $text
        }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    return [pscustomobject]@{
        event = "capture_experiment_timeout"
        run = $runId
        probe = $ProbeName
        failure = "ResultTimeout"
    }
}

New-Item -ItemType Directory -Force -Path $script:RunDirectory | Out-Null
$devices = @(Get-PhysicalDevices)
if ($devices.Count -eq 0) {
    Write-Output "NO_PHYSICAL_ANDROID_DEVICES_AVAILABLE"
    exit 2
}

if (-not $SkipBuild) {
    Push-Location $script:Root
    try {
        & .\gradlew.bat --no-daemon --max-workers=2 assembleDebug
        if ($LASTEXITCODE -ne 0) { throw "assembleDebug failed" }
    } finally {
        Pop-Location
    }
}

$apk = Get-ApkInfo
$deviceInfo = @($devices | ForEach-Object { Get-DeviceInfo $_ })
Write-ArtifactJson "apk-info.json" $apk
Write-ArtifactJson "device-info.json" $deviceInfo

foreach ($device in $devices) {
    if (-not $SkipInstall) { Install-Apk $device $apk.path }
}

$probeNames = if ($Probe -eq "All") {
    @("VideoMetadata", "InputCapability", "LegacyLifecycle", "Resolution", "MirrorLifecycle")
} else {
    @($Probe)
}
$results = @()
$sequence = 1
foreach ($device in $devices) {
    foreach ($probeName in $probeNames) {
        $result = Invoke-Probe $device $probeName $sequence
        $results += [pscustomobject]@{
            serial = $device.serial
            probe = $probeName
            result = $result
        }
        $sequence += 1
    }
}

Write-ArtifactJson "results.json" $results
$results | ConvertTo-Json -Depth 6
if ($results.result.failure -contains "ResultTimeout") { exit 1 }
