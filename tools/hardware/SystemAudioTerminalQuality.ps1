function Assert-SystemAudioTerminalQuality([string[]]$Lines) {
    $stops = @($Lines | Where-Object { $_ -match '\bAUDIO_PLAYBACK_STOP_BEGIN\b.*\bsource=SystemAudio\b' })
    if ($stops.Count -eq 0) { throw 'SYSTEM_AUDIO_TERMINAL_COUNTERS_MISSING' }
    foreach ($line in $stops) {
        $fields = @{}
        foreach ($match in [regex]::Matches($line, '\b([a-z_]+)=([^\s]+)')) {
            $fields[$match.Groups[1].Value] = $match.Groups[2].Value
        }
        foreach ($name in @('rejected', 'underrun', 'xrun')) {
            if ($fields[$name] -notmatch '^\d+$') { throw "SYSTEM_AUDIO_TERMINAL_COUNTER_UNAVAILABLE_$name" }
            if ([long]$fields[$name] -ne 0) { throw "SYSTEM_AUDIO_TERMINAL_QUALITY_FAIL_$name" }
        }
    }
}
