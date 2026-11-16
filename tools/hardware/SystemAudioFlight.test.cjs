const {test}=require('node:test'),assert=require('node:assert/strict');
const {parse,summarize,receiveAge,receiveIntervals,aaudioWaitDeadlineBounds}=require('./SystemAudioFlight.cjs');
const event=(kind,begin,end,other={})=>({lane:0n,begin,end,position:0n,aux:0n,counter:0n,
  kind:BigInt(kind),tid:1n,generation:1n,sequence:1n,frames:240n,before:3360n,after:3120n,code:0n,phase:2n,...other});
const trace=(events,trigger=0n,reason=0)=>({meta:{trigger:String(trigger),reason:String(reason)},
  lanes:Array.from({length:5},(_,lane)=>({lane,contention:'0',invalid:'0'})),events});

const waitBound={verifiedBinaryContract:true,sameLocalClockRate:true,
 sampleLowerNs:'438764549225807',markerNs:'438764549262000',remainingNs:'1634925',
 timeoutCeilingNs:'1000000000',uncertaintyNs:'9346',observedWakeNs:'438764556428923'};
test('verified historical wait has a conservative computed deadline interval, not an expiry',()=>{
 const r=aaudioWaitDeadlineBounds(waitBound);
 assert.equal(r.earliestNs,'438764550851386');
 assert.equal(r.latestNs,'438764550906271');
 assert.equal(r.observedWakeAfterLatestLowerBoundNs,'5522652');
 assert.match(r.status,/NOT_SYSCALL/);
});
test('a potentially wrapped 32bit remaining interval cannot bound a deadline',()=>{
 assert.match(aaudioWaitDeadlineBounds({...waitBound,timeoutCeilingNs:'4294967296'}).status,/INVALID/);
});
test('a marker alone cannot establish the binary or clock contract',()=>{
 assert.match(aaudioWaitDeadlineBounds({...waitBound,verifiedBinaryContract:false}).status,/MISSING/);
 assert.match(aaudioWaitDeadlineBounds({...waitBound,sameLocalClockRate:false}).status,/MISSING/);
});
test('reversed sampling order is rejected',()=>{
 assert.match(aaudioWaitDeadlineBounds({...waitBound,sampleLowerNs:'438764549262001'}).status,/INVALID/);
});
test('a wake inside the conservative deadline interval has no demonstrated miss',()=>{
 assert.equal(aaudioWaitDeadlineBounds({...waitBound,observedWakeNs:'438764550900000'})
  .observedWakeAfterLatestLowerBoundNs,'0');
});
test('RX control distinguishes kernel age without cross-domain subtraction',()=>{
 const t=trace([event(8,1_000_000_000n,1_040_000_000n),event(1,1_040_001_000n,1_040_002_000n,
   {position:9_040_001_000n,aux:9_000_000_000n}),event(2,1_040_003_000n,1_040_004_000n)],1_040_004_000n,5);
 assert.match(summarize(t).classification,/^B_KERNEL/);
});
test('missing kernel timestamp is not evidence of punctual reception',()=>{
 const t=trace([event(8,1n,40_000_001n),event(1,40_000_002n,40_000_003n)],40_000_003n,5);
 assert.equal(summarize(t).classification,'RX_CONTROL_NOT_DISCRIMINATING');
});
test('declared fault without its injection cannot qualify discrimination',()=>{
 const t=trace([event(5,1n,2n)]);t.meta.fault='2';
 assert.equal(summarize(t).classification,'CONTROL_INJECTION_NOT_RETAINED_OR_NOT_EXECUTED');
});
test('decode control is not misclassified as network',()=>{
 const t=trace([event(9,10_000n,40_010_000n),event(3,9_999n,40_010_001n)],40_010_001n,5);
 assert.match(summarize(t).classification,/^C_PCM/);
});
test('callback body control is independent of ring rejection',()=>{
 const t=trace([event(10,1_000n,23_001_000n),event(5,999n,23_002_000n)],999n,6);
 assert.match(summarize(t).classification,/^D_CALLBACK_BODY/);
 assert.equal(summarize(t).rejectedFrames,0);
});
test('earlier spontaneous trigger invalidates attribution to a later injection',()=>{
 const t=trace([event(9,50_000_000n,90_000_000n)],1_000_000n,6);
 assert.match(summarize(t).classification,/^PREEXISTING_TRIGGER/);
});
test('fast callback after a gap does not prove runnable scheduling delay',()=>{
 const t=trace([event(5,20_000_000n,20_050_000n,{aux:10_000_000n})],20_000_000n,6);
 assert.equal(summarize(t).classification,'D_DELAY_BEFORE_CALLBACK_ENTRY_SCHEDULER_STATE_UNKNOWN');
});
test('admission rejection is not automatically UDP loss or xrun',()=>{
 const s=summarize(trace([event(4,1n,8_000_001n,{code:19n})],8_000_001n,1));
 assert.equal(s.rejectedFrames,240); assert.match(s.classification,/CAUSE_NOT_YET/);
});
test('no trigger is bounded non-observation, not fixed quality',()=>{
 assert.equal(summarize(trace([event(5,1n,2n)])).classification,'CAUSE_NOT_OBSERVED_IN_BOUNDED_RUN');
});
test('slow syscall bracket cannot establish microsecond kernel correlation',()=>{
 const age=receiveAge(event(1,1n,200_001n,{aux:10n,position:20n}));
 assert.equal(age.fineMapping,false);assert.equal(age.conservativeDelayLowerBoundMs,0);
});
test('bounded131us uncertainty does not erase an established41ms socket delay',()=>{
 const t=trace([event(8,1_000_000_000n,1_040_000_000n),event(1,1_040_010_000n,1_040_141_000n,
  {position:9_041_000_000n,aux:9_000_000_000n}),event(2,1_040_150_000n,1_040_160_000n)],1_040_150_000n,5);
 assert.match(summarize(t).classification,/^B_KERNEL/);
 assert.equal(receiveAge(t.events[1]).fineMapping,false);
});
test('REALTIME step producing negative residence invalidates the age observation',()=>{
 assert.equal(receiveAge(event(1,1n,2n,{aux:100n,position:20n})),null);
});
test('unsupported xrun zero stays unsupported',()=>{
 const s=summarize(trace([event(6,1n,2n,{code:1n})]));
 assert.equal(s.xrunObservations[0].supported,false);
});
test('REALTIME offset change blocks fine socket-age attribution',()=>{
 const t=trace([event(1,1_000_000n,1_001_000n,{position:9_030_000_000n,aux:9_000_000_000n})],1_001_000n,5);
 t.clocks=[{domain:'CLOCK',phase:'start',before:100n,after:200n,other:9_000_000_000n},
  {domain:'CLOCK',phase:'end',before:1_000_000n,after:1_000_100n,other:9_011_000_000n}];
 assert.equal(summarize(t).classification,'SOCKET_AGE_CLOCK_MAPPING_UNRELIABLE');
});
test('fixed schema rejects malformed rows and memory inflation',()=>{
 assert.throws(()=>parse('META,version=1,clock=MONOTONIC,bytes=1048576'),/memory/);
 assert.throws(()=>parse('META,version=2,clock=MONOTONIC'),/schema/);
 assert.throws(()=>parse('1,2,3'),/fixed/);
});

test('thread CPU and wall time stay distinct without inferring runnable delay',()=>{
 const s=summarize(trace([event(5,1_000_000n,24_000_000n,{code:8n,sequence:100_000n})]));
 assert.equal(s.callbackCpuMs.max,.1);
 assert.equal(s.callbackNonCpuMs.max,22.9);
 assert.match(s.limits.at(-1),/not automatically runnable/);
 assert.equal(summarize(trace([event(5,1n,2n)])).callbackCpuMs,null);
});

test('SDK counter publication time is the end, not query entry',()=>{
 const s=summarize(trace([event(6,1_000_000n,20_000_000n,{aux:19_000_000n,counter:9n})]));
 assert.equal(s.xrunObservations[0].at,'20000000');
 assert.equal(s.xrunObservations[0].queryBegin,'1000000');
 assert.equal(s.xrunObservations[0].sdkReturn,'19000000');
});

test('kernel interarrival uses ancillary time, never userspace REALTIME position',()=>{
 const rows=[event(1,100_000_000n,100_001_000n,{position:1_100_000_000n,aux:1_099_000_000n}),
  event(1,125_000_000n,125_001_000n,{position:1_125_000_000n,aux:1_119_000_000n})];
 const s=receiveIntervals(rows)[0];
 assert.equal(s.kernelGapMs,20);
 assert.equal(s.userspaceRealtimeGapMs,25);
 assert.equal(s.userspaceMonotonicGapMs,25);
 assert.equal(receiveIntervals([rows[0],event(1,126_000_000n,126_001_000n)])[0].kernelGapMs,null);
});
