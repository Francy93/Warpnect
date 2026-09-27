[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$SdkPath,
    [string]$ApkPath,
    [ValidateRange(1,3)][int]$Cycles = 3,
    [string]$FfmpegPath
)
$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^emulator-[0-9]+$') { throw 'Physical targets are forbidden.' }
if (-not $SdkPath) { $SdkPath = $env:ANDROID_SDK_ROOT }
if (-not $SdkPath) { $SdkPath = $env:ANDROID_HOME }
if (-not $SdkPath) { $SdkPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adb = Join-Path $SdkPath 'platform-tools\adb.exe'
if (-not $ApkPath) { $ApkPath = Join-Path $PSScriptRoot '..\..\app\build\outputs\apk\debug\app-debug.apk' }
if ($FfmpegPath -and -not (Test-Path -LiteralPath $FfmpegPath)) { throw 'Missing local AVC decoder observer.' }
$directory = Join-Path $PSScriptRoot ('..\..\app\build\reports\root-lab\root-audio-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $directory -Force | Out-Null
if ($FfmpegPath) { Add-Type -AssemblyName System.Drawing }

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
        $errors = $err.GetAwaiter().GetResult()
        if ($p.ExitCode -ne 0) { throw "Observer failed: $text $errors" }
        return $text.Trim()
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
function Logs { Adb ('logcat -d -T "' + $logStart + '" -s WarpnectDirectRoot:I *:S') }
function Wait-Log {
    param([string]$Pattern)
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        $logs = Logs
        if ($logs -match 'operation=[^ ]+ result=FAIL|direct_root_(audio|input|video)_qualification result=FAIL') { throw "Qualification failed: $logs" }
        if ($logs -match $Pattern) { Write-Host "ASSERT PASS: $Pattern"; return }
        Start-Sleep -Milliseconds 150
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Evidence timeout: $Pattern`n$logs"
}
function Decode-Video {
    param([int]$Cycle)
    foreach ($state in @('a','b')) {
        $file = Join-Path $directory "cycle${Cycle}_$state.h264"
        $png = Join-Path $directory "cycle${Cycle}_$state.png"
        Adb "pull /data/user/0/io.warpnect/files/root-video/state_$state.h264 $file" | Out-Host
        & $FfmpegPath -v error -y -i $file -frames:v 1 $png
        if ($LASTEXITCODE -ne 0) { throw 'AVC decode failed.' }
        $bitmap = [System.Drawing.Bitmap]::FromFile($png)
        try {
            if ($bitmap.Width -ne 640 -or $bitmap.Height -ne 360) { throw 'Wrong decoded geometry.' }
            $pixel = $bitmap.GetPixel(320,180)
            Write-Host "VIDEO_CONTENT cycle=$Cycle state=$state center_rgb=$($pixel.R),$($pixel.G),$($pixel.B)"
            if ($state -eq 'a' -and ($pixel.R -lt 200 -or $pixel.G -lt 200 -or $pixel.B -lt 200)) { throw 'White fixture absent.' }
            if ($state -eq 'b' -and ((@($pixel.R,$pixel.G,$pixel.B) | Measure-Object -Maximum).Maximum -
                (@($pixel.R,$pixel.G,$pixel.B) | Measure-Object -Minimum).Minimum -lt 120)) { throw 'Colored fixture absent.' }
        } finally { $bitmap.Dispose() }
    }
}

Assert-Target
if ((Adb 'shell ps -A') -match 'warpnect-root-[0-9a-f]+') { throw 'Existing Root helper must be explicitly stopped first.' }
$passes = 0
$attempts = 0
$generations = @()
$audioGenerations = @()
try {
    foreach ($cycle in 1..$Cycles) {
        Assert-Target
        $attempts++
        $logStart = Adb "shell date '+%m-%d %H:%M:%S.000'"
        Helper 'Prepare' | Out-Host
        Wait-Log 'operation=prepare result=OK state=LAB_BOOTSTRAP_AVAILABLE'
        $started = (Helper 'Start') -join "`n"
        Write-Host $started
        $identityLine = ($started -split '\r?\n' | Where-Object { $_ -match '^direct_root_helper_identity ' })
        $identity = $identityLine.Substring('direct_root_helper_identity '.Length) | ConvertFrom-Json
        $bootstrap = (Adb 'shell cat /data/user/0/io.warpnect/files/root-lab/request.json') | ConvertFrom-Json
        if ($identity.uid -ne 0 -or $identity.gid -ne 0 -or $identity.api -ne 26 -or
            $identity.provenance -ne 'LABORATORY_EXTERNAL_ROOT' -or $generations -contains $identity.generation) { throw 'Wrong or reused Root identity.' }
        $generations += $identity.generation
        Helper 'Acquire' | Out-Host
        Wait-Log "operation=acquire result=OK state=HELPER_READY generation=$($identity.generation) "
        $before = Adb "shell ls /proc/$($identity.pid)/fd"
        if ($FfmpegPath) {
            Helper 'QualifyInput' | Out-Host
            Wait-Log "direct_root_input_qualification result=PASS generation=$($identity.generation) "
            Helper 'QualifyVideo' | Out-Host
            Wait-Log "direct_root_video_qualification result=AWAITING_DECODED_CONTENT generation=$($identity.generation)"
            Decode-Video $cycle
            Helper 'ConfirmVideo' | Out-Host
            Wait-Log "direct_root_video_qualification result=PASS generation=$($identity.generation) "
        }
        Helper 'QualifyAudio' | Out-Host
        Wait-Log "direct_root_audio_qualification result=PASS generation=$($identity.generation)"
        $logs = Logs
        $line = ($logs -split '\r?\n' | Where-Object { $_ -match 'direct_root_audio_evidence ' } | Select-Object -Last 1)
        if ($line -notmatch 'audio_generation=([0-9]+) .*silence_rms=([^ ]+).*tone_samples=([0-9]+).*nonzero=([0-9]+).*rms=([^ ]+).*peak=([0-9]+).*tone_amplitude=([^ ]+).*app_frames=([0-9]+).*producer=(\{.*\})') { throw 'PCM evidence missing.' }
        $audio = [long]$Matches[1]
        $silence = [double]::Parse($Matches[2], [cultureinfo]::InvariantCulture)
        $amplitude = [double]::Parse($Matches[7], [cultureinfo]::InvariantCulture)
        $producer = $Matches[9] | ConvertFrom-Json
        if ($audioGenerations -contains $audio -or $silence -gt 100 -or $amplitude -lt 1000 -or
            $producer.errors -ne 0 -or $producer.occupancy -ne 0 -or $producer.notifications -ne $producer.acks) { throw 'Audio proof or drain cleanup invalid.' }
        $audioGenerations += $audio
        Write-Host "PCM_EVIDENCE cycle=$cycle $line"
        Wait-Log 'stale=REJECTED hmac=REJECTED replay=REJECTED magic=REJECTED version=REJECTED bounds=REJECTED provider=REJECTED'
        Wait-Log 'direct_root_pcm_wrong_caller result=REJECTED uid=1[0-9]+'
        Wait-Log 'duplicate=PASS size_mismatch=REJECTED unmap_close_idempotent=PASS'
        if ($FfmpegPath) {
            Helper 'CompletePlan' | Out-Host
            Wait-Log "direct_root_complete_plan result=PASS .*frozen=true generation=$($identity.generation) "
        }
        Helper 'AudioStatus' | Out-Host
        Wait-Log 'runtime_ready=true active=false pcm_lease=false'
        $after = Adb "shell ls /proc/$($identity.pid)/fd"
        $appPid = Adb 'shell pidof io.warpnect'
        $appFds = Adb "shell ls /proc/$appPid/fd"
        if ((Adb "shell cat /proc/$appPid/maps") -match 'warpnect-root-(pcm|audio)') { throw 'App PCM mapping leaked.' }
        if ((Adb "shell cat /proc/$($identity.pid)/maps") -match 'warpnect-root-(pcm|audio)') { throw 'Root PCM mapping leaked.' }
        Write-Host "RESOURCE cycle=$cycle helper_pid=$($identity.pid) before=$(@($before -split '\r?\n').Count) after=$(@($after -split '\r?\n').Count) app_pid=$appPid app_fds=$(@($appFds -split '\r?\n').Count)"
        Helper 'Stop' | Out-Host
        $deadline = [DateTime]::UtcNow.AddSeconds(10)
        $name = 'warpnect-root-' + ([long]$identity.generation).ToString('x')
        do { $ps = Adb 'shell ps -A'; if ($ps -notmatch [regex]::Escape($name)) { break }; Start-Sleep -Milliseconds 150 } while ([DateTime]::UtcNow -lt $deadline)
        if ($ps -match [regex]::Escape($name)) { throw 'Root helper leaked.' }
        $portHex = ([int]$bootstrap.port).ToString('X4')
        if ((Adb 'shell cat /proc/net/tcp') -match (':'+$portHex+'\s+[0-9A-F:]+\s+0A')) { throw 'Root listener leaked.' }
        if ((Adb 'shell ls /data/user/0/io.warpnect/files/root-lab') -match '(request\.json|\.secret)') { throw 'Bootstrap residue.' }
        Logs | Set-Content -LiteralPath (Join-Path $directory "cycle$cycle.log")
        $passes++
    }
    Adb 'shell am force-stop io.warpnect' | Out-Host
    Adb 'shell am start -W -n io.warpnect/.MainActivity' | Out-Host
    $logStart = Adb "shell date '+%m-%d %H:%M:%S.000'"
    Helper 'AudioStatus' | Out-Host
    Wait-Log 'records=\[.*SYSTEM_AUDIO.*\] runtime_ready=false active=false pcm_lease=false'
} finally {
    Helper 'Stop' | Out-Host
    Write-Host "ROOT AUDIO QUALIFICATIONS $passes / $attempts (no retry)"
    Write-Host "EVIDENCE $directory"
}
