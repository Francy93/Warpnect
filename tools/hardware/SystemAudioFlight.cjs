const fs = require('node:fs');

const kinds = { receive: 1, auth: 2, decode: 3, admission: 4, callback: 5,
  xrun: 6, lifecycle: 7, rxFault: 8, decodeFault: 9, consumerFault: 10,
  encode: 11, send: 12, dispatch: 13, decoderCreated: 14, readiness: 15,
  readyBuffer: 16, receiverSnapshot: 17 };
const names = Object.fromEntries(Object.entries(kinds).map(([k,v])=>[v,k]));
const numeric = s => { if (!/^\d+$/.test(s)) throw Error('Invalid unsigned field'); return BigInt(s); };
const ms = n => Number(n) / 1e6;
function parse(text) {
  const lines = text.replace(/^\uFEFF/,'').trim().split(/\r?\n/);
  const result = {meta:{}, clocks:[], lanes:[], events:[]};
  for (const line of lines) {
    const cells = line.split(',');
    if (cells[0] === 'META' || cells[0] === 'LANE') {
      const obj = {};
      for (const cell of cells.slice(cells[0] === 'META' ? 1 : 2)) {
        const pair = cell.split('=');
        if (pair.length !== 2 || pair[0] in obj) throw Error('Malformed metadata');
        obj[pair[0]] = pair[1];
      }
      if (cells[0] === 'META') result.meta = obj;
      else result.lanes.push({lane:Number(cells[1]),...obj});
    } else if (cells[0] === 'CLOCK' || cells[0] === 'BOOT') {
      if (cells.length !== 5) throw Error('Malformed clock bracket');
      result.clocks.push({domain:cells[0],phase:cells[1],before:numeric(cells[2]),
        other:numeric(cells[3]),after:numeric(cells[4])});
    } else if (cells[0] !== 'lane') {
      if (cells.length !== 15) throw Error('Malformed fixed record');
      const values = cells.map(numeric);
      const keys = ['lane','begin','end','position','aux','counter','kind','tid','generation',
        'sequence','frames','before','after','code','phase'];
      const e = Object.fromEntries(keys.map((k,i)=>[k,values[i]]));
      if (e.begin === 0n || e.end < e.begin || e.lane > 4n || !names[Number(e.kind)])
        throw Error('Invalid event chronology/type');
      result.events.push(e);
    }
  }
  if (result.meta.version !== '1' || result.meta.clock !== 'MONOTONIC') throw Error('Unknown schema/clock');
  if (Number(result.meta.bytes) >= 1048576) throw Error('Recorder memory contract exceeded');
  if (result.lanes.length !== 5 || new Set(result.lanes.map(l=>l.lane)).size !== 5) throw Error('Incomplete lane metadata');
  result.events.sort((a,b)=>a.begin < b.begin ? -1 : a.begin > b.begin ? 1 : 0);
  return result;
}
function distribution(values) {
  if (!values.length) return null;
  values.sort((a,b)=>a-b);
  const at = p => values[Math.min(values.length-1,Math.floor(values.length*p))];
  return {count:values.length,p50:at(.5),p95:at(.95),p99:at(.99),max:values.at(-1)};
}
function receiveAge(e) {
  // REALTIME - REALTIME only; MONOTONIC brackets bound the observer duration.
  if (e.kind !== 1n || e.aux === 0n || e.position < e.aux) return null;
  const age=e.position-e.aux,bracket=e.end-e.begin;
  return {ageMs:ms(age),bracketMs:ms(bracket),fineMapping:bracket<=100000n,
    conservativeDelayLowerBoundMs:ms(age>bracket ? age-bracket : 0n),
    kernelMonoLow:e.aux-(e.position-e.begin),kernelMonoHigh:e.aux-(e.position-e.end)};
}
function receiveIntervals(events) {
  const rows=events.filter(e=>e.kind===1n);
  return rows.slice(1).map((e,i)=>({
    begin:rows[i].begin.toString(),end:e.begin.toString(),
    kernelGapMs:rows[i].aux && e.aux ? ms(e.aux-rows[i].aux) : null,
    userspaceRealtimeGapMs:ms(e.position-rows[i].position),
    userspaceMonotonicGapMs:ms(e.begin-rows[i].begin),
  }));
}
function clockContinuity(trace) {
  const brackets=(trace.clocks??[]).filter(c=>c.domain==='CLOCK' && c.before>0n &&
    c.other>0n && c.after>=c.before && c.after-c.before<=100000n);
  const start=brackets.find(c=>c.phase==='start'),end=brackets.find(c=>c.phase==='end');
  if(!start || !end)return {status:'UNVERIFIED_MISSING_OR_SLOW_BRACKET'};
  const low=(end.other-end.after)-(start.other-start.before);
  const high=(end.other-end.before)-(start.other-start.after);
  return {status:low>1000000n || high< -1000000n ? 'REALTIME_OFFSET_CHANGE_INVALIDATES_FINE_MAPPING' : 'ENDPOINT_OFFSETS_COMPATIBLE',
    changeLowNs:low.toString(),changeHighNs:high.toString(),
    limit:'Compatible endpoints do not exclude an unobserved intervening clock step.'};
}
function summarize(trace) {
  const events=trace.events;
  const find=kind=>events.filter(e=>e.kind === BigInt(kind));
  const faults=events.filter(e=>[8n,9n,10n].includes(e.kind));
  const trigger=numeric(trace.meta.trigger), reason=Number(trace.meta.reason);
  const loss=trace.lanes.some(l=>numeric(l.contention)>0n || numeric(l.invalid)>0n);
  let classification=trigger ? 'FIRST_BOUNDARY_REQUIRES_TIMELINE' : 'CAUSE_NOT_OBSERVED_IN_BOUNDED_RUN';
  const evidence=[];
  const clock=clockContinuity(trace);
  if (Number(trace.meta.fault)>0 && !faults.length) {
    classification='CONTROL_INJECTION_NOT_RETAINED_OR_NOT_EXECUTED';
  } else if (faults.length) {
    if (faults.length !== 1) throw Error('Controlled run must contain exactly one injection');
    const fault=faults[0];
    if (trigger && trigger < fault.begin-100000n) classification='PREEXISTING_TRIGGER_INVALIDATES_FAULT_ATTRIBUTION';
    else if (fault.kind===8n) {
      const rx=find(kinds.receive).find(e=>e.begin>=fault.end && e.begin-fault.end<10000000n);
      const age=rx && receiveAge(rx);
      const authenticated=rx && find(kinds.auth).some(e=>e.begin>=rx.end && e.begin-rx.end<2000000n);
      classification=age && age.conservativeDelayLowerBoundMs>25 && authenticated ? 'B_KERNEL_TO_APPLICATION_DELAY_CONTROL_CONFIRMED' : 'RX_CONTROL_NOT_DISCRIMINATING';
      if(age)evidence.push({socketAgeMs:age.ageMs,clockBracketMs:age.bracketMs});
    } else if (fault.kind===9n) {
      const decode=find(kinds.decode).find(e=>e.begin<=fault.begin && e.end>=fault.end);
      classification=decode && decode.end-decode.begin>35000000n ? 'C_PCM_AVAILABILITY_DELAY_CONTROL_CONFIRMED' : 'DECODE_CONTROL_NOT_DISCRIMINATING';
    } else {
      const callback=find(kinds.callback).find(e=>e.begin<=fault.begin && e.end>=fault.end);
      classification=callback && callback.end-callback.begin>20000000n ? 'D_CALLBACK_BODY_DELAY_CONTROL_CONFIRMED' : 'CONSUMER_CONTROL_NOT_DISCRIMINATING';
    }
  } else if (reason===6) {
    const candidates=find(kinds.callback).filter(e=>
      e.end-e.begin>6000000n || (e.aux>0 && e.begin-e.aux>6000000n));
    const e=candidates.find(e=>e.begin>=trigger-100000n) ?? candidates[0];
    if(e) {
      classification=e.end-e.begin>6000000n ? 'D_CALLBACK_BODY_DELAY_OBSERVED' : 'D_DELAY_BEFORE_CALLBACK_ENTRY_SCHEDULER_STATE_UNKNOWN';
      evidence.push({begin:e.begin.toString(),bodyMs:ms(e.end-e.begin),entryGapMs:e.aux?ms(e.begin-e.aux):null});
    }
  } else if (reason===5) {
    const e=find(kinds.receive).find(e=>e.end>=trigger-100000n && receiveAge(e)?.conservativeDelayLowerBoundMs>20);
    if(e) {classification='B_SOCKET_RESIDENCE_OBSERVED';evidence.push(receiveAge(e));}
  } else if (reason===4) classification='RX_DELIVERY_GAP_REQUIRES_MATCHED_TX_AND_KERNEL_INTERVALS';
  else if (reason===1) classification='ADMISSION_REJECTION_CAUSE_NOT_YET_ESTABLISHED';
  else if (reason===3) classification='XRUN_OBSERVED_IN_SNAPSHOT_OCCURRENCE_INTERVAL_NOT_EXACT';
  else if (reason===2) classification='PCM_RING_UNDERRUN_REQUIRES_UPSTREAM_TIMELINE';
  else if (reason===7) classification='DECODER_RECREATION_OBSERVED';
  if(loss)evidence.push({warning:'Recorder contention/invalid records; absence of an event cannot exonerate its boundary'});
  if(clock.status==='REALTIME_OFFSET_CHANGE_INVALIDATES_FINE_MAPPING') {
    evidence.push({warning:clock.status});
    if(classification.startsWith('B_'))classification='SOCKET_AGE_CLOCK_MAPPING_UNRELIABLE';
  }
  const perKind={};
  for(const [name,id] of Object.entries(kinds)) {
    const rows=find(id);
    if(rows.length) perKind[name]={count:rows.length,durationMs:distribution(rows.map(e=>ms(e.end-e.begin))),
      first:rows[0].begin.toString(),last:rows.at(-1).end.toString()};
  }
  const selected=events.filter(e=>trigger && e.end>=trigger-100000000n && e.begin<=trigger+150000000n);
  return {classification,meta:trace.meta,lanes:trace.lanes,recorderLoss:loss,clockContinuity:clock,
    triggerMonotonicNs:trigger.toString(),evidence,perKind,
    faults:faults.map(e=>({kind:names[Number(e.kind)],begin:e.begin.toString(),end:e.end.toString(),durationMs:ms(e.end-e.begin)})),
    callbackBodyMs:distribution(find(kinds.callback).map(e=>ms(e.end-e.begin))),
    callbackGapMs:distribution(find(kinds.callback).filter(e=>e.aux>0n).map(e=>ms(e.begin-e.aux))),
    callbackCpuMs:distribution(find(kinds.callback).filter(e=>(e.code&8n)!==0n).map(e=>ms(e.sequence))),
    callbackNonCpuMs:distribution(find(kinds.callback).filter(e=>(e.code&8n)!==0n && e.end-e.begin>=e.sequence)
      .map(e=>ms(e.end-e.begin-e.sequence))),
    receiveIntervals:receiveIntervals(events),
    rejectedFrames:find(kinds.admission).filter(e=>e.code===19n).reduce((n,e)=>n+Number(e.frames),0),
    ringUnderruns:find(kinds.callback).filter(e=>(e.code&2n)!==0n).length,
    xrunObservations:find(kinds.xrun).map(e=>({at:e.end.toString(),queryBegin:e.begin.toString(),
      sdkReturn:e.aux.toString(),previousObservation:e.position.toString(),
      count:Number(e.counter),supported:e.code===0n,phase:Number(e.phase)})),
    timeline:selected.map(e=>Object.fromEntries(Object.entries(e).map(([k,v])=>[k,k==='kind'?names[Number(v)]:v.toString()]))),
    limits:['Kernel timestamps are software stack observations, not RF arrival.',
      'No raw clock subtraction across devices; no inferred exact xrun occurrence from snapshots.',
      'Circular overwrite is expected; lane time coverage must be checked before absence claims.',
      'Callback wall-minus-thread-CPU is not automatically runnable scheduling delay; correlate stock scheduler events.']};
}
// This bound needs a separately verified binary/control-flow and local clock contract.
// aaSlpNs alone is not a captured syscall argument or timer-expiry observation.
function aaudioWaitDeadlineBounds(input) {
  if(input.verifiedBinaryContract!==true || input.sameLocalClockRate!==true)
    return {status:'REQUIRED_BINARY_OR_CLOCK_CONTRACT_MISSING'};
  const lower=BigInt(input.sampleLowerNs), marker=BigInt(input.markerNs);
  const remaining=BigInt(input.remainingNs), ceiling=BigInt(input.timeoutCeilingNs);
  const uncertainty=BigInt(input.uncertaintyNs), wake=BigInt(input.observedWakeNs);
  if(lower<=0n || marker<lower || remaining<0n || ceiling<=0n ||
      ceiling>2147483647n || remaining>ceiling || uncertainty<0n)
    return {status:'INVALID_OR_POTENTIALLY_WRAPPED_WAIT_BOUND'};
  const earliest=lower+remaining-uncertainty, latest=marker+remaining+uncertainty;
  const missed=wake>latest?wake-latest:0n;
  return {status:'COMPUTED_DEADLINE_INTERVAL_NOT_SYSCALL_OBSERVATION',
    earliestNs:earliest.toString(),latestNs:latest.toString(),
    observedWakeAfterLatestLowerBoundNs:missed.toString(),
    limits:['Conditional on normal successful clock sampling, verified order and no signed32 remaining-time alias.',
      'Does not identify the sleeping primitive, timer expiry or earliest wake request.',
      'A tracing write or other work before syscall entry is not excluded.']};
}
module.exports={parse,summarize,receiveAge,receiveIntervals,kinds,clockContinuity,aaudioWaitDeadlineBounds};
if(require.main===module){
  const result=summarize(parse(fs.readFileSync(process.argv[2],'utf8')));
  console.log(JSON.stringify(result,(_,v)=>typeof v==='bigint'?v.toString():v,2));
}
