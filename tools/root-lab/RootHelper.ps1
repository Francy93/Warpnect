[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Prepare', 'Start', 'Acquire', 'Inspect', 'Stop', 'Invalidate', 'NegativeAuth', 'NegativeGeneration', 'Duplicate', 'LoseHelper', 'NegativeUid', 'QualifyInput', 'InputStatus', 'StaleInput', 'HoldInput', 'DropInput', 'QualifyVideo', 'ConfirmVideo', 'HoldVideo', 'VideoStatus')]
    [string]$Action,
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [string]$SdkPath,
    [string]$ApkPath
)

$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^emulator-[0-9]+$') { throw 'Physical targets are forbidden.' }
if (-not $SdkPath) { $SdkPath = $env:ANDROID_SDK_ROOT }
if (-not $SdkPath) { $SdkPath = $env:ANDROID_HOME }
if (-not $SdkPath) { $SdkPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adb = Join-Path $SdkPath 'platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adb)) { throw 'Missing adb.' }
if (-not $ApkPath) { $ApkPath = Join-Path $PSScriptRoot '..\..\app\build\outputs\apk\debug\app-debug.apk' }
$expectedHash = (Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash.ToLowerInvariant()

function Invoke-RootLabAdb {
    param([string]$Arguments)
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo.FileName = $adb
    $process.StartInfo.Arguments = "-s $Serial $Arguments"
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.CreateNoWindow = $true
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    try {
        $null = $process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(20000)) {
            $process.Kill()
            throw "ADB client timeout: $Serial"
        }
        $output = $stdout.GetAwaiter().GetResult()
        $errors = $stderr.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) { throw "ADB failure: $errors $output" }
        return $output.Trim()
    } finally { $process.Dispose() }
}

# Identity checks precede EVERY root, launch and control operation.
$avd = (Invoke-RootLabAdb 'emu avd name') -split '\r?\n' | Select-Object -First 1
if ($avd.Trim() -ne 'Warpnect_Root_API26') { throw 'Only the qualified API26 AVD is permitted.' }
if ((Invoke-RootLabAdb 'shell getprop ro.kernel.qemu') -ne '1') { throw 'Not an emulator.' }
if ((Invoke-RootLabAdb 'shell getprop ro.build.version.sdk') -ne '26') { throw 'Wrong API.' }
$package = Invoke-RootLabAdb 'shell pm path io.warpnect'
if ($package -notmatch '^package:(/data/app/[A-Za-z0-9_~./=\-]+/base\.apk)$') { throw 'Unexpected package artifact.' }
$baseApk = $Matches[1]
$remoteHash = (Invoke-RootLabAdb "shell sha256sum $baseApk").Split(' ')[0]
if ($remoteHash -ne $expectedHash) { throw 'Installed APK differs from the exact local build.' }

function Invoke-AppAction {
    param([string]$Operation)
    Invoke-RootLabAdb "shell am broadcast -n io.warpnect/.debug.directshell.DirectRootLabReceiver --es operation $Operation"
}

if ($Action -eq 'Prepare') {
    Invoke-RootLabAdb 'shell am start -W -n io.warpnect/.MainActivity'
    Invoke-AppAction 'prepare'
}
if ($Action -eq 'Start') {
    Invoke-RootLabAdb 'root'
    Invoke-RootLabAdb 'wait-for-device'
    if ((Invoke-RootLabAdb 'shell id') -notmatch '^uid=0\(root\) gid=0\(root\)') { throw 'adbd did not acquire UID/GID0.' }
    $raw = Invoke-RootLabAdb 'shell cat /data/user/0/io.warpnect/files/root-lab/request.json'
    if ($raw.Length -gt 1024) { throw 'Oversized bootstrap metadata.' }
    $request = $raw | ConvertFrom-Json
    if ($request.generation -notmatch '^[1-9][0-9]{0,18}$' -or [long]$request.generation -le 0 -or
        $request.port -lt 1024 -or $request.port -gt 65535 -or
        $request.expires -notmatch '^[1-9][0-9]{0,15}$' -or
        $request.apk -ne $baseApk -or $request.sha256 -ne $expectedHash) { throw 'Invalid/stale Root bootstrap metadata.' }
    $hex = ([long]$request.generation).ToString('x')
    $secretPath = "/data/user/0/io.warpnect/files/root-lab/$hex.secret"
    $log = "/data/local/tmp/warpnect-root-$hex.log"
    $main = 'io.warpnect.debug.directshell.WarpnectRootHelper'
    # Secret bytes never cross a host argument or log: UID0 consumes the private app file directly.
    $command = "CLASSPATH=$baseApk /system/bin/nohup app_process /system/bin --nice-name=warpnect-root-$hex $main $($request.generation) $($request.port) $expectedHash $($request.expires) LABORATORY_EXTERNAL_ROOT </dev/null >$log 2>&1 &"
    Invoke-RootLabAdb ('shell "umask 077; ' + $command + '"')
    $deadline = [DateTime]::UtcNow.AddSeconds(8)
    do {
        $status = Invoke-RootLabAdb "shell cat $log"
        if ($status -match 'direct_root_bootstrap_result result=READY') { break }
        if ($status -match 'result=FAIL') { throw "Root helper rejected launch: $status" }
        Start-Sleep -Milliseconds 150
    } while ([DateTime]::UtcNow -lt $deadline)
    if ($status -notmatch 'direct_root_bootstrap_result result=READY') { throw 'Root READY timeout.' }
    Write-Output $status
}
if ($Action -eq 'Acquire') { Invoke-AppAction 'acquire' }
if ($Action -eq 'Stop') { Invoke-AppAction 'shutdown' }
if ($Action -eq 'Invalidate') { Invoke-AppAction 'invalidate' }
if ($Action -eq 'NegativeAuth') { Invoke-AppAction 'negative-auth' }
if ($Action -eq 'NegativeGeneration') { Invoke-AppAction 'negative-generation' }
if ($Action -eq 'Duplicate') { Invoke-AppAction 'duplicate' }
if ($Action -eq 'QualifyInput') { Invoke-AppAction 'qualify-input' }
if ($Action -eq 'InputStatus') { Invoke-AppAction 'input-status' }
if ($Action -eq 'StaleInput') { Invoke-AppAction 'stale-input' }
if ($Action -eq 'HoldInput') { Invoke-AppAction 'hold-input' }
if ($Action -eq 'DropInput') { Invoke-AppAction 'drop-input' }
if ($Action -eq 'QualifyVideo') { Invoke-AppAction 'qualify-video' }
if ($Action -eq 'ConfirmVideo') { Invoke-AppAction 'confirm-video' }
if ($Action -eq 'HoldVideo') { Invoke-AppAction 'hold-video' }
if ($Action -eq 'VideoStatus') { Invoke-AppAction 'video-status' }
if ($Action -eq 'NegativeUid') {
    if ((Invoke-RootLabAdb 'shell id') -notmatch '^uid=2000\(shell\) gid=2000\(shell\)') { throw 'Negative UID test requires unrooted adbd.' }
    $request = (Invoke-RootLabAdb 'shell run-as io.warpnect cat files/root-lab/request.json') | ConvertFrom-Json
    if ($request.generation -notmatch '^[1-9][0-9]{0,18}$' -or $request.port -lt 1024 -or $request.port -gt 65535 -or
        $request.expires -notmatch '^[1-9][0-9]{0,15}$' -or $request.apk -ne $baseApk -or
        $request.sha256 -ne $expectedHash) { throw 'Invalid metadata.' }
    $command = "shell CLASSPATH=$baseApk app_process /system/bin io.warpnect.debug.directshell.WarpnectRootHelper $($request.generation) $($request.port) $expectedHash $($request.expires) LABORATORY_EXTERNAL_ROOT"
    try { Invoke-RootLabAdb $command; throw 'Wrong UID helper was accepted.' }
    catch {
        if ($_ -notmatch 'FAIL_ROOT_UID_REQUIRED uid=2000 gid=2000') { throw }
        Write-Output 'ASSERT PASS: UID2000 launch rejected before reading secret or opening endpoint.'
    }
}
if ($Action -eq 'LoseHelper') {
    $request = (Invoke-RootLabAdb 'shell cat /data/user/0/io.warpnect/files/root-lab/request.json') | ConvertFrom-Json
    if ($request.generation -notmatch '^[1-9][0-9]{0,18}$') { throw 'Invalid generation.' }
    $name = 'warpnect-root-' + ([long]$request.generation).ToString('x')
    $helperPid = Invoke-RootLabAdb "shell pidof $name"
    if ($helperPid -notmatch '^[1-9][0-9]{0,8}$') { throw 'Expected exactly one current Root helper.' }
    $identity = Invoke-RootLabAdb "shell cat /proc/$helperPid/status"
    if ($identity -notmatch '(?m)^Uid:\s+0\s+0\s+0\s+0\s*$') { throw 'Helper is not UID0.' }
    $args = Invoke-RootLabAdb "shell cat /proc/$helperPid/cmdline"
    if (($args -split "`0")[0] -ne $name) { throw 'Helper process identity changed.' }
    Invoke-RootLabAdb "shell kill -TERM $helperPid"
    Write-Output "Only current Warpnect Root helper $helperPid terminated for explicit loss validation."
}
if ($Action -eq 'Inspect') {
    Invoke-AppAction 'status'
    Invoke-RootLabAdb 'shell ps -A'
    Invoke-RootLabAdb 'logcat -d -s WarpnectDirectRoot:I *:S'
}
