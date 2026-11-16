param([switch]$SelfTest,[string]$ObjectRoot,[string]$Nm,
    [ValidateSet('libscl_core.so','libwarpnect_output_contract_probe.so')]
    [ValidateNotNullOrEmpty()]
    [string[]]$Libraries=@('libscl_core.so'))
$ErrorActionPreference='Stop'
function Assert-NoFlightEmulatedTls([string]$Symbols) {
    if($Symbols -match '(?m)__emutls_\S*SystemAudioFlightDebug') {
        throw 'FLIGHT_CALLBACK_LAZY_TLS_ALLOCATION_POSSIBLE'
    }
    if($Symbols -notmatch 'SystemAudioFlightDebug::tid\(\)') {
        throw 'FLIGHT_IMPLEMENTATION_NOT_PRESENT_IN_DEBUG_ARTIFACT'
    }
}
if($SelfTest){
    Assert-NoFlightEmulatedTls '0000 W SystemAudioFlightDebug::tid()'
    Assert-NoFlightEmulatedTls "0000 W SystemAudioFlightDebug::tid()`n0001 __emutls_v_unrelated"
    $rejected=$false
    try{Assert-NoFlightEmulatedTls "0000 W SystemAudioFlightDebug::tid()`n0001 V __emutls_v._ZZSystemAudioFlightDebug3tidEv"}
    catch{if($_.Exception.Message -ne 'FLIGHT_CALLBACK_LAZY_TLS_ALLOCATION_POSSIBLE'){throw};$rejected=$true}
    if(-not $rejected){throw 'Known pre-fix binary must fail'}
    'PASS 3 compiled-symbol guard fixtures';return
}
if(-not $ObjectRoot -or -not $Nm){throw 'Exact built artifact root and existing llvm-nm required'}
$results=@()
foreach($abi in @('arm64-v8a','armeabi-v7a','x86_64')) {
    foreach($name in $Libraries) {
        $file=Join-Path (Join-Path $ObjectRoot $abi) $name
        $symbols=(& $Nm -C $file)-join "`n"
        if($LASTEXITCODE){throw 'Cannot inspect actual compiled artifact'}
        Assert-NoFlightEmulatedTls $symbols
        $results+=@{abi=$abi;library=$name;sha256=(Get-FileHash $file).Hash;result='PASS_NO_FLIGHT_EMULATED_TLS'}
    }
}
$results | ConvertTo-Json -Depth 3
