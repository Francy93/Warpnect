Add-Type -AssemblyName System.IO.Compression.FileSystem

function Get-AndroidApkDexMarkers {
    param([Parameter(Mandatory)][string]$ApkPath, [Parameter(Mandatory)][string[]]$Markers)
    $archive = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $ApkPath))
    $markerEntries = @{}
    foreach ($marker in $Markers) { $markerEntries[$marker] = @() }
    try {
        $entries = @($archive.Entries | Where-Object { $_.FullName -match '^classes[0-9]*\.dex$' })
        foreach ($entry in $entries) {
            if ($entry.Length -gt 32MB) { throw 'DEX marker audit exceeds bounded entry size' }
            $stream = $entry.Open()
            $reader = [IO.StreamReader]::new($stream, [Text.Encoding]::ASCII)
            try { $contents = $reader.ReadToEnd() } finally { $reader.Dispose(); $stream.Dispose() }
            foreach ($marker in $Markers) {
                if ($contents.Contains($marker)) { $markerEntries[$marker] += $entry.FullName }
            }
        }
        return @{scope='ARTIFACT_MARKERS_NOT_EXECUTION_PROOF';dexEntries=$entries.Count;matches=$markerEntries}
    } finally { $archive.Dispose() }
}
