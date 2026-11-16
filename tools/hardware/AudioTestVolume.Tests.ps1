$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/AudioTestVolume.ps1"
$dump = @'
- STREAM_MUSIC:
  Muted: false
  Min: 0
  Max: 15
  Current: 2 (speaker): 5\50, 8000 (remote_submix): 7\70
  Devices: speaker
- STREAM_ALARM:
  Min: 1
  Max: 7
  Current: 2 (speaker): 4
  Devices: speaker
'@
$count = 0
function Check($value, $message) { if (-not $value) { throw $message }; $script:count++ }
function Reject($operation) { try { & $operation; throw 'Expected rejection absent' } catch {
    if ($_.Exception.Message -eq 'Expected rejection absent') { throw }; $script:count++
} }
$state = ConvertFrom-MusicVolumeDump $dump
Check ($state.Current -eq 5) 'Wrong original speaker level'
Check ($state.Max -eq 15 -and $state.Min -eq 0) 'Wrong stream range'
Check ((Get-AudibleTestVolume $state) -eq 10) 'Expected actual15-scale target10'
Check ($state.Levels.remote_submix -eq 7) 'Lost inactive route original'
$submix = ConvertFrom-MusicVolumeDump ($dump.Replace('Devices: speaker', 'Devices: remote_submix'))
Check ($submix.Current -eq 7) 'Route-specific restore must not use speaker value'
$other = ConvertFrom-MusicVolumeDump ($dump.Replace('Max: 15', 'Max: 25'))
Check ((Get-AudibleTestVolume $other) -eq 17) 'Do not assume15-level scale'
Reject { ConvertFrom-MusicVolumeDump ($dump.Replace('Max: 15', 'Max: 4')) }
Reject { ConvertFrom-MusicVolumeDump ($dump.Replace('Devices: speaker', 'Devices: speaker, headset')) }
Reject { ConvertFrom-MusicVolumeDump ($dump.Replace('Devices: speaker', 'Devices: missing')) }
Reject { ConvertFrom-MusicVolumeDump '- STREAM_ALARM: Min: 0' }
Check ($state.Levels.speaker -eq 5 -and $state.Levels.remote_submix -eq 7) 'Original state mutated'
"PASS $count volume-policy checks"
