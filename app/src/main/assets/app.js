'use strict';
const $ = id => document.getElementById(id);
const state = {
  connected:false, telemetry:null, gps:null, trips:[], recording:false,
  distanceMiles:0,tripId:null,tripStart:null,curves:[],selectedCurve:'Speed',faults:[],sensors:[]
};
const number = (value,digits=1) => typeof value === 'number' && Number.isFinite(value)
  ? value.toFixed(digits) : '—';
const native = (name,...args) => {
  try{ if(window.Cart && typeof Cart[name] === 'function')return Cart[name](...args); }
  catch(e){console.error('Native call failed',name,e);}
  return null;
};
const set = (id,value) => {const el=$(id); if(el && el.textContent!==String(value))el.textContent=String(value);};
function selectPage(page){
  for(const el of document.querySelectorAll('.page'))el.classList.toggle('active',el.dataset.page===page);
  for(const btn of document.querySelectorAll('.nav-btn')){
    const selected=btn.dataset.target===page;
    btn.classList.toggle('chosen',selected);
    if(btn.dataset.target!=='batteryhealth')btn.querySelector('img').src='img/ic_'+btn.dataset.target+'_'+(selected?'active':'inactive')+'.png';
  }
  if(page==='trips')reloadTrips();
  if(page==='batteryhealth')refreshBattery();
  if(page==='throttle')loadSavedPresets();
  if(page==='diagnostics')refreshDiscovery();
  window.scrollTo({top:0,behavior:'instant'});
}
for(const button of document.querySelectorAll('.nav-btn'))button.addEventListener('click',()=>selectPage(button.dataset.target));
$('faultQuick').onclick=()=>selectPage('diagnostics');
for(const bar of document.querySelectorAll('.segment-control')){
  const target=bar.dataset.segment;
  bar.querySelectorAll('button').forEach(button=>button.onclick=()=>{
    for(const other of bar.querySelectorAll('button'))other.classList.toggle('current',other===button);
    const section=bar.parentElement;
    for(const panel of section.querySelectorAll('.sub-panel')){
      panel.classList.toggle('active',panel.dataset.subpanel===button.dataset.sub);
    }
    if(target==='trip' && button.dataset.sub==='history')reloadTrips();
    if(target==='throttle' && button.dataset.sub==='presets')loadSavedPresets();
  });
}

function clearTelemetry(){
  for(const id of ['batteryValue','ampsValue','throttleValue','throttleLive','driveTemp','diagKsi','diagSolenoid','diagTemp','faultCount','tripNow'])set(id,'—');
  $('throttleProgress').style.width='0%'; $('throttleProgress2').style.width='0%';
  $('faultList').replaceChildren(document.createTextNode('Connect the controller to view current fault status.'));
  set('faultBadge','UNAVAILABLE'); set('sensorStatus','NO CONNECTION');
  $('sensorList').replaceChildren(document.createTextNode('Controller not connected.'));
  state.telemetry=null;state.sensors=[];state.faults=[];
  set('batLiveV','—');set('batLogging','WAITING FOR USB');
}
function updateConnection(data){
  state.connected=!!data.connected;
  const button=$('connectBtn');
  button.classList.toggle('disconnected',!state.connected);
  set('connectText',state.connected?'CONNECTED  •  DISCONNECT':'CONNECT TO ALLTRAX');
  set('connectionDetail',data.message||'USB status unavailable');
  set('diagUsb',state.connected?'Connected':'Disconnected');
  if(data.model)set('modelText',`${data.model} · ${data.voltage}V / ${data.amps}A`);
  if(!state.connected)clearTelemetry();
}
function onTelemetry(data){
  if(data.unavailable){clearTelemetry();return;}
  state.telemetry=data;
  state.faults=Array.isArray(data.faults)?data.faults:[];
  state.sensors=Array.isArray(data.sensors)?data.sensors:[];
  set('batteryValue',number(data.volts,1));set('ampsValue',number(data.amps,1));
  set('batLiveV',number(data.volts,2));set('batLogging','RECORDING');
  set('throttleValue',typeof data.throttlePct==='number'?number(data.throttlePct,0)+'%':'—');
  set('throttleLive',typeof data.throttlePct==='number'?number(data.throttlePct,1)+'%':'—');
  const percent=typeof data.throttlePct==='number'?Math.max(0,Math.min(100,data.throttlePct)):0;
  $('throttleProgress').style.width=percent+'%'; $('throttleProgress2').style.width=percent+'%';
  const temp=typeof data.controllerTempF==='number'?number(data.controllerTempF,0)+'°F':'—';
  set('driveTemp',temp);set('diagTemp',temp);
  set('tripNow',typeof data.volts==='number'?number(data.volts,1)+'V':'—');
  set('faultCount',state.faults.length);set('faultBadge',state.faults.length+' ACTIVE');
  const ksi=state.sensors.find(x=>x.name==='Key-switch voltage');
  set('diagKsi',ksi && typeof ksi.value==='number'?number(ksi.value,1)+' V':'—');
  const sol=state.sensors.find(x=>x.name==='Solenoid relay state');
  set('diagSolenoid',sol && typeof sol.value==='number' ? String(sol.value)+' (raw)': '—');
  renderFaults();renderSensors();
}
function onGps(data){
  state.gps=data;
  if(data.error){set('gpsStatus',data.error);set('speedValue','—');set('tripSpeed','—');$('speedFill').setAttribute('stroke-dasharray','0 427');return;}
  if(data.message){set('gpsStatus',data.message);return;}
  const mph=data.speedMph;
  set('speedValue',number(mph,0));
  set('tripSpeed',typeof mph==='number'?number(mph,1)+' mph':'—');
  if(typeof data.distanceMiles==='number'){
    state.distanceMiles=data.distanceMiles;
    set('tripDistance',number(data.distanceMiles,2)+' mi');
  }
  if(typeof data.accuracyMeters==='number')set('gpsStatus','GPS satellite fix  ·  accuracy ±'+number(data.accuracyMeters,0)+' m');
  const ratio=typeof mph==='number'?Math.max(0,Math.min(1,mph/40)):0;
  $('speedFill').setAttribute('stroke-dasharray',(ratio*427)+' 427');
}
function onTripState(data){
  state.recording=!!data.recording;
  state.tripId=data.id;
  set('tripStatus',state.recording?'RECORDING':'NOT RECORDING');
  $('startTripBtn').disabled=state.recording;
  $('finishTripBtn').disabled=!state.recording;
  $('startTripBtn').textContent=state.recording?'● Recording...':'▶ Start Recording';
  const found=state.trips.find(t=>t.id===state.tripId);
  if(found && typeof found.startV==='number')set('tripStart',number(found.startV,1)+'V');
  else if(!state.recording)set('tripStart','—');
  if(!state.recording && typeof data.distanceMiles==='number')set('tripDistance',number(data.distanceMiles,2)+' mi');
  reloadTrips();
}
function onTrips(data){state.trips=data.trips||[];renderHistory();}
function reloadTrips(){
  const text=native('trips');
  try{ if(text){state.trips=JSON.parse(text);renderHistory();} }catch(e){console.error(e);}
}
function renderHistory(){
  const parent=$('tripHistory');parent.replaceChildren();
  if(!state.trips.length){parent.textContent='No trips recorded on this phone yet.';return;}
  if(state.recording){const first=state.trips.find(t=>t.id===state.tripId);if(first && typeof first.startV==='number')set('tripStart',number(first.startV,1)+'V');}
  for(const trip of state.trips){
    const btn=document.createElement('button');btn.className='history-row';
    const title=document.createElement('strong');title.textContent=new Date(trip.started).toLocaleString() + (trip.ended?'':'  ·  IN PROGRESS');
    const sub=document.createElement('small');
    sub.textContent=`Start ${number(trip.startV)} V  ·  End ${number(trip.endV)} V  ·  Min ${number(trip.minV)} V  ·  ${number(trip.distanceMeters/1609.344,2)} mi`;
    btn.append(title,sub);
    btn.onclick=()=>showSamples(trip.id);
    parent.append(btn);
  }
}
function showSamples(id){
  try{
    const items=JSON.parse(native('samples',Number(id))||'[]');
    graph($('tripChart'),items,'v','a');
    set('chartNote',`${items.length} recorded samples • voltage (cyan) and motor amperage (orange) are separate scales. Peak motor A is not battery A.`);
  }catch(e){set('chartNote','Failed to load trip history: '+e.message);}
}
function graph(canvas,items,voltageKey,ampsKey){
  const ctx=canvas.getContext('2d'),w=canvas.width,h=canvas.height;
  ctx.fillStyle='#08151a';ctx.fillRect(0,0,w,h);
  ctx.strokeStyle='#25404a';ctx.lineWidth=1;
  const L=46,T=22,W=w-72,H=h-55;
  for(let i=0;i<=5;i++){ctx.beginPath();ctx.moveTo(L,T+i*H/5);ctx.lineTo(L+W,T+i*H/5);ctx.stroke();}
  if(items.length<2){ctx.fillStyle='#a9bcc4';ctx.font='18px system-ui';ctx.fillText('Need at least two recorded samples',L+12,T+H/2);return;}
  const plot=(key,color,yoffset=0)=>{
    const valid=items.map((r,i)=>({i,val:r[key]})).filter(r=>typeof r.val==='number'&&Number.isFinite(r.val));
    if(valid.length<2)return;
    const min=Math.min(...valid.map(r=>r.val)),max=Math.max(...valid.map(r=>r.val));
    const spread=Math.max(0.1,max-min);
    ctx.strokeStyle=color;ctx.lineWidth=3;ctx.beginPath();
    valid.forEach(({i,val},idx)=>{const x=L+i/(items.length-1)*W;const y=T+H-(val-min)/spread*H;if(idx===0)ctx.moveTo(x,y);else ctx.lineTo(x,y);});ctx.stroke();
    ctx.fillStyle=color;ctx.font='15px system-ui';ctx.fillText(`${key==='v'?'Voltage':'Motor A'} ${number(min)}—${number(max)}`,L+8,T+17+yoffset);
  };
  plot(voltageKey,'#00e5d4');plot(ampsKey,'#ff9800',23);
}
function renderFaults(){
  const list=$('faultList');list.replaceChildren();
  if(!state.telemetry){list.textContent='Connect the controller to read faults.';return;}
  if(!state.faults.length){const good=document.createElement('div');good.className='fault-clear';good.textContent='✓ No active flags in the latest read';list.append(good);return;}
  for(let i=0;i<state.faults.length;i++){
    const box=document.createElement('div');box.className='fault-item';
    const name=document.createElement('b');name.textContent=`${i+1}. ${state.faults[i]}`;
    const note=document.createElement('small');note.textContent='Active flag reported by controller';
    box.append(name,note);list.append(box);
  }
}
function renderSensors(){
  const list=$('sensorList');list.replaceChildren();
  if(!state.telemetry){list.textContent='Connect to read live sensors.';return;}
  set('sensorStatus',state.sensors.filter(x=>x.status==='reported').length+' / '+state.sensors.length+' REPORTED');
  const query=$('sensorFilter').value.trim().toLowerCase();
  for(const sensor of state.sensors){
    if(query && !(sensor.name+' '+sensor.address).toLowerCase().includes(query))continue;
    const row=document.createElement('div');row.className='sensor-row';
    const left=document.createElement('div'),right=document.createElement('div');
    const label=document.createElement('b');label.textContent=sensor.name;
    const addr=document.createElement('small');addr.textContent=sensor.address+' · raw: '+(sensor.raw??'—');
    const value=document.createElement('strong');
    value.textContent=(sensor.value===null||sensor.value===undefined?'—':sensor.value)+(sensor.value===null||sensor.value===undefined?'':(' '+sensor.unit));
    const status=document.createElement('small');status.className='status '+sensor.status;
    status.textContent=sensor.status;
    left.append(label,addr);right.append(value,status);row.append(left,right);list.append(row);
  }
  if(!list.childNodes.length)list.textContent='No sensors match this filter.';
}
function onThrottle(data){
  if(data.error){set('throttleSettingsContent',data.error);set('curvePoints',data.error);return;}
  if(data.kind==='settings'){
    const content=[
      'Flash address: '+data.address,
      'Throttle type (raw): '+data.throttleTypeRaw,
      'Response rate (raw): '+data.responseRateRaw,
      'HPD (raw): '+data.hpdRaw,
      'Relay at zero (raw): '+data.relayZeroRaw,
      '', 'Full flash bytes:', data.raw,'',data.note
    ];
    set('throttleSettingsContent',content.join('\n'));
  }else if(Array.isArray(data.curves)){
    state.curves=data.curves;
    renderCurve();
  }
}
function renderCurve(){
  const control=$('curveTabs');control.replaceChildren();
  for(const item of state.curves){
    const btn=document.createElement('button');btn.textContent=item.name;
    btn.classList.toggle('current',item.name===state.selectedCurve);
    btn.onclick=()=>{state.selectedCurve=item.name;renderCurve();};
    control.append(btn);
  }
  const selected=state.curves.find(x=>x.name===state.selectedCurve)||state.curves[0];
  if(!selected)return;
  const vals=selected.points||[];
  const ctx=$('curveChart').getContext('2d'),w=ctx.canvas.width,h=ctx.canvas.height;
  ctx.fillStyle='#091821';ctx.fillRect(0,0,w,h);
  ctx.strokeStyle='#305361';ctx.lineWidth=1;
  for(let i=0;i<=5;i++){ctx.beginPath();ctx.moveTo(45,20+i*(h-65)/5);ctx.lineTo(w-24,20+i*(h-65)/5);ctx.stroke();}
  ctx.beginPath();ctx.strokeStyle='#00e5d4';ctx.lineWidth=3;
  let count=0;
  for(let i=0;i<vals.length;i++){
    const p=vals[i];if(p.x>4095||p.y>4095)continue;
    const x=45+(p.x/4095)*(w-70),y=20+(1-p.y/4095)*(h-65);
    if(count===0)ctx.moveTo(x,y);else ctx.lineTo(x,y);count++;
  }
  ctx.stroke();
  const info=`${selected.name} curve — raw flash at ${selected.base}\n\n`+
    vals.map((p,i)=>`${String(i+1).padStart(2,'0')}: X=${p.x}   Y=${p.y}`).join('\n')+
    '\n\nDisplay assumes raw 0..4095 for preview only. DO NOT WRITE until map validated.';
  set('curvePoints',info);
}
function onMemory(data){set('memoryResult',data.error?'Read failed: '+data.error:`${data.address}\n${data.hex}`);}
function loadSavedPresets(){
  const output=$('savedPresetList');
  try{
    const saved=JSON.parse(native('presets')||'[]');
    output.textContent=saved.length?`${saved.length} local draft(s): ${saved.map(p=>p.name).join(' · ')}`:'No custom drafts saved yet.';
  }catch(e){output.textContent='Could not read local drafts.';}
}

// Flooded 48V lead-acid resting voltage: deliberately approximate, 25°C table.
// Do not use loaded voltage as state of charge or motor amps as battery amps.
const batteryState={hours:24,checks:[],samples:[]};
const socPoints=[[46.20,0],[46.68,10],[47.16,20],[47.52,30],[48.00,40],
  [48.36,50],[48.72,60],[49.08,70],[49.44,80],[49.92,90],[50.88,100]];
function estimateSoc(volts){
  if(typeof volts!=='number'||!Number.isFinite(volts))return null;
  if(volts<=socPoints[0][0])return 0;
  for(let i=1;i<socPoints.length;i++){
    if(volts<=socPoints[i][0]){
      const [lo,lp]=socPoints[i-1],[hi,hp]=socPoints[i];
      return Math.round(lp+(volts-lo)/(hi-lo)*(hp-lp));
    }
  }
  return 100;
}
function refreshBattery(){
  try{
    const samples=JSON.parse(native('batterySamples',batteryState.hours)||'[]');
    const checks=JSON.parse(native('batteryChecks')||'[]');
    batteryState.samples=samples;batteryState.checks=checks;
    renderBattery();
  }catch(e){set('batHistoryNote','Cannot load battery log: '+e.message);}
}
function renderBattery(){
  const samples=batteryState.samples,checks=batteryState.checks;
  const values=samples.map(s=>s.v).filter(v=>typeof v==='number'&&Number.isFinite(v));
  const amps=samples.map(s=>s.a).filter(v=>typeof v==='number'&&Number.isFinite(v));
  const min=values.length?Math.min(...values):null,max=values.length?Math.max(...values):null;
  set('batMinV',min===null?'—':number(min,2)+' V');
  set('batSwing',min===null?'—':number(max-min,2)+' V');
  set('batPeakA',amps.length?number(Math.max(...amps.map(Math.abs)),0)+' A':'—');
  set('batCount',samples.length);
  const check=checks[0];
  if(check){
    const pct=estimateSoc(check.v),age=Date.now()-check.at;
    set('batSoc',pct+'%');$('batSocFill').style.width=pct+'%';
    set('batConfidence',age>7*86400000?'OLD ESTIMATE':'RESTED VOLTAGE ESTIMATE');
    set('batExplanation',`Based on ${number(check.v,2)} V recorded ${new Date(check.at).toLocaleString()}. This is an approximate charge estimate at that time, not a live reading. A weak cell can make it overly optimistic.`);
  }else{
    set('batSoc','—');$('batSocFill').style.width='0%';
    set('batConfidence','Needs a rested reading');
    set('batExplanation','Record a voltage measurement after at least 2 hours resting to get a rough remaining-charge estimate. Live voltage while driving is not reliable for this.');
  }
  set('batHistoryNote',samples.length?`${samples.length} logged intervals in selected period · chart shows minimum pack voltage per interval; gaps mean no data.`:
    'No logged readings in this period. Connect USB with the app open to start logging.');
  const parent=$('batCheckHistory');parent.replaceChildren();
  if(!checks.length)parent.textContent='No rested voltage checks saved yet.';
  checks.forEach(row=>{
    const div=document.createElement('div');div.className='battery-check-row';
    const v=document.createElement('b');v.textContent=`${number(row.v,2)} V · ~${estimateSoc(row.v)}%`;
    const date=document.createElement('small');date.textContent=new Date(row.at).toLocaleString();
    div.append(v,date);parent.append(div);
  });
  drawBatteryChart($('batteryChart'),samples);
}
function drawBatteryChart(canvas,rows){
  const ctx=canvas.getContext('2d'),w=canvas.width,h=canvas.height;
  ctx.fillStyle='#08151a';ctx.fillRect(0,0,w,h);
  const L=57,R=15,T=28,B=40,W=w-L-R,H=h-T-B;
  if(rows.length<2){ctx.fillStyle='#a9bcc4';ctx.font='18px system-ui';ctx.fillText('Waiting for battery samples',L+20,T+H/2);return;}
  const vals=rows.map(x=>x.v).filter(v=>typeof v==='number'&&Number.isFinite(v));
  if(vals.length<2)return;
  const min=Math.floor((Math.min(...vals)-.3)*2)/2,max=Math.ceil((Math.max(...vals)+.3)*2)/2;
  const start=rows[0].at,end=rows[rows.length-1].at,span=Math.max(end-start,1);
  ctx.font='17px system-ui';ctx.textAlign='right';
  for(let i=0;i<=4;i++){
    const v=max-i*(max-min)/4,y=T+i*H/4;
    ctx.strokeStyle='#28454e';ctx.beginPath();ctx.moveTo(L,y);ctx.lineTo(L+W,y);ctx.stroke();
    ctx.fillStyle='#9bb7bd';ctx.fillText(v.toFixed(1)+'V',L-8,y+5);
  }
  ctx.strokeStyle='#00e5d4';ctx.lineWidth=3;ctx.beginPath();
  rows.forEach((row,i)=>{
    const x=L+(row.at-start)/span*W,y=T+(max-row.v)/(max-min)*H;
    if(i===0)ctx.moveTo(x,y);else {
      if(row.at-rows[i-1].at>120000)ctx.moveTo(x,y);else ctx.lineTo(x,y);
    }
  });ctx.stroke();
  ctx.fillStyle='#9bb7bd';ctx.font='15px system-ui';ctx.textAlign='left';
  ctx.fillText(new Date(start).toLocaleString(),L,h-12);
  ctx.textAlign='right';ctx.fillText(new Date(end).toLocaleString(),w-R,h-12);
}
$('batRefresh').onclick=refreshBattery;
for(const btn of document.querySelectorAll('.battery-period button'))btn.onclick=()=>{
  document.querySelectorAll('.battery-period button').forEach(b=>b.classList.toggle('current',b===btn));
  batteryState.hours=Number(btn.dataset.hours);refreshBattery();
};
$('batSaveCheck').onclick=()=>{
  const v=Number($('batRestVoltage').value);
  if(!$('batRestVoltage').value.trim()||!Number.isFinite(v)||v<42||v>55){
    set('batRestMessage','Enter a measured pack voltage between 42 and 55 V.');return;
  }
  if(!confirm('Was this voltage measured after at least 2 hours with no driving or charging?'))return;
  if(native('saveBatteryCheck',v,'User-confirmed rested pack voltage')){
    $('batRestVoltage').value='';set('batRestMessage','Rested voltage check saved. Estimate updated.');refreshBattery();
  }else set('batRestMessage','Unable to save measurement.');
};

function discoveryHistory(){try{return JSON.parse(native('discoveryHistory')||'[]');}catch(e){return [];}}
function refreshDiscovery(){const h=discoveryHistory();set('discoveryStatus',h.length?`${h.length} saved snapshots. Latest: ${h[h.length-1].label}`:'No saved snapshots.');}
function onDiscovery(r){
  if(r.error){set('discoveryStatus',r.error);return;}
  set('discoveryStatus',`Captured ${r.blocks.length} regions: ${r.label}`);
  set('discoveryOutput',r.blocks.map(b=>`${b.address}: ${b.error||b.hex}`).join('\n'));
}
$('discoveryCapture').onclick=()=>{if(!state.connected){set('discoveryStatus','Connect USB first.');return;}set('discoveryStatus','Capturing…');native('discover',$('discoveryLabel').value.trim()||'Parked snapshot');};
$('discoveryCompare').onclick=()=>{
  const h=discoveryHistory();if(h.length<2){set('discoveryOutput','Capture two snapshots first.');return;}
  const a=h[h.length-2],b=h[h.length-1];const old=new Map(a.blocks.filter(r=>r.bytes).map(r=>[r.address,r.bytes]));
  const lines=[];
  for(const row of b.blocks){const prev=old.get(row.address);if(!prev||!row.bytes)continue;
    for(let i=0;i<Math.min(prev.length,row.bytes.length);i++)if(prev[i]!==row.bytes[i])lines.push(`${row.address}+${i}: ${prev[i]} → ${row.bytes[i]}`);
  }
  set('discoveryOutput',`${a.label} → ${b.label}\n${lines.length} changed bytes (NOT confirmed sensors)\n`+(lines.join('\n')||'No changes detected.'));
};
$('discoveryCopy').onclick=()=>{
  const box=document.createElement('textarea');box.value=JSON.stringify({format:'RNR discovery read-only v1',snapshots:discoveryHistory()},null,2);
  document.body.append(box);box.select();const ok=document.execCommand('copy');box.remove();
  set('discoveryStatus',ok?'Snapshot JSON copied.':'Clipboard unavailable.');
};
window.RNR={onConnection:updateConnection,onTelemetry,onGps,onTripState,onTrips,onThrottle,onMemory,onDiscovery};

$('connectBtn').onclick=()=>native(state.connected?'disconnect':'connect');
$('gpsBtn').onclick=()=>native('gps');
$('startTripBtn').onclick=()=>native('beginTrip');
$('finishTripBtn').onclick=()=>native('endTrip');
$('refreshTrips').onclick=reloadTrips;
$('readThrottleSettings').onclick=()=>{set('throttleSettingsContent','Reading controller flash…');native('throttleScreen','settings');};
$('readThrottleCurves').onclick=()=>{set('curvePoints','Reading all three stored curve tables…');native('throttleScreen','curves');};
$('sensorFilter').addEventListener('input',renderSensors);
$('memoryRead').onclick=()=>{set('memoryResult','Reading…');native('readRaw',$('memoryAddress').value.trim(),Number($('memoryLength').value));};
$('savePreset').onclick=()=>{
  const name=$('presetName').value.trim();
  if(!name){alert('Give your draft a name.');return;}
  const data={name,createdAt:new Date().toISOString(),writeEnabled:false,notes:'Local draft only; no controller changes permitted.'};
  if(native('savePreset',name,JSON.stringify(data)))loadSavedPresets();
  else alert('Unable to save this draft.');
};
$('copyFaults').onclick=()=>{
  const value=state.faults.length?state.faults.join('\n'):'No active flags / controller disconnected';
  const textarea=document.createElement('textarea');textarea.value=value;document.body.append(textarea);
  textarea.select();try{document.execCommand('copy');}catch(e){console.error(e)}textarea.remove();
};
// Never hold onto a stale GPS speed when satellite data stops updating.
setInterval(()=>{
  if(state.gps && typeof state.gps.atMs==='number' && Date.now()-state.gps.atMs>12000){
    set('speedValue','—');set('tripSpeed','—');$('speedFill').setAttribute('stroke-dasharray','0 427');
    set('gpsStatus','GPS signal stale — waiting for a new fix');
  }
},2000);
window.addEventListener('DOMContentLoaded',()=>{clearTelemetry();loadSavedPresets();});
