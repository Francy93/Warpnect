function ConvertFrom-MusicVolumeDump([string]$Dump) {
    $section = [regex]::Match($Dump, '(?ms)^\s*- STREAM_MUSIC:\s*(.*?)(?=^\s*- STREAM_|\z)')
    if (-not $section.Success) { throw 'STREAM_MUSIC section missing' }
    $text = $section.Groups[1].Value
    $min = [regex]::Match($text, '(?m)^\s*Min: (\d+)\s*$')
    $max = [regex]::Match($text, '(?m)^\s*Max: (\d+)\s*$')
    $device = [regex]::Match($text, '(?m)^\s*Devices: ([a-z_0-9]+)\s*$')
    if (-not $min.Success -or -not $max.Success -or -not $device.Success) {
        throw 'Unqualified stream range or single active output route'
    }
    $levels = @{}
    foreach ($match in [regex]::Matches($text, '[a-fA-F0-9]+ \(([^)]+)\): (\d+)(?:\\\d+)?')) {
        $levels[$match.Groups[1].Value] = [int]$match.Groups[2].Value
    }
    $route = $device.Groups[1].Value
    if (-not $levels.ContainsKey($route)) { throw 'Active route volume not available' }
    $minimum = [int]$min.Groups[1].Value
    $maximum = [int]$max.Groups[1].Value
    if ($minimum -ge $maximum -or $levels[$route] -lt $minimum -or $levels[$route] -gt $maximum) {
        throw 'Invalid stream volume range/value'
    }
    [pscustomobject]@{ Min=$minimum; Max=$maximum; Route=$route; Current=$levels[$route]; Levels=$levels }
}

function Get-AudibleTestVolume($State) {
    $target = [int][math]::Round($State.Min + 0.67 * ($State.Max - $State.Min))
    if ($target -ge $State.Max -or $target -le $State.Min) { throw 'No safe nonmaximum test volume' }
    $target
}
