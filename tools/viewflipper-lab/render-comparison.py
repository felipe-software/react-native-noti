#!/usr/bin/env python3
"""Render the retained cross-device measurements as a standalone HTML page."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DATA = json.loads((ROOT / 'docs/viewflipper-device-comparison.json').read_text())
PAGE = '''<!doctype html>
<html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>ViewFlipper device comparison</title>
<style>
*{box-sizing:border-box}body{margin:0;background:transparent;color:var(--foreground,#20252b);font:14px/1.5 var(--font-sans,system-ui)}p{margin:0 0 14px}.lead{font-size:21px;font-weight:620;line-height:1.35;letter-spacing:-.35px}.muted,small{color:var(--muted-foreground,#64707c)}.note{font-size:12px}.block{border-top:1px solid var(--border,#dce2e5);margin-top:22px;padding-top:18px}.label{font-weight:620;margin-bottom:8px}.controls{display:flex;gap:7px;flex-wrap:wrap;margin:12px 0 17px}button{font:inherit;padding:7px 12px;border:1px solid var(--border,#ccd1d6);border-radius:6px;background:var(--card,#fff);color:inherit;cursor:pointer}button[aria-pressed=true]{background:var(--primary,#294956);color:var(--primary-foreground,#fff);border-color:transparent}button:focus-visible,summary:focus-visible{outline:2px solid var(--ring,#4e8aa5);outline-offset:3px}.row{margin:12px 0 17px}.numbers{display:flex;justify-content:space-between;gap:12px;align-items:baseline;font-variant-numeric:tabular-nums}.numbers strong{font-weight:600;white-space:nowrap}.track{height:12px;border-radius:4px;background:var(--muted,#e4e9ed);overflow:hidden;margin-top:7px}.fill{height:100%;width:0}.emulator{background:var(--chart-1,#477f9e)}.xiaomi{background:var(--chart-2,#8a6ba3)}.latency{display:block;font-size:12px;margin-top:5px}table{width:100%;border-collapse:collapse;font-size:13px;table-layout:fixed}td,th{text-align:left;vertical-align:top;padding:10px 7px;border-bottom:1px solid var(--border,#dce2e5);overflow-wrap:break-word}th{font-weight:500;color:var(--muted-foreground,#64707c);font-size:12px}td:first-child,th:first-child{padding-left:0;width:40%}td:last-child,th:last-child{padding-right:0}summary{cursor:pointer;font-weight:600;margin-bottom:12px}a{color:var(--primary,#2563eb);text-underline-offset:3px}.scope{margin:22px 0 0;font-size:12px;color:var(--muted-foreground,#64707c)}@media(max-width:480px){body{font-size:13px}.lead{font-size:19px}td,th{padding:10px 5px;font-size:12px}.numbers{gap:7px}button{padding:7px 10px}}
</style>
<p class="lead">The Xiaomi ran the frame, URI and XML animation paths.</p>
<p class="muted"><strong>86 of 87 probe outcomes matched.</strong> The same producer APK ran on a Redmi Note 12 Pro 5G with Android 14 / HyperOS 2 and an Android 16 emulator. The remaining probe found a newer API unavailable on the phone.</p>
<table><thead><tr><th>Native observation</th><th>Android 16 emulator</th><th>Xiaomi Android 14</th></tr></thead><tbody>
<tr><td>512 direct flipper children</td><td>Accepted</td><td>Accepted</td></tr>
<tr><td>Nested RemoteViews wrappers</td><td>10 accepted;<br>11 rejected</td><td>10 accepted;<br>11 rejected</td></tr>
<tr><td>Notification bitmap samples</td><td>4.72 MB retained;<br>5.24 MB stripped</td><td>Same</td></tr>
<tr><td>Widget bitmap budget</td><td>15,552,000 bytes</td><td>15,552,000 bytes</td></tr>
<tr><td>150-item widget collection</td><td>Two active views</td><td>Two active views</td></tr>
<tr><td>Public DrawInstructions API</td><td>Rendered</td><td>Class absent</td></tr>
</tbody></table>
<p class="note muted" style="margin-top:10px">512 is a tested lower bound, not a maximum. Expected rejections were observed on both devices; neither run had an expectation mismatch.</p>
<div class="block">
<p class="label">Selected-child timing in the local host</p>
<p class="note muted">Choose the requested flip interval. The phone's display was in 120 Hz mode. These short Choreographer samples measure child selection, not visibly presented images.</p>
<div class="controls" id="intervals" role="group" aria-label="Flip interval in milliseconds"></div>
<div id="timing" aria-live="polite"></div>
<p class="note muted">At 33 ms, earlier notification recordings measured 19.9 changes/s on the emulator and 27.1/s on the Xiaomi. Those capture samples cannot establish either display's maximum.</p>
</div>
<div class="block">
<p class="label">Notification updates delivered in eight seconds</p>
<p class="note muted">Choose calls per second. A separately bound observer matched unique sequences to the actual sender timestamps.</p>
<div class="controls" id="rates" role="group" aria-label="Offered notification calls per second"></div>
<div id="delivery" aria-live="polite"></div>
<p class="note muted">Both devices logged rate shedding. Listener receipt proves posting, not screen presentation. One short trial does not define a sustained update ceiling.</p>
</div>
<div class="block">
<p><strong>Playback survived producer exit on the Xiaomi.</strong> The retained recording showed 244 changes in 8.43 seconds while the app PID was absent. This verifies playback of preloaded frames.</p>
<p class="note muted">The phone accepted a widget pin request, but no launcher placement was observed. Its private widget host was tested; actual MIUI launcher playback remains unverified.</p>
</div>
<details class="block"><summary>Source interpretation and measurement scope</summary>
<p class="note">The AOSP baselines differ in rate-estimator smoothing and enqueue accounting. They support version-aware investigation; they do not prove that Xiaomi's proprietary implementation exactly matches AOSP.</p>
<p class="note"><a href="https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/services/core/java/com/android/server/notification/RateEstimator.java">Android 14 estimator</a> · <a href="https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/core/java/android/service/notification/RateEstimator.java">Android 16 estimator</a> · <a href="https://developer.android.com/reference/android/widget/RemoteViews.DrawInstructions">DrawInstructions API</a></p>
<p class="note muted">The native comparison used the same APK. Notification video and delivery comparisons use separately retained trials. Hardware, refresh rate, OS and OEM software all differ; this is not an isolated OEM experiment.</p>
</details>
<p class="scope">Observed on two devices on 28 October 2026. The visual design space is broad; payload, API and delivery boundaries still apply.</p>
<script>
const data=__DATA__;
function controls(id,values,label,choose){values.forEach((value,index)=>{const button=document.createElement('button');button.type='button';button.textContent=label(value);button.onclick=()=>choose(index);document.getElementById(id).appendChild(button)})}
function selected(id,index){document.querySelectorAll('#'+id+' button').forEach((button,i)=>button.setAttribute('aria-pressed',String(i===index)))}
function row(name,kind,value,max,description,detail){const box=document.createElement('div');box.className='row';const numbers=document.createElement('div');numbers.className='numbers';const label=document.createElement('span');label.textContent=name;const count=document.createElement('strong');count.textContent=description;numbers.append(label,count);const track=document.createElement('div');track.className='track';track.setAttribute('role','img');track.setAttribute('aria-label',name+': '+description);const fill=document.createElement('div');fill.className='fill '+kind;fill.style.width=Math.min(100,100*value/max)+'%';track.append(fill);box.append(numbers,track);if(detail){const small=document.createElement('small');small.className='latency';small.textContent=detail;box.append(small)}return box}
function timing(index){selected('intervals',index);const result=data.localChildSelectionTiming[index];document.getElementById('timing').replaceChildren(row('Emulator','emulator',result.emulatorChangesPerSecond,130,result.emulatorChangesPerSecond.toFixed(1)+' selections/s'),row('Xiaomi','xiaomi',result.xiaomiChangesPerSecond,130,result.xiaomiChangesPerSecond.toFixed(1)+' selections/s'))}
function delivery(index){selected('rates',index);const rows=[data.emulatorDelivery[index],data.xiaomiDelivery[index]];document.getElementById('delivery').replaceChildren(...rows.map((r,i)=>row(i?'Xiaomi':'Emulator',i?'xiaomi':'emulator',r.uniqueSequences,r.offered,r.uniqueSequences+' / '+r.offered+' posted','Median send → listener '+r.postedToListenerMedianMs+' ms · '+(r.offered-r.uniqueSequences)+' missing sequences')))}
controls('intervals',data.localChildSelectionTiming,r=>r.intervalMs+' ms',timing);controls('rates',data.emulatorDelivery,r=>r.requestedPerSecond+'/s',delivery);timing(0);delivery(3);
console.assert(data.sameOutcomeCount===86,'Unexpected matched count');console.assert(data.emulator.expectationMismatches.length===0&&data.xiaomi.expectationMismatches.length===0,'Probe mismatch');console.log(JSON.stringify({matchingOutcomes:data.sameOutcomeCount,timingChoices:data.localChildSelectionTiming.length,deliveryChoices:data.emulatorDelivery.length}));
</script></html>'''

PAGE = PAGE.replace('__DATA__', json.dumps(DATA).replace('</', '<\\/'))
path = ROOT / 'artifacts/viewflipper-limits/device-comparison.html'
path.parent.mkdir(parents=True, exist_ok=True)
path.write_text(PAGE)
print(path)
