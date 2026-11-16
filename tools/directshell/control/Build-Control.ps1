param(
    [Parameter(Mandatory)][string]$Output,
    [Parameter(Mandatory)][string]$Sdk,
    [Parameter(Mandatory)][string]$Jdk
)
$ErrorActionPreference = 'Stop'
$dst = [System.IO.Path]::GetFullPath($Output)
if (Test-Path -LiteralPath $dst) { throw 'Control output must not already exist' }
$sdk = (Resolve-Path -LiteralPath $Sdk).Path
$jdk = (Resolve-Path -LiteralPath $Jdk).Path
$env:JAVA_HOME = $jdk
$platform = Join-Path $sdk 'platforms/android-35/android.jar'
$tool = Join-Path $sdk 'build-tools/35.0.0'
$source = Join-Path $PSScriptRoot 'src/io/warpnect/directshell/control'
$test = Join-Path $PSScriptRoot 'test/io/warpnect/directshell/control/ControlEndpointTest.java'
$client = Join-Path $PSScriptRoot 'client/io/warpnect/directshell/control/ControlWitness.java'
$inputs = @("$source/ControlMain.java","$source/ControlEndpoint.java",$test,$client)
$inputHashes = @($inputs | Get-FileHash)
[void][IO.Directory]::CreateDirectory($dst)
foreach ($part in 'classes','host-test','dex') { [void][IO.Directory]::CreateDirectory("$dst/$part") }

& "$jdk/bin/javac.exe" '-J-Xmx192m' --release 8 -classpath $platform -d "$dst/classes" $inputs[0] $inputs[1]
if ($LASTEXITCODE) { throw 'Control Android compilation failed' }
& "$jdk/bin/javac.exe" '-J-Xmx192m' --release 8 -classpath "$dst/classes" -d "$dst/host-test" $test $client
if ($LASTEXITCODE) { throw 'Control host compilation failed' }
$hostClasses = "$dst/classes$([IO.Path]::PathSeparator)$dst/host-test"
& "$jdk/bin/java.exe" '-Xmx96m' -cp $hostClasses io.warpnect.directshell.control.ControlEndpointTest |
    Tee-Object -FilePath "$dst/host-tests.txt"
if ($LASTEXITCODE -or (Get-Content "$dst/host-tests.txt") -cne 'CONTROL_HOST_TESTS=8 PASS') { throw 'Control host tests failed' }

$classes = @(Get-ChildItem -Recurse -File -LiteralPath "$dst/classes" -Filter '*.class' | Sort-Object Name -CaseSensitive)
$expected = @('ControlEndpoint$Ready.class','ControlEndpoint.class','ControlMain.class' | Sort-Object -CaseSensitive)
if (($classes.Name -join '|') -cne ($expected -join '|')) { throw 'Unexpected class input' }
& "$jdk/bin/jdeps.exe" '-J-Xmx192m' -verbose:class -filter:none -cp $platform "$dst/classes" |
    Out-File -LiteralPath "$dst/java-dependencies.txt" -NoClobber
$unsafeEdge = '^\s+\S+\s+->\s+.*(?:\snot found|\.debug\.|\.reflect\.|java\.lang\.(Runtime|ProcessBuilder)\s)'
if ($LASTEXITCODE -or (Select-String -LiteralPath "$dst/java-dependencies.txt" -CaseSensitive -Pattern $unsafeEdge -Quiet)) {
    throw 'Unresolved or prohibited Control class reference'
}

& "$tool/d8.bat" '-JXmx256m' --lib $platform --min-api 26 --output "$dst/dex" @($classes.FullName)
if ($LASTEXITCODE -or @(Get-ChildItem -File -LiteralPath "$dst/dex").Name -cne 'classes.dex') {
    throw 'Control DEX output mismatch'
}
$dex = "$dst/dex/classes.dex"
& "$tool/dexdump.exe" -d $dex | Out-File -LiteralPath "$dst/control-disassembly.txt" -NoClobber
if ($LASTEXITCODE) { throw 'DEX inspection failed' }
$content = Get-Content -Raw -LiteralPath "$dst/control-disassembly.txt"
$prefix = 'Lio/warpnect/directshell/control/'
$descriptors = @([regex]::Matches($content,"(?m)^\s*Class descriptor\s*:\s*'([^']+)'") |
    ForEach-Object { $_.Groups[1].Value } | Sort-Object -CaseSensitive)
$localDefinitions = @('ControlEndpoint;', 'ControlEndpoint$Ready;', 'ControlMain;',
    'ControlMain$$ExternalSyntheticLambda0;', 'ControlMain$$ExternalSyntheticLambda1;')
$allowed = @($localDefinitions | ForEach-Object { $prefix + $_ })
if (($descriptors -join '|') -cne (($allowed | Sort-Object -CaseSensitive) -join '|') -or
    $content -match 'Class;\.forName|Ljava/lang/reflect/|Ljava/lang/ProcessBuilder;|Ldalvik/|Landroid/app/|Lio/warpnect/debug/') {
    throw 'Control DEX closure rejected'
}
foreach ($h in $inputHashes) {
    if ((Get-FileHash -LiteralPath $h.Path).Hash -cne $h.Hash) { throw 'Control source changed during build' }
}
[ordered]@{result='CONTROL_DEX_REVIEW_ONLY';sha256=(Get-FileHash -LiteralPath $dex).Hash;
    dexBytes=(Get-Item -LiteralPath $dex).Length;descriptors=$descriptors;source=$inputHashes;
    sdk=(Get-FileHash -LiteralPath $platform).Hash;compiler=(Get-FileHash -LiteralPath "$tool/lib/d8.jar").Hash;
    hostTests=8;androidExecutions=0} | ConvertTo-Json -Depth 5 | Out-File -LiteralPath "$dst/receipt.json" -NoClobber
