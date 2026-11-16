$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/SystemAudioTerminalQuality.ps1"
$count = 0
function Accept([string[]]$lines) { Assert-SystemAudioTerminalQuality $lines; $script:count++ }
function Reject([string[]]$lines, [string]$expected) {
    try { Assert-SystemAudioTerminalQuality $lines; throw 'Expected rejection absent' }
    catch { if ($_.Exception.Message -notlike $expected) { throw }; $script:count++ }
}
$zero = 'AUDIO_PLAYBACK_STOP_BEGIN source=SystemAudio generation=1 underrun=0 rejected=0 xrun=0'
Accept @($zero)
Reject @($zero.Replace('rejected=0', 'rejected=240')) '*QUALITY_FAIL_rejected'
Reject @($zero.Replace('underrun=0', 'underrun=129')) '*QUALITY_FAIL_underrun'
Reject @($zero.Replace('xrun=0', 'xrun=6')) '*QUALITY_FAIL_xrun'
Reject @() '*COUNTERS_MISSING'
Reject @($zero.Replace('SystemAudio', 'MicrophoneAudio')) '*COUNTERS_MISSING'
Reject @($zero.Replace('xrun=0', 'xrun=-1')) '*COUNTER_UNAVAILABLE_xrun'
Reject @($zero.Replace('underrun=0 ', '')) '*COUNTER_UNAVAILABLE_underrun'
Reject @($zero.Replace('rejected=0', 'rejected=240'), 'AUDIO_PLAYBACK_STOP post_reset_rejected=0 xrun=0') '*QUALITY_FAIL_rejected'
$root = [IO.Path]::GetFullPath("$PSScriptRoot/../../app/build/reports")
foreach ($fixture in @(
    @('r1400-human-listening-net5g', '*QUALITY_FAIL_rejected'),
    @('r1399-human-listening-current-lan', '*QUALITY_FAIL_underrun'),
    @('r1401-silent-late-transition-trace', '*QUALITY_FAIL_xrun'),
    @('r1369-startup-after-shared-load-three', '')
)) {
    $path = "$root/$($fixture[0])/23eaec0f12047ece.log"
    if (-not (Test-Path -LiteralPath $path)) { "SKIP optional local evidence: $($fixture[0])"; continue }
    $lines = @(Get-Content -LiteralPath $path)
    if ($fixture[1]) { Reject $lines $fixture[1] } else { Accept $lines }
}
"PASS $count terminal-quality checks; optional historical evidence is never required in a clean checkout"
