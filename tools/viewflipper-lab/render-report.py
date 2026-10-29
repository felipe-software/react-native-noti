#!/usr/bin/env python3
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DATA = json.loads((ROOT / 'docs/viewflipper-limits-results.json').read_text())
ROWS = [{key: row[key] for key in ('name', 'expected', 'outcome', 'matchesExpectation')} | {
    'detail': row.get('rootCause') or row.get('error') or row.get('value')
} for row in DATA['results'] if row['name'] != 'methods/remotable-inventory']
PAGE = '''<!doctype html>
<html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>ViewFlipper investigation</title>
<style>
*{box-sizing:border-box}body{margin:0;color:var(--foreground,#20252b);font-family:var(--font-sans,system-ui);font-size:14px;line-height:1.55;background:transparent}p{margin:0 0 16px}.lead{font-size:23px;line-height:1.3;letter-spacing:-.5px;font-weight:650;max-width:650px}.muted,small{color:var(--muted-foreground,#64707c)}.facts{display:grid;grid-template-columns:repeat(3,1fr);gap:14px;margin:22px 0}.fact{border-top:2px solid var(--border,#b7c4ce);padding-top:12px}.value{display:block;font-size:30px;letter-spacing:-1px;line-height:1.2}.label{display:block;font-size:12px;margin:7px 0}.section{margin:26px 0 12px;font-size:16px;font-weight:650}.controls{display:flex;gap:8px;flex-wrap:wrap;margin:12px 0}button,select,input{font:inherit;border:1px solid var(--border,#ccd1d6);border-radius:6px;background:var(--card,#fff);color:inherit;padding:7px 11px}button{cursor:pointer}button[aria-pressed=true]{background:var(--primary,#294956);color:var(--primary-foreground,#fff);border-color:transparent}button:focus-visible,summary:focus-visible,input:focus-visible{outline:2px solid var(--ring,#4e8aa5);outline-offset:3px}.bar{position:relative;height:24px;background:var(--muted,#e4e9ed);border-radius:4px;overflow:hidden;margin:12px 0}.fill{height:100%;background:var(--chart-1,#488a9e);transition:width .2s}.numbers{display:flex;justify-content:space-between;gap:14px;font-variant-numeric:tabular-nums}.note{font-size:12px;margin-top:10px}table{width:100%;border-collapse:collapse;font-size:13px}td,th{text-align:left;vertical-align:top;padding:12px 8px;border-bottom:1px solid var(--border,#dce2e5)}th{font-size:12px;font-weight:500;color:var(--muted-foreground,#64707c)}td:first-child,th:first-child{padding-left:0}td:last-child,th:last-child{padding-right:0}td:first-child{width:39%}a{color:var(--primary,#2563eb);text-underline-offset:3px}.panel{border-top:1px solid var(--border,#dce2e5);margin-top:24px;padding-top:20px}.flow{display:grid;grid-template-columns:1fr auto 1fr auto 1fr;align-items:center;gap:8px;margin:16px 0;font-size:12px}.flow span{padding:12px;background:var(--secondary,#edf1f3);border-radius:6px}.flow b{display:block;font-size:14px}summary{cursor:pointer;font-weight:600;padding:12px 0}.rows{max-height:390px;overflow:auto}.probe{border-bottom:1px solid var(--border,#dce2e5);padding:10px 0}.probe summary{padding:0;display:flex;justify-content:space-between;gap:12px;font-weight:450;overflow-wrap:anywhere}.outcome{color:var(--muted-foreground,#64707c);font-size:11px;white-space:nowrap}.probe pre{font-family:var(--font-mono,monospace);font-size:11px;white-space:pre-wrap;overflow-wrap:anywhere;max-height:180px;overflow:auto}.sources{margin:18px 0 8px;font-size:12px}.screens{display:grid;grid-template-columns:1fr 1fr;gap:14px}.screens img{width:100%;height:280px;object-fit:cover;object-position:center 43%;border-radius:8px}.caption{font-size:12px;margin-top:7px} @media(max-width:480px){.lead{font-size:21px}.facts{gap:10px}.value{font-size:25px}.flow{grid-template-columns:1fr;gap:4px}.flow i{display:none}.screens img{height:210px}td,th{padding:10px 5px}}
</style>
<p class="lead">A vast visual design space, with measurable Android boundaries.</p>
<p class="muted">87 native probes on Android 16, four posting-rate trials, and real notification and launcher recordings. Generated imagery is broadly expressive; transport, execution and input still have constraints.</p>
<div class="facts"><div class="fact"><span class="value">512</span><span class="label">direct children inflated</span><small>A tested lower bound, not a maximum.</small></div><div class="fact"><span class="value">5 MB</span><span class="label">notification bitmap strip default</span><small>URI frames move pixels out of this accounting.</small></div><div class="fact"><span class="value">119 B</span><span class="label">host-evaluated vector document</span><small>Motion continued after producer exit.</small></div></div>
<p class="section">Calls are not delivered updates</p>
<p>Choose the offered rate. Each phase ran for eight seconds; a separate observer recorded unique posted sequences.</p>
<div class="controls" id="rates" aria-label="Offered updates per second"></div>
<div class="bar" role="img" id="bar"><div class="fill" id="fill"></div></div>
<div class="numbers"><strong id="received"></strong><span class="muted" id="offered"></span></div>
<p class="note muted" id="lag"></p>
<p class="note">Rate-shedding logs corroborated the missing sequences. Listener receipt measures posting, not screen presentation. <a href="https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/services/core/java/com/android/server/notification/NotificationManagerService.java#9027">Android implementation</a></p>
<p class="section">The boundaries worth designing around</p>
<table><thead><tr><th>Experiment</th><th>Observed result</th></tr></thead><tbody>
<tr><td>Nested RemoteViews parcels</td><td>10 wrappers accepted; 11 rejected. A separate 32-level XML hierarchy inflated.</td></tr>
<tr><td>Notification bitmap payload</td><td>4.72 MB retained; 5.24 MB stripped from the custom view.</td></tr>
<tr><td>Widget bitmap payload</td><td>12.58 MB accepted; 16.78 MB rejected against a 15.552 MB device budget.</td></tr>
<tr><td>96 URI frames</td><td>16.64 KB parcel; 7.37 MB decoded ARGB pixels. Real SystemUI playback worked.</td></tr>
<tr><td>150-item widget collection</td><td>Two active child views. The collection adapter did not bind in a notification host.</td></tr>
<tr><td>Hidden flipper</td><td>GONE still advanced while the window was visible. Detachment stopped advancement.</td></tr>
<tr><td>Legacy animation input</td><td>A translated button still received touches at its original bounds.</td></tr>
</tbody></table>
<p class="note muted">The 12-child library guard is not an Android limit. Recording samples showed about 29 changes/s at a 1 ms request and 20/s at 33 ms; capture throughput prevents treating these as maximum display rates.</p>
<div class="panel"><p class="section" style="margin-top:0">A new path beyond frame playback</p>
<p>The public DrawInstructions API rendered a sine-driven circle inside SystemUI. XML state animators also produced continuous motion without any flipper.</p>
<div class="flow"><span><b>App</b>119-byte vector document</span><i>→</i><span><b>RemoteViews</b>188-byte parcel</span><i>→</i><span><b>SystemUI</b>time expression → drawing</span></div>
<p class="note muted">The corrected document survived accessibility inspection and kept moving after the app PID exited. A rootless prototype crashed the platform inspector. This experimental path needs version, semantics and lifecycle validation before product integration.</p>
<p class="sources"><a href="https://developer.android.com/reference/android/widget/RemoteViews.DrawInstructions">Public API</a> · <a href="https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/core/java/android/widget/RemoteViews.java#5894">Platform player integration</a></p></div>
<details class="panel"><summary>Explore the native observations</summary><label for="filter">Filter probes</label><div class="controls"><input id="filter" placeholder="e.g. bitmap, method, lifecycle" style="flex:1;min-width:120px"></div><p class="note muted" id="count"></p><div id="probes" class="rows"></div><p class="note muted">The large inherited-method inventory is retained in the JSON report. Expected rejections are successful boundary observations.</p></details>
<details><summary>Emulator evidence</summary><div class="screens"><div><img src="__VECTOR_IMAGE__" alt="A vector circle rendered inside a real Android notification"><p class="caption">Vector drawing in SystemUI.</p></div><div><img src="__WIDGET_IMAGE__" alt="Every Second Pays widget and the frame lab widget on the launcher"><p class="caption">The existing money widget and the lab's flipper.</p></div></div></details>
<p class="note muted" style="margin-top:24px">Scope: one populated API 36 ARM64 emulator. This establishes capabilities and concrete failures, not every OEM's ceiling. The repo contains the native harness, 37 pinned source hashes, raw probe data, and reproduction instructions.</p>
<script>
const delivery=__DELIVERY__;
const probes=__ROWS__;
function choose(index){const r=delivery[index];document.querySelectorAll('#rates button').forEach((b,i)=>b.setAttribute('aria-pressed',String(i===index)));document.getElementById('fill').style.width=(100*r.received/r.offered)+'%';document.getElementById('bar').setAttribute('aria-label',r.received+' of '+r.offered+' notifications posted');document.getElementById('received').textContent=r.received+' posted';document.getElementById('offered').textContent=r.offered+' calls · '+(r.offered-r.received)+' missing';document.getElementById('lag').textContent='Median send → listener: '+r.postedToListenerMedianMs+' ms. A notification assistant was enabled.'}
delivery.forEach((r,i)=>{const b=document.createElement('button');b.textContent=r.requestedPerSecond+'/s';b.onclick=()=>choose(i);document.getElementById('rates').appendChild(b)});
choose(3);
function filter(){const q=document.getElementById('filter').value.toLowerCase();const rows=probes.filter(r=>(r.name+' '+r.outcome).toLowerCase().includes(q));const container=document.getElementById('probes');container.replaceChildren();document.getElementById('count').textContent=rows.length+' observations shown';rows.forEach(r=>{const d=document.createElement('details');d.className='probe';const s=document.createElement('summary');const name=document.createElement('span');name.textContent=r.name;const badge=document.createElement('span');badge.className='outcome';badge.textContent=r.outcome==='rejected'?'Expected rejection':r.expected==='observe'?'Observed':'Accepted';s.append(name,badge);const p=document.createElement('pre');p.textContent=typeof r.detail==='object'?JSON.stringify(r.detail,null,2):String(r.detail);d.append(s,p);container.appendChild(d)})}
document.getElementById('filter').addEventListener('input',filter);filter();
console.log(JSON.stringify({probes:probes.length+1,expectationMismatches:probes.filter(r=>!r.matchesExpectation).length,deliveryPhases:delivery.length}));
</script></html>'''

PAGE = PAGE.replace('__DELIVERY__', json.dumps(DATA['delivery']).replace('</', '<\\/'))
PAGE = PAGE.replace('__ROWS__', json.dumps(ROWS).replace('</', '<\\/'))
PAGE = PAGE.replace('__VECTOR_IMAGE__', str(ROOT / 'artifacts/viewflipper-limits/draw-producer-dead.png'))
PAGE = PAGE.replace('__WIDGET_IMAGE__', str(ROOT / 'artifacts/viewflipper-limits/launcher-widgets.png'))
path = ROOT / 'artifacts/viewflipper-limits/report.html'
path.parent.mkdir(parents=True, exist_ok=True)
path.write_text(PAGE)
print(path)
