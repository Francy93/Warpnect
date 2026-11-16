$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/AndroidApkEntryAudit.ps1"
$file = [IO.Path]::Combine([IO.Path]::GetTempPath(), "warpnect-apk-marker-$([guid]::NewGuid()).zip")
$stream = [IO.File]::Open($file, [IO.FileMode]::CreateNew)
try {
    $archive = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Create, $true)
    try {
        foreach ($fixture in @(
            @{name='classes.dex';text='baseline'},
            @{name='classes12.dex';text='contractPlan edges'},
            @{name='assets/not-a-dex.txt';text='assets-only'}
        )) {
            $writer = [IO.StreamWriter]::new($archive.CreateEntry($fixture.name).Open(), [Text.Encoding]::ASCII)
            try { $writer.Write($fixture.text) } finally { $writer.Dispose() }
        }
    } finally { $archive.Dispose(); $stream.Dispose() }
    $result = Get-AndroidApkDexMarkers -ApkPath $file -Markers @('baseline','contractPlan','edges','absent','assets-only')
    if ($result.dexEntries -ne 2) { throw 'Incorrect multidex enumeration' }
    if ($result.matches['baseline'] -notcontains 'classes.dex') { throw 'Primary DEX not searched' }
    if ($result.matches['contractPlan'] -notcontains 'classes12.dex') { throw 'Secondary DEX omitted' }
    if ($result.matches['edges'] -notcontains 'classes12.dex') { throw 'Edge plan omitted' }
    if ($result.matches['absent'].Count -ne 0 -or $result.matches['assets-only'].Count -ne 0) {
        throw 'Absent or non-DEX marker falsely accepted'
    }
    if ($result.matches.Count -ne 5 -or @($result.matches.Keys | Where-Object { $_ -isnot [string] }).Count -ne 0) {
        throw 'Regex automatic variable corrupted the marker dictionary'
    }
    $result | ConvertTo-Json -Depth 4 | Out-Null
    'PASS 6 APK marker checks; secondary DEX included, resources excluded, serialization valid'
} finally {
    $stream.Dispose()
    Remove-Item -LiteralPath $file -ErrorAction SilentlyContinue
}
