$ErrorActionPreference='Stop'
. "$PSScriptRoot/PerfettoReadiness.ps1"
if(-not (Test-BoundedPerfettoReady '[12.001] perfetto_cmd.cc:100 starting tracing for 3000 ms')){throw 'API29 ready'}
if(-not (Test-BoundedPerfettoReady '[206.195] perfetto_cmd.cc:792 Connected to the Perfetto traced service, TTL: 3s')){throw 'API31 ready'}
foreach($bad in @('Connected','Disconnected from service','starting tracing for 30000 ms','Connected to the Perfetto traced service, TTL: 30s','')) {
    if(Test-BoundedPerfettoReady $bad){throw "False readiness: $bad"}
}
'PASS 7 bounded Perfetto readiness checks'
