[CmdletBinding()]
param(
    [ValidateSet(
        "PairAccept",
        "ConfirmClientThenHost",
        "ConfirmHostThenClient",
        "ConfirmNearSimultaneous",
        "ClientReject",
        "HostReject",
        "RetryAfterClientReject",
        "RetryAfterHostReject",
        "RetryAfterPairingFailed",
        "RepeatedConnect",
        "RepeatedConfirm",
        "DisconnectDuringSas",
        "KillClientDuringSas",
        "KillHostDuringSas",
        "RoleReversal",
        "MediaStartupTrace"
        ,"PairAcceptCleanState"
    )]
    [string]$Scenario = "PairAccept",
    [string]$DeviceA = $env:WARPNECT_DEVICE_A,
    [string]$DeviceB = $env:WARPNECT_DEVICE_B,
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [switch]$CleanState
)

$ErrorActionPreference = "Stop"

$script:Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$script:ArtifactRoot = Join-Path $PSScriptRoot "artifacts"
$script:RunDirectory = Join-Path $script:ArtifactRoot ((Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ"))
$script:PackageName = "io.warpnect"
$script:ActivityName = "io.warpnect/.MainActivity"

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

function Invoke-Adb {
    param([string]$Serial, [string[]]$Arguments)
    & $script:Adb -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "adb failed for ${Serial}: $($Arguments -join ' ')" }
}

function Invoke-AdbText {
    param([string]$Serial, [string[]]$Arguments)
    return (& $script:Adb -s $Serial @Arguments 2>&1 | Out-String).Trim()
}

function Invoke-AdbTextBounded {
    param([string]$Serial, [string[]]$Arguments, [int]$TimeoutMilliseconds = 5000)
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $script:Adb
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    foreach ($argument in @("-s", $Serial) + $Arguments) {
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
        throw "ADB_TIMEOUT on ${Serial}: $($Arguments -join ' ')"
    }
    $output = (($stdout.GetAwaiter().GetResult()) + ($stderr.GetAwaiter().GetResult())).Trim()
    if ($process.ExitCode -ne 0) { throw "ADB_FAILED on ${Serial}: $($Arguments -join ' ')" }
    return $output
}

function Get-UsableDeviceRecords {
    $lines = & $script:Adb devices -l
    if ($LASTEXITCODE -ne 0) { throw "adb devices -l failed" }
    $records = foreach ($line in $lines) {
        if ($line -match "^(\S+)\s+device(?:\s+(.*))?$") {
            $serial = $Matches[1]
            $attributes = $Matches[2]
            $isEmulator = $serial -match "^emulator-" -or $attributes -match "(?i)(emulator|sdk_gphone|goldfish|ranchu)"
            [pscustomobject]@{
                Serial = $serial
                Attributes = $attributes
                IsEmulator = $isEmulator
            }
        }
    }
    return @($records)
}

function Select-HardwarePair {
    $usable = Get-UsableDeviceRecords
    $bySerial = @{}
    foreach ($device in $usable) { $bySerial[($device.Serial)] = $device }

    $selected = @()
    foreach ($override in @($DeviceA, $DeviceB)) {
        if ([string]::IsNullOrWhiteSpace($override)) { continue }
        if (-not $bySerial.ContainsKey($override)) {
            throw "Requested device '$override' is not currently in adb state device."
        }
        if ($selected.Serial -notcontains $override) { $selected += $bySerial[$override] }
    }

    $candidates = @($usable | Where-Object { -not $_.IsEmulator } | Sort-Object Serial)
    foreach ($candidate in $candidates) {
        if ($selected.Count -ge 2) { break }
        if ($selected.Serial -notcontains $candidate.Serial) { $selected += $candidate }
    }

    if ($selected.Count -lt 2) {
        Write-Output "TWO_REAL_DEVICES_REQUIRED"
        exit 2
    }
    return @($selected | Select-Object -First 2 | ForEach-Object { [string]$_.Serial })
}

function Get-DeviceInfo {
    param([pscustomobject]$Device)
    $serial = $Device.Serial
    $property = {
        param([string]$name)
        Invoke-AdbText $serial @("shell", "getprop", $name)
    }
    $wifi = Invoke-AdbText $serial @("shell", "cmd", "wifi", "status")
    $sdkText = & $property "ro.build.version.sdk"
    $sdk = 0
    [void][int]::TryParse($sdkText, [ref]$sdk)
    $nearby = if ($sdk -ge 33) {
        Invoke-AdbText $serial @("shell", "cmd", "package", "check-permission", "android.permission.NEARBY_WIFI_DEVICES", $script:PackageName, "0")
    } else {
        "not_applicable_api_$sdk"
    }
    $packageDump = Invoke-AdbText $serial @("shell", "dumpsys", "package", $script:PackageName)
    $versionName = [regex]::Match($packageDump, "(?m)^\s*versionName=(.+)$").Groups[1].Value.Trim()
    $versionCode = [regex]::Match($packageDump, "(?m)^\s*versionCode=(\d+)").Groups[1].Value
    return [pscustomobject]@{
        serial = $serial
        manufacturer = & $property "ro.product.manufacturer"
        model = & $property "ro.product.model"
        android_release = & $property "ro.build.version.release"
        api_level = $sdk
        abi = & $property "ro.product.cpu.abi"
        connection = "adb"
        wifi_enabled = $wifi -match "Wifi is enabled"
        location_mode = Invoke-AdbText $serial @("shell", "settings", "get", "secure", "location_mode")
        nearby_wifi_devices = $nearby
        package_installed = $packageDump -match [regex]::Escape($script:PackageName)
        package_version_name = $versionName
        package_version_code = $versionCode
    }
}

function Write-RunText {
    param([string]$RelativePath, [string]$Text)
    $path = Join-Path $script:RunDirectory $RelativePath
    $parent = Split-Path -Parent $path
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
    [System.IO.File]::WriteAllText($path, $Text, [System.Text.UTF8Encoding]::new($false))
}

function Redact-SensitiveText {
    param([string]$Text)
    return [regex]::Replace($Text, "\b\d{3}\s?\d{3}\b", "[REDACTED_SAS]")
}

function Install-CurrentApk {
    param([pscustomobject]$Device, [string]$Apk, [string]$OutputPath)
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $script:Adb
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    foreach ($argument in @("-s", $Device.Serial, "install", "-r", $Apk)) {
        [void]$startInfo.ArgumentList.Add($argument)
    }
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    [void]$process.Start()
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    if (-not $process.WaitForExit(120000)) {
        $process.Kill($true)
        $process.WaitForExit()
        Write-RunText $OutputPath ((($stdout.GetAwaiter().GetResult()) + ($stderr.GetAwaiter().GetResult())).Trim())
        throw "INSTALL_TIMEOUT on $($Device.Serial). The harness did not uninstall or clear Warpnect."
    }
    $output = (($stdout.GetAwaiter().GetResult()) + ($stderr.GetAwaiter().GetResult())).Trim()
    Write-RunText $OutputPath $output
    if ($process.ExitCode -ne 0 -or $output -notmatch "Success") {
        throw "INSTALL_FAILED on $($Device.Serial). The harness will not uninstall or clear Warpnect. See install artifact."
    }
}

function Build-And-Install {
    param([pscustomobject[]]$Devices)
    if (-not $SkipBuild) {
        Push-Location $script:Root
        try {
            & .\gradlew.bat --no-daemon --max-workers=2 assembleDebug 2>&1 |
                Tee-Object -FilePath (Join-Path $script:RunDirectory "build.log")
            if ($LASTEXITCODE -ne 0) { throw "assembleDebug failed" }
        } finally {
            Pop-Location
        }
    }

    $apk = Join-Path $script:Root "app\build\outputs\apk\debug\app-debug.apk"
    if (-not (Test-Path -LiteralPath $apk)) { throw "Expected debug APK does not exist: $apk" }
    $item = Get-Item -LiteralPath $apk
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        $stream = [System.IO.File]::OpenRead($apk)
        try {
            $hash = ([System.BitConverter]::ToString($sha256.ComputeHash($stream))).Replace("-", "")
        } finally {
            $stream.Dispose()
        }
    } finally {
        $sha256.Dispose()
    }
    $apkInfo = [pscustomobject]@{
        path = (Resolve-Path $apk).Path
        bytes = $item.Length
        sha256 = $hash
        last_modified_utc = $item.LastWriteTimeUtc.ToString("o")
    }
    Write-RunText "apk-info.json" ($apkInfo | ConvertTo-Json)

    foreach ($device in $Devices) {
        if (-not $SkipInstall) {
            Invoke-Adb ($device.Serial) @("shell", "am", "force-stop", $script:PackageName)
            Install-CurrentApk $device $apk ("install-{0}.txt" -f $device.Serial)
        }
        $packagePath = Invoke-AdbText ($device.Serial) @("shell", "pm", "path", $script:PackageName)
        if ($packagePath -notmatch "package:") { throw "Warpnect package is missing on $($device.Serial)." }
    }
    return $apkInfo
}

function Ensure-DiscoveryPermission {
    param([pscustomobject]$Device)
    $apiLevel = [int](Invoke-AdbText $Device.Serial @("shell", "getprop", "ro.build.version.sdk"))
    $permission = if ($apiLevel -ge 33) {
        "android.permission.NEARBY_WIFI_DEVICES"
    } else {
        "android.permission.ACCESS_FINE_LOCATION"
    }
    $status = Invoke-AdbText $Device.Serial @("shell", "cmd", "package", "check-permission", $permission, $script:PackageName, "0")
    if ($status -ne "granted") {
        Invoke-Adb $Device.Serial @("shell", "pm", "grant", $script:PackageName, $permission)
    }
}

function Start-Warpnect {
    param([pscustomobject]$Device, [switch]$ClearState)
    $serial = $Device.Serial
    Invoke-Adb $serial @("logcat", "-c")
    if ($ClearState) {
        Invoke-Adb $serial @("shell", "pm", "clear", $script:PackageName)
    }
    Ensure-DiscoveryPermission $Device
    Invoke-Adb $serial @("shell", "am", "force-stop", $script:PackageName)
    Invoke-Adb $serial @("shell", "am", "start", "-n", $script:ActivityName)
    if (-not (Wait-ForWarpnectForeground $Device)) {
        throw "DEVICE_LOCKED_OR_NOT_FOREGROUND on $($Device.Serial). Unlock the device and relaunch Warpnect; the harness will not bypass device security."
    }
}

function Get-UiDocument {
    param([pscustomobject]$Device)
    $remote = "/sdcard/warpnect-h1-window.xml"
    $lastFailure = "unknown"
    for ($attempt = 1; $attempt -le 4; $attempt++) {
        try {
            $dumpOutput = Invoke-AdbTextBounded ($Device.Serial) @("shell", "uiautomator", "dump", $remote)
            $xmlText = Invoke-AdbTextBounded ($Device.Serial) @("exec-out", "cat", $remote)
        } catch {
            $lastFailure = "adb_timeout_or_failure"
            Start-Sleep -Milliseconds 250
            continue
        }
        if ($dumpOutput -match "null root node") {
            $lastFailure = "null_root"
            Start-Sleep -Milliseconds 250
            continue
        }
        if ([string]::IsNullOrWhiteSpace($xmlText) -or $xmlText -notmatch "<hierarchy") {
            $lastFailure = "empty_or_invalid_xml"
            Start-Sleep -Milliseconds 250
            continue
        }
        try {
            [xml]$xml = $xmlText
            if ($null -ne $xml.hierarchy) { return $xml }
            $lastFailure = "missing_hierarchy"
        } catch {
            $lastFailure = "malformed_xml"
        }
        Start-Sleep -Milliseconds 250
    }
    throw "UIAUTOMATOR_DUMP_UNAVAILABLE on $($Device.Serial): $lastFailure"
}

function Get-UiNodes {
    param([xml]$Document)
    return @($Document.SelectNodes("//node"))
}

function Find-UiNode {
    param([pscustomobject]$Device, [string]$Text)
    $document = Get-UiDocument $Device
    return Get-UiNodes $document | Where-Object {
        $_.text -eq $Text -or $_.'content-desc' -eq $Text
    } | Select-Object -First 1
}

function Get-SasFromUi {
    param([pscustomobject]$Device)
    $document = Get-UiDocument $Device
    return Get-SasFromDocument $document
}

function Get-SasFromDocument {
    param([xml]$Document)
    $candidates = @(Get-UiNodes $Document |
        ForEach-Object { $_.text } |
        Where-Object { $_ -match "^\d{3}\s?\d{3}$" } |
        ForEach-Object { $_ -replace "\s", "" } |
        Select-Object -Unique)
    if ($candidates.Count -ne 1) { return $null }
    return $candidates[0]
}

function Start-UiDump {
    param([pscustomobject]$Device)
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $script:Adb
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    foreach ($argument in @("-s", $Device.Serial, "exec-out", "uiautomator", "dump", "/dev/tty")) {
        [void]$startInfo.ArgumentList.Add($argument)
    }
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    [void]$process.Start()
    return [pscustomobject]@{
        device = $Device
        process = $process
        stdout = $process.StandardOutput.ReadToEndAsync()
        stderr = $process.StandardError.ReadToEndAsync()
    }
}

function Get-PairedSasFromUi {
    param([pscustomobject]$HostDevice, [pscustomobject]$ClientDevice)
    $dumps = @((Start-UiDump $HostDevice), (Start-UiDump $ClientDevice))
    $sasBySerial = @{}
    $confirmBySerial = @{}
    $rejectBySerial = @{}
    foreach ($dump in $dumps) {
        if (-not $dump.process.WaitForExit(4000)) {
            $dump.process.Kill($true)
            $dump.process.WaitForExit()
            continue
        }
        $xmlText = $dump.stdout.GetAwaiter().GetResult()
        [void]$dump.stderr.GetAwaiter().GetResult()
        try {
            $hierarchyEnd = $xmlText.LastIndexOf("</hierarchy>", [System.StringComparison]::Ordinal)
            if ($hierarchyEnd -lt 0) { throw "UI hierarchy was not returned." }
            $xmlText = $xmlText.Substring(0, $hierarchyEnd + "</hierarchy>".Length)
            [xml]$document = $xmlText
            $sasBySerial[$dump.device.Serial] = Get-SasFromDocument $document
            $confirmBySerial[$dump.device.Serial] = Get-UiTapPointFromDocument $document "Confirm"
            $rejectBySerial[$dump.device.Serial] = Get-UiTapPointFromDocument $document "Reject"
        } catch {
            $sasBySerial[$dump.device.Serial] = $null
            $confirmBySerial[$dump.device.Serial] = $null
            $rejectBySerial[$dump.device.Serial] = $null
        }
    }
    return [pscustomobject]@{
        host = $sasBySerial[$HostDevice.Serial]
        client = $sasBySerial[$ClientDevice.Serial]
        host_confirm = $confirmBySerial[$HostDevice.Serial]
        client_confirm = $confirmBySerial[$ClientDevice.Serial]
        host_reject = $rejectBySerial[$HostDevice.Serial]
        client_reject = $rejectBySerial[$ClientDevice.Serial]
    }
}

function Assert-WarpnectForeground {
    param([pscustomobject]$Device)
    $document = Get-UiDocument $Device
    $hasWarpnect = (Get-UiNodes $document | Where-Object { $_.package -eq $script:PackageName }).Count -gt 0
    if (-not $hasWarpnect) {
        throw "DEVICE_LOCKED_OR_NOT_FOREGROUND on $($Device.Serial). Unlock the device and relaunch Warpnect; the harness will not bypass device security."
    }
}

function Wait-ForWarpnectForeground {
    param([pscustomobject]$Device, [int]$TimeoutSeconds = 15)
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        try {
            Assert-WarpnectForeground $Device
            return $true
        } catch {
            Start-Sleep -Milliseconds 250
        }
    } while ([DateTime]::UtcNow -lt $deadline)
    return $false
}

function Ensure-WarpnectForeground {
    param([pscustomobject]$Device)
    if (Wait-ForWarpnectForeground $Device) { return }
    Invoke-Adb ($Device.Serial) @("shell", "am", "start", "-n", $script:ActivityName)
    if (-not (Wait-ForWarpnectForeground $Device)) {
        throw "DEVICE_LOCKED_OR_NOT_FOREGROUND on $($Device.Serial). Unlock the device and relaunch Warpnect; the harness will not bypass device security."
    }
}

function Wait-ForUiText {
    param([pscustomobject]$Device, [string]$Text, [int]$TimeoutSeconds = 15)
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        if ($null -ne (Find-UiNode $Device $Text)) { return $true }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    return $false
}

function Get-ClickNode {
    param([System.Xml.XmlNode]$Node)
    $current = $Node
    while ($null -ne $current) {
        if ($current.Attributes -and $current.Attributes["clickable"] -and $current.Attributes["clickable"].Value -eq "true") {
            return $current
        }
        $current = $current.ParentNode
    }
    return $Node
}

function Tap-UiText {
    param([pscustomobject]$Device, [string]$Text)
    $point = Get-UiTapPoint $Device $Text
    Tap-UiPoint $Device $point
}

function Get-UiTapPoint {
    param([pscustomobject]$Device, [string]$Text)
    return Get-UiTapPointFromDocument (Get-UiDocument $Device) $Text
}

function Get-UiTapPointFromDocument {
    param([xml]$Document, [string]$Text)
    $node = Get-UiNodes $Document | Where-Object {
        $_.text -eq $Text -or $_.'content-desc' -eq $Text
    } | Select-Object -First 1
    if ($null -eq $node) { return $null }
    $clickNode = Get-ClickNode $node
    $bounds = $clickNode.Attributes["bounds"].Value
    if ($bounds -notmatch "^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$") { return $null }
    $x = [int](([int]$matches[1] + [int]$matches[3]) / 2)
    $y = [int](([int]$matches[2] + [int]$matches[4]) / 2)
    return [pscustomobject]@{ x = $x; y = $y }
}

function Tap-UiPoint {
    param([pscustomobject]$Device, [pscustomobject]$Point)
    if ($null -eq $Point) { throw "UI action point was unavailable on $($Device.Serial)." }
    Invoke-Adb ($Device.Serial) @("shell", "input", "tap", "$($Point.x)", "$($Point.y)")
}

function Tap-IfPresent {
    param([pscustomobject]$Device, [string]$Text)
    if ($null -ne (Find-UiNode $Device $Text)) { Tap-UiText $Device $Text; return $true }
    return $false
}

function Capture-DeviceEvidence {
    param([pscustomobject]$Device, [string]$ScenarioDirectory)
    $serial = $Device.Serial
    $log = & $script:Adb -s $serial logcat -d -v threadtime 2>&1 |
        ForEach-Object { $_.ToString() } |
        Where-Object {
            $_ -match "WarpnectDiscovery" -or
                $_ -match "FATAL EXCEPTION" -or
                $_ -match "Process: io\.warpnect" -or
                $_ -match "AndroidRuntime:.*io\.warpnect"
        } |
        Select-Object -Last 400 |
        Out-String
    Write-RunText (Join-Path $ScenarioDirectory ("{0}.log" -f $serial)) (Redact-SensitiveText $log)
    try {
        $xml = Get-UiDocument $Device
        Write-RunText (Join-Path $ScenarioDirectory ("{0}.ui.xml" -f $serial)) (Redact-SensitiveText $xml.OuterXml)
        $screenshot = Join-Path $script:RunDirectory (Join-Path $ScenarioDirectory ("{0}.png" -f $serial))
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $screenshot) | Out-Null
        Save-RedactedScreenshot $Device $xml $screenshot
    } catch {
        Write-RunText (Join-Path $ScenarioDirectory ("{0}.ui_capture_error.txt" -f $serial)) (
            "UI_CAPTURE_UNAVAILABLE: " + (Redact-SensitiveText $_.Exception.Message)
        )
    }
}

function Test-DiscoveryBreadcrumb {
    param([pscustomobject]$Device, [string]$Event)
    $lines = & $script:Adb -s $Device.Serial logcat -d -v brief 2>&1 | ForEach-Object { $_.ToString() }
    return @($lines | Where-Object { $_ -match "WarpnectDiscovery.*event=$([regex]::Escape($Event))(\s|$)" }).Count -gt 0
}

function Save-RedactedScreenshot {
    param([pscustomobject]$Device, [xml]$Document, [string]$Destination)
    $temporary = Join-Path $env:TEMP ("warpnect-h1-{0}.png" -f [guid]::NewGuid().ToString("N"))
    try {
        & $script:Adb -s $Device.Serial exec-out screencap -p > $temporary
        if ($LASTEXITCODE -ne 0) { throw "Unable to capture screenshot from $($Device.Serial)." }
        Add-Type -AssemblyName System.Drawing
        $bitmap = [System.Drawing.Bitmap]::new($temporary)
        try {
            $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
            try {
                $brush = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::Black)
                try {
                    Get-UiNodes $Document |
                        Where-Object { $_.text -match "^\d{3}\s?\d{3}$" } |
                        ForEach-Object {
                            $bounds = $_.Attributes["bounds"].Value
                            if ($bounds -match "^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$") {
                                $graphics.FillRectangle(
                                    $brush,
                                    [int]$matches[1],
                                    [int]$matches[2],
                                    [int]$matches[3] - [int]$matches[1],
                                    [int]$matches[4] - [int]$matches[2]
                                )
                            }
                        }
                } finally {
                    $brush.Dispose()
                }
            } finally {
                $graphics.Dispose()
            }
            $bitmap.Save($Destination, [System.Drawing.Imaging.ImageFormat]::Png)
        } finally {
            $bitmap.Dispose()
        }
    } finally {
        [System.IO.File]::Delete($temporary)
    }
}

function Stop-ScenarioSemantically {
    param([pscustomobject]$HostDevice, [pscustomobject]$ClientDevice)
    [void](Tap-IfPresent $ClientDevice "Disconnect")
    [void](Tap-IfPresent $ClientDevice "Cancel search")
    [void](Tap-IfPresent $HostDevice "Disable Host")
}

function Start-PairingAttempt {
    param([pscustomobject]$HostDevice, [pscustomobject]$ClientDevice, [switch]$ReuseHost)
    Ensure-WarpnectForeground $HostDevice
    if (-not $ReuseHost) { Tap-UiText $HostDevice "Enable Host" }
    if (-not (Wait-ForUiText $HostDevice "Waiting for clients")) { throw "Host did not reach Waiting for clients." }
    Ensure-WarpnectForeground $ClientDevice
    if ($ReuseHost) {
        if (-not (Tap-IfPresent $ClientDevice "Cancel search")) {
            throw "Client did not expose the real Cancel search recovery action."
        }
        if (-not (Wait-ForUiText $ClientDevice "Ready")) { throw "Client did not return to Ready after Cancel search." }
    }
    Tap-UiText $ClientDevice "Find Hosts"
    if (-not (Wait-ForUiText $ClientDevice "Connect" 30)) { throw "Client did not discover a Connect action." }
    Tap-UiText $ClientDevice "Connect"
    $clientSas = $null
    $hostSas = $null
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    do {
        $sas = Get-PairedSasFromUi $HostDevice $ClientDevice
        $clientSas = $sas.client
        $hostSas = $sas.host
        if ($clientSas -and $hostSas) {
            if ($null -eq $sas.client_confirm -or $null -eq $sas.host_confirm -or
                $null -eq $sas.client_reject -or $null -eq $sas.host_reject) {
                throw "SAS prompt did not expose required Confirm and Reject actions."
            }
            break
        }
    } while ([DateTime]::UtcNow -lt $deadline)
    if (-not $clientSas -or -not $hostSas) { throw "SAS did not appear on both peers." }
    return [pscustomobject]@{
        sas_equal = ($clientSas -eq $hostSas)
        code = $clientSas
        expected_terminal = $null
        retry_sas_equal = $null
        retry_sas_fresh = $null
        host_confirm = $sas.host_confirm
        client_confirm = $sas.client_confirm
        host_reject = $sas.host_reject
        client_reject = $sas.client_reject
    }
}

function Reject-PairingAndWait {
    param([pscustomobject]$Device, [pscustomobject]$Point, [pscustomobject]$ClientDevice)
    Tap-UiPoint $Device $Point
    if (-not (Wait-ForUiText $ClientDevice "Failed" 10)) {
        throw "Client did not reach the expected terminal Failed state after Reject."
    }
    if (-not (Wait-ForNoSasPrompt $Device 5) -or -not (Wait-ForNoSasPrompt $ClientDevice 5)) {
        throw "A stale SAS verification prompt remained after Reject."
    }
}

function Fail-PairingByWithholdingHostConfirmationAndWait {
    param([pscustomobject]$ClientDevice, [pscustomobject]$ClientConfirm, [pscustomobject]$HostDevice)
    Tap-UiPoint $ClientDevice $ClientConfirm
    if (-not (Wait-ForUiText $ClientDevice "Failed" 10)) {
        throw "Client did not publish the expected terminal Failed state after pairing confirmation timed out."
    }
    if (-not (Wait-ForNoSasPrompt $ClientDevice 5) -or -not (Wait-ForNoSasPrompt $HostDevice 5)) {
        throw "A stale SAS verification prompt remained after the controlled pairing failure."
    }
}

function Wait-ForNoSasPrompt {
    param([pscustomobject]$Device, [int]$TimeoutSeconds = 10)
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $document = Get-UiDocument $Device
        $hasSas = @(Get-UiNodes $document | Where-Object { $_.text -match "^\d{3}\s?\d{3}$" }).Count -gt 0
        $hasVerification = $null -ne (Get-UiNodes $document | Where-Object { $_.text -eq "Verify security code" } | Select-Object -First 1)
        if (-not $hasSas -and -not $hasVerification) { return $true }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    return $false
}

function Verify-ReusableAttempt {
    param([pscustomobject]$HostDevice, [pscustomobject]$ClientDevice, [pscustomobject]$FirstAttempt)
    $retry = Start-PairingAttempt $HostDevice $ClientDevice -ReuseHost
    if (-not $retry.sas_equal) { throw "Retry SAS_EQUAL=false." }
    if ($retry.code -eq $FirstAttempt.code) { throw "Retry did not produce a fresh SAS." }
    Reject-PairingAndWait $ClientDevice $retry.client_reject $ClientDevice
    return $retry
}

function Invoke-PairingScenario {
    param([pscustomobject]$HostDevice, [pscustomobject]$ClientDevice)
    $result = Start-PairingAttempt $HostDevice $ClientDevice
    if (-not $result.sas_equal) { throw "SAS_EQUAL=false; automation must not confirm." }
    switch ($Scenario) {
        "ClientReject" { Reject-PairingAndWait $ClientDevice $result.client_reject $ClientDevice; $result.expected_terminal = "client_reject" }
        "HostReject" { Reject-PairingAndWait $HostDevice $result.host_reject $ClientDevice; $result.expected_terminal = "host_reject" }
        "RetryAfterClientReject" {
            Reject-PairingAndWait $ClientDevice $result.client_reject $ClientDevice
            $retry = Verify-ReusableAttempt $HostDevice $ClientDevice $result
            return [pscustomobject]@{
                sas_equal = $result.sas_equal
                retry_sas_equal = $retry.sas_equal
                retry_sas_fresh = $true
                expected_terminal = "retry_after_client_reject"
            }
        }
        "RetryAfterHostReject" {
            Reject-PairingAndWait $HostDevice $result.host_reject $ClientDevice
            $retry = Verify-ReusableAttempt $HostDevice $ClientDevice $result
            return [pscustomobject]@{
                sas_equal = $result.sas_equal
                retry_sas_equal = $retry.sas_equal
                retry_sas_fresh = $true
                expected_terminal = "retry_after_host_reject"
            }
        }
        "RetryAfterPairingFailed" {
            Fail-PairingByWithholdingHostConfirmationAndWait $ClientDevice $result.client_confirm $HostDevice
            $retry = Verify-ReusableAttempt $HostDevice $ClientDevice $result
            return [pscustomobject]@{
                sas_equal = $result.sas_equal
                retry_sas_equal = $retry.sas_equal
                retry_sas_fresh = $true
                expected_terminal = "retry_after_pairing_failed"
            }
        }
        "ConfirmClientThenHost" { Tap-UiPoint $ClientDevice $result.client_confirm; Tap-UiPoint $HostDevice $result.host_confirm }
        "ConfirmHostThenClient" { Tap-UiPoint $HostDevice $result.host_confirm; Tap-UiPoint $ClientDevice $result.client_confirm }
        "ConfirmNearSimultaneous" {
            $jobs = @(
                Start-Job { param($adb, $serial, $x, $y) & $adb -s $serial shell input tap $x $y } -ArgumentList $script:Adb, $ClientDevice.Serial, $result.client_confirm.x, $result.client_confirm.y
                Start-Job { param($adb, $serial, $x, $y) & $adb -s $serial shell input tap $x $y } -ArgumentList $script:Adb, $HostDevice.Serial, $result.host_confirm.x, $result.host_confirm.y
            )
            $jobs | Wait-Job | Receive-Job | Out-Null
            $jobs | Remove-Job -Force
        }
        default { Tap-UiPoint $HostDevice $result.host_confirm; Tap-UiPoint $ClientDevice $result.client_confirm }
    }
    Start-Sleep -Seconds 5
    return $result
}

function Invoke-MediaStartupTrace {
    param([pscustomobject]$HostDevice, [pscustomobject]$ClientDevice)
    Ensure-WarpnectForeground $HostDevice
    Tap-UiText $HostDevice "Enable Host"
    if (-not (Wait-ForUiText $HostDevice "Waiting for clients")) {
        throw "Host did not reach Waiting for clients."
    }
    Ensure-WarpnectForeground $ClientDevice
    Tap-UiText $ClientDevice "Find Hosts"
    if (-not (Wait-ForUiText $ClientDevice "Connect" 30)) {
        throw "Client did not discover a Connect action."
    }
    Tap-UiText $ClientDevice "Connect"

    $sasEqual = $null
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    do {
        $sas = Get-PairedSasFromUi $HostDevice $ClientDevice
        if ($sas.host -or $sas.client) {
            if (-not $sas.host -or -not $sas.client -or $sas.host -ne $sas.client) {
                throw "SAS_EQUAL=false or incomplete; automation must not confirm."
            }
            if ($null -eq $sas.host_confirm -or $null -eq $sas.client_confirm) {
                throw "SAS confirmation controls were unavailable."
            }
            Tap-UiPoint $HostDevice $sas.host_confirm
            Tap-UiPoint $ClientDevice $sas.client_confirm
            $sasEqual = $true
        }
        if (
            (Test-DiscoveryBreadcrumb $HostDevice "handshake_authenticated") -and
            (Test-DiscoveryBreadcrumb $ClientDevice "handshake_authenticated")
        ) {
            break
        }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)

    $hostAuthenticated = Test-DiscoveryBreadcrumb $HostDevice "handshake_authenticated"
    $clientAuthenticated = Test-DiscoveryBreadcrumb $ClientDevice "handshake_authenticated"
    if (-not $hostAuthenticated -or -not $clientAuthenticated) {
        throw "Secure Session did not authenticate on both peers."
    }
    Start-Sleep -Seconds 3
    return [pscustomobject]@{
        sas_equal = $sasEqual
        host_authenticated = $hostAuthenticated
        client_authenticated = $clientAuthenticated
        host_capability_completed = Test-DiscoveryBreadcrumb $HostDevice "capability_completed"
        client_capability_completed = Test-DiscoveryBreadcrumb $ClientDevice "capability_completed"
        host_setup_completed = Test-DiscoveryBreadcrumb $HostDevice "session_prepared"
        client_setup_completed = Test-DiscoveryBreadcrumb $ClientDevice "session_prepared"
        host_video_channel_ready = Test-DiscoveryBreadcrumb $HostDevice "video_channel_ready"
        client_video_channel_ready = Test-DiscoveryBreadcrumb $ClientDevice "video_channel_ready"
        host_media_started = Test-DiscoveryBreadcrumb $HostDevice "media_start_accepted"
        client_media_started = Test-DiscoveryBreadcrumb $ClientDevice "media_start_accepted"
    }
}

New-Item -ItemType Directory -Force -Path $script:RunDirectory | Out-Null
$selectedSerials = @(Select-HardwarePair)
$deviceARecord = [pscustomobject]@{ Serial = $selectedSerials[0] }
$deviceBRecord = [pscustomobject]@{ Serial = $selectedSerials[1] }
$selectionJson = @($deviceARecord, $deviceBRecord) | ConvertTo-Json
Write-RunText "device-selection.json" $selectionJson
$deviceASerial = [string]($deviceARecord.Serial)
$deviceBSerial = [string]($deviceBRecord.Serial)
if ([string]::IsNullOrWhiteSpace($deviceASerial) -or [string]::IsNullOrWhiteSpace($deviceBSerial)) {
    throw "Device selection did not produce two ADB serials. See device-selection.json."
}
$deviceInfo = @((Get-DeviceInfo $deviceARecord), (Get-DeviceInfo $deviceBRecord))
$deviceInfoJson = $deviceInfo | ConvertTo-Json
Write-RunText "device-info.json" $deviceInfoJson

$apkInfo = Build-And-Install -Devices @($deviceARecord, $deviceBRecord)
$scenarioDirectory = "scenario-$Scenario"
$hostDevice = $deviceARecord
$clientDevice = $deviceBRecord
if ($Scenario -eq "RoleReversal") { $hostDevice = $deviceBRecord; $clientDevice = $deviceARecord }

$scenarioResult = [ordered]@{
    scenario = $Scenario
    host_serial = $hostDevice.Serial
    client_serial = $clientDevice.Serial
    apk_sha256 = $apkInfo.sha256
    started_utc = [DateTime]::UtcNow.ToString("o")
    result = "FAIL"
    reason = "not_started"
    sas_equal = $null
    host_authenticated = $null
    client_authenticated = $null
    retry_sas_equal = $null
    retry_sas_fresh = $null
}

try {
    $clearForScenario = $CleanState -or $Scenario -eq "PairAcceptCleanState"
    Start-Warpnect $hostDevice -ClearState:$clearForScenario
    Start-Warpnect $clientDevice -ClearState:$clearForScenario
    if ($Scenario -eq "MediaStartupTrace") {
        $media = Invoke-MediaStartupTrace $hostDevice $clientDevice
        $scenarioResult["sas_equal"] = $media.sas_equal
        $scenarioResult["host_authenticated"] = $media.host_authenticated
        $scenarioResult["client_authenticated"] = $media.client_authenticated
        $scenarioResult["media"] = $media
        $scenarioResult.result = "PASS"
        $scenarioResult.reason = "secure Session trace captured"
    } else {
        $pairing = Invoke-PairingScenario $hostDevice $clientDevice
        $scenarioResult["sas_equal"] = $pairing.sas_equal
        $scenarioResult["retry_sas_equal"] = $pairing.retry_sas_equal
        $scenarioResult["retry_sas_fresh"] = $pairing.retry_sas_fresh
    }
    $clientFailed = (Find-UiNode $clientDevice "Failed") -ne $null
    $hostFailed = (Find-UiNode $hostDevice "Failed") -ne $null
    if ($Scenario -eq "MediaStartupTrace") {
        if ($clientFailed -or $hostFailed) {
            $scenarioResult.result = "FAIL"
            $scenarioResult.reason = "secure Session trace reached Failed"
        }
    } else {
        $scenarioResult["host_authenticated"] = Test-DiscoveryBreadcrumb $hostDevice "handshake_authenticated"
        $scenarioResult["client_authenticated"] = Test-DiscoveryBreadcrumb $clientDevice "handshake_authenticated"
    }
    if ($Scenario -ne "MediaStartupTrace" -and $pairing.expected_terminal -eq "client_reject") {
        if (
            (Test-DiscoveryBreadcrumb $clientDevice "pairing_local_reject") -and
            (Test-DiscoveryBreadcrumb $hostDevice "pairing_remote_reject_received")
        ) {
            $scenarioResult.result = "PASS"
            $scenarioResult.reason = "client rejection reached both peers"
        } else {
            $scenarioResult.reason = "client rejection was not observed by both peers"
        }
    } elseif ($Scenario -ne "MediaStartupTrace" -and $pairing.expected_terminal -eq "host_reject") {
        if (
            (Test-DiscoveryBreadcrumb $hostDevice "pairing_local_reject") -and
            (Test-DiscoveryBreadcrumb $clientDevice "pairing_remote_reject_received")
        ) {
            $scenarioResult.result = "PASS"
            $scenarioResult.reason = "host rejection reached both peers"
        } else {
            $scenarioResult.reason = "host rejection was not observed by both peers"
        }
    } elseif ($Scenario -ne "MediaStartupTrace" -and $pairing.expected_terminal -like "retry_after_*") {
        if ($pairing.retry_sas_equal -and $pairing.retry_sas_fresh) {
            $scenarioResult.result = "PASS"
            $scenarioResult.reason = "new matching SAS appeared after in-process recovery"
        } else {
            $scenarioResult.reason = "retry did not prove a fresh matching SAS"
        }
    } elseif ($Scenario -ne "MediaStartupTrace" -and ($clientFailed -or $hostFailed)) {
        $scenarioResult.reason = "pairing reached Failed"
    } elseif (
        $Scenario -ne "MediaStartupTrace" -and
        $Scenario -in @("PairAccept", "PairAcceptCleanState", "ConfirmClientThenHost", "ConfirmHostThenClient", "ConfirmNearSimultaneous", "RoleReversal") -and
        (-not $scenarioResult.host_authenticated -or -not $scenarioResult.client_authenticated)
    ) {
        $scenarioResult.reason = "post-pair handshake did not authenticate both peers"
    } elseif ($Scenario -ne "MediaStartupTrace") {
        $scenarioResult.result = "PASS"
        $scenarioResult.reason = "no immediate failure"
    }
} catch {
    $scenarioResult.reason = $_.Exception.Message
} finally {
    $scenarioResult.ended_utc = [DateTime]::UtcNow.ToString("o")
    Capture-DeviceEvidence $hostDevice $scenarioDirectory
    Capture-DeviceEvidence $clientDevice $scenarioDirectory
    Write-RunText (Join-Path $scenarioDirectory "result.json") ($scenarioResult | ConvertTo-Json)
    Stop-ScenarioSemantically $hostDevice $clientDevice
}

$scenarioResult | ConvertTo-Json
if ($scenarioResult.result -ne "PASS") { exit 1 }
