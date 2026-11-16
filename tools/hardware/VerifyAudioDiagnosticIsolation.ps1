param(
    [Parameter(Mandatory=$true)][string]$CompileCommands,
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [string]$BaseRef='20928f4f3f971499d18804142ca10a4381f1b0e5'
)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$database=Get-Content -LiteralPath $CompileCommands -Raw | ConvertFrom-Json
$output=(New-Item -ItemType Directory -Path $OutputDirectory -Force).FullName
if($output.StartsWith($root+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) {
    throw 'Use an external artifact directory, not the source checkout'
}
$files=@('jni_bridge.cpp','src/audio_oboe_playback.cpp','src/audio_receiver_runtime.cpp','src/audio_transport.cpp')
$results=@()
foreach($file in $files) {
    $relative='app/src/main/cpp/'+$file
    $source=Join-Path $root $relative
    $entry=@($database | Where-Object { [IO.Path]::GetFullPath($_.file) -eq $source })
    if($entry.Count -ne 1){throw "Expected one compile command: $file"}
    # CMake commands use quoted paths, not shell pipelines. Never evaluate the command as code.
    $tokens=@([regex]::Matches($entry[0].command,'(?:[^\s"]+|"[^"]*")+') | ForEach-Object {$_.Value.Trim('"')})
    $compiler=$tokens[0]
    $options=@()
    for($i=1;$i -lt $tokens.Count;$i++) {
        if($tokens[$i] -eq '-o'){$i++;continue}
        if($tokens[$i] -eq '-c' -or $tokens[$i] -eq $entry[0].file){continue}
        $options+=$tokens[$i]
    }
    $options+=@('-DNDEBUG','-E','-P')
    $baseline=(& git -C $root show "${BaseRef}:$relative") -join "`n"
    if($LASTEXITCODE){throw "Missing baseline file: $relative"}
    $old=Join-Path $output ($file.Replace('/','-')+'.baseline.cpp')
    [IO.File]::WriteAllText($old,$baseline,[Text.UTF8Encoding]::new($false))
    $hashes=@()
    foreach($inputFile in @($old,$source)) {
        $preprocessed=(& $compiler @options $inputFile 2> (Join-Path $output 'preprocessor-stderr.txt')) -join "`n"
        if($LASTEXITCODE){throw "Preprocessor failed: $inputFile"}
        if($preprocessed -match 'SystemAudioFlightDebug|MicrophoneRingTimingDebug|AudioWireTimingDebug|nativeAudioReceiverFlightEnabled') {
            throw "DEBUG diagnostic leaked into NDEBUG: $file"
        }
        $normalized=[regex]::Replace($preprocessed,'\s+','')
        $hashes+=([Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($normalized))))
    }
    if($hashes[0] -ne $hashes[1]){throw "NDEBUG source differs from G1-G4: $file"}
    $results+=@{file=$relative;result='PASS_NDEBUG_WHITESPACE_NORMALIZED_IDENTITY';sha256=$hashes[1]}
}
$manifestChanges=@(& git -C $root diff $BaseRef --name-only -- 'app/src/*/AndroidManifest.xml' 'app/build.gradle.kts' 'app/src/main/cpp/CMakeLists.txt')
if($LASTEXITCODE -or $manifestChanges.Count){throw 'Manifest, dependency or native linkage delta requires explicit review'}
$results | ConvertTo-Json -Depth 4
