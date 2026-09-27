[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [Parameter(Mandatory = $true)][string]$FfmpegPath,
    [string]$SdkPath,
    [string]$ApkPath,
    [ValidateRange(1,3)][int]$Cycles = 3
)
$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^emulator-[0-9]+$') { throw 'Physical targets are forbidden.' }
if (-not $SdkPath) { $SdkPath = $env:ANDROID_SDK_ROOT }
if (-not $SdkPath) { $SdkPath = $env:ANDROID_HOME }
if (-not $SdkPath) { $SdkPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adb = Join-Path $SdkPath 'platform-tools\adb.exe'
if (-not $ApkPath) { $ApkPath = Join-Path $PSScriptRoot '..\..\app\build\outputs\apk\debug\app-debug.apk' }
if (-not (Test-Path -LiteralPath $FfmpegPath)) { throw 'A local FFmpeg observer is required; it never captures display pixels.' }
$directory = Join-Path $PSScriptRoot ('..\..\app\build\reports\root-lab\root-video-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $directory -Force | Out-Null
Add-Type -AssemblyName System.Drawing

function Adb {
    param([string]$Arguments)
    $p = New-Object System.Diagnostics.Process
    $p.StartInfo.FileName = $adb
    $p.StartInfo.Arguments = "-s $Serial $Arguments"
    $p.StartInfo.UseShellExecute = $false
    $p.StartInfo.CreateNoWindow = $true
    $p.StartInfo.RedirectStandardOutput = $true
    $p.StartInfo.RedirectStandardError = $true
    try {
        $null = $p.Start()
        $out = $p.StandardOutput.ReadToEndAsync()
        $err = $p.StandardError.ReadToEndAsync()
        if (-not $p.WaitForExit(20000)) { $p.Kill(); throw 'Observer timeout.' }
        $text = $out.GetAwaiter().GetResult()
        $errorText = $err.GetAwaiter().GetResult()
        if ($p.ExitCode -ne 0) { throw "Observer failed: $text $errorText" }
        $text.Trim()
    } finally { $p.Dispose() }
}
function Assert-Target {
    $avd = ((Adb 'emu avd name') -split '\r?\n' | Select-Object -First 1).Trim()
    if ($avd -ne 'Warpnect_Root_API26' -or (Adb 'shell getprop ro.kernel.qemu') -ne '1' -or
        (Adb 'shell getprop ro.build.version.sdk') -ne '26' -or (Adb 'shell getenforce') -ne 'Enforcing') { throw 'Wrong lab environment.' }
}
function Helper {
    param([string]$Action)
    & (Join-Path $PSScriptRoot 'RootHelper.ps1') -Action $Action -Serial $Serial -SdkPath $SdkPath -ApkPath $ApkPath
}
function Logs { Adb ('logcat -d -T "' + $logStart + '" -s WarpnectDirectRoot:I WarpnectCapture:I WarpnectDirectShellVideo:I *:S') }
function Wait-Log {
    param([string]$Pattern)
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        $logs = Logs
        if ($logs -match $Pattern) { Write-Host "ASSERT PASS: $Pattern"; return }
        if ($logs -match 'direct_root_video_qualification result=FAIL') { throw "Video boundary failed: $logs" }
        Start-Sleep -Milliseconds 150
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Evidence timeout: $Pattern`n$logs"
}
function Gone {
    param($Identity)
    $name = 'warpnect-root-' + ([long]$Identity.generation).ToString('x')
    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    do {
        if ((Adb 'shell ps -A') -notmatch [regex]::Escape($name)) { return }
        Start-Sleep -Milliseconds 150
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'Root helper leaked.'
}

Assert-Target
Helper 'Inspect' | Out-Null
$passes = 0
$attempts = 0
$generations = @()
$surfaceGenerations = @()
try {
    foreach ($cycle in 1..$Cycles) {
        Assert-Target
        $attempts++
        $logStart = Adb "shell date '+%m-%d %H:%M:%S.000'"
        Helper 'Prepare' | Out-Host
        $started = (Helper 'Start') -join "`n"
        Write-Host $started
        $line = ($started -split '\r?\n' | Where-Object { $_ -match '^direct_root_helper_identity ' })
        $identity = $line.Substring('direct_root_helper_identity '.Length) | ConvertFrom-Json
        $bootstrapRequest = (Adb 'shell cat /data/user/0/io.warpnect/files/root-lab/request.json') | ConvertFrom-Json
        if ($identity.uid -ne 0 -or $identity.gid -ne 0 -or $identity.api -ne 26 -or
            $identity.provenance -ne 'LABORATORY_EXTERNAL_ROOT' -or $generations -contains $identity.generation) { throw 'Wrong or reused Root identity.' }
        $generations += $identity.generation
        Helper 'Acquire' | Out-Host
        Wait-Log "operation=acquire result=OK state=HELPER_READY generation=$($identity.generation) "
        if ($cycle -eq 1) {
            Helper 'QualifyInput' | Out-Host
            Wait-Log "direct_root_input_qualification result=PASS generation=$($identity.generation) "
        }
        $before = Adb "shell ls /proc/$($identity.pid)/fd"
        Helper 'QualifyVideo' | Out-Host
        Wait-Log "direct_root_video_qualification result=AWAITING_DECODED_CONTENT generation=$($identity.generation)"
        $captureLog = Logs
        if ($captureLog -notmatch "direct_root_surface_armed generation=$($identity.generation) surface=([0-9]+)") { throw 'Surface generation absent.' }
        $surfaceGeneration = $Matches[1]
        if ($surfaceGenerations -contains $surfaceGeneration) { throw 'Surface generation reused.' }
        $surfaceGenerations += $surfaceGeneration
        Wait-Log 'runtime=REJECTED surface=REJECTED hmac=REJECTED provider=REJECTED magic=REJECTED'
        Wait-Log 'direct_root_surface_replay result=REJECTED'
        Wait-Log 'mirror=false surface=false provider=false'
        foreach ($state in @('a','b')) {
            $file = Join-Path $directory "cycle${cycle}_$state.h264"
            $png = Join-Path $directory "cycle${cycle}_$state.png"
            Adb "pull /data/user/0/io.warpnect/files/root-video/state_$state.h264 $file" | Out-Host
            & $FfmpegPath -v error -y -i $file -frames:v 1 $png
            if ($LASTEXITCODE -ne 0) { throw 'AVC decode failed.' }
            $bitmap = [System.Drawing.Bitmap]::FromFile($png)
            try {
                if ($bitmap.Width -ne 640 -or $bitmap.Height -ne 360) { throw 'Wrong decoded geometry.' }
                $pixel = $bitmap.GetPixel(320,180)
                Write-Host "CONTENT cycle=$cycle state=$state center_rgb=$($pixel.R),$($pixel.G),$($pixel.B) sha256=$((Get-FileHash $file -Algorithm SHA256).Hash)"
                if ($state -eq 'a' -and ($pixel.R -lt 200 -or $pixel.G -lt 200 -or $pixel.B -lt 200)) { throw 'Fixture A white center absent.' }
                if ($state -eq 'b' -and ((@($pixel.R,$pixel.G,$pixel.B) | Measure-Object -Maximum).Maximum - (@($pixel.R,$pixel.G,$pixel.B) | Measure-Object -Minimum).Minimum -lt 120)) { throw 'Fixture B colored checkerboard absent.' }
            } finally { $bitmap.Dispose() }
        }
        Helper 'ConfirmVideo' | Out-Host
        Wait-Log "direct_root_video_qualification result=PASS generation=$($identity.generation) "
        $after = Adb "shell ls /proc/$($identity.pid)/fd"
        $appPid = Adb 'shell pidof io.warpnect'
        $appFds = Adb "shell ls /proc/$appPid/fd"
        Write-Host "RESOURCE cycle=$cycle helper_pid=$($identity.pid) before=$(@($before -split '\r?\n').Count) after=$(@($after -split '\r?\n').Count) app_pid=$appPid app_fds=$(@($appFds -split '\r?\n').Count)"
        Helper 'VideoStatus' | Out-Host
        Wait-Log 'active=false surface_lease=false'
        Helper 'Stop' | Out-Host
        Gone $identity
        $portHex = ([int]$bootstrapRequest.port).ToString('X4')
        if ((Adb 'shell cat /proc/net/tcp') -match (':'+$portHex+'\s+[0-9A-F:]+\s+0A')) { throw 'Root listener leaked.' }
        if ((Adb 'shell ls /data/user/0/io.warpnect/files/root-lab') -match '(request\.json|\.secret)') { throw 'Bootstrap residue.' }
        $logs = Logs
        $logs | Set-Content -LiteralPath (Join-Path $directory "cycle$cycle.log")
        Write-Host $logs
        $passes++
    }
    Adb 'shell am force-stop io.warpnect' | Out-Host
    Adb 'shell am start -W -n io.warpnect/.MainActivity' | Out-Host
    $logStart = Adb "shell date '+%m-%d %H:%M:%S.000'"
    Helper 'VideoStatus' | Out-Host
    Wait-Log 'records=\[VIDEO, INPUT\]|records=\[INPUT, VIDEO\]'
    Wait-Log 'runtime_ready=false active=false surface_lease=false'
    Write-Host 'ASSERT PASS: persisted Root Video/Input; no runtime/capture after process restart'
} finally {
    Helper 'Stop' | Out-Host
    Write-Host "ROOT VIDEO QUALIFICATIONS $passes / $attempts (no retry)"
    Write-Host "EVIDENCE $directory"
}
