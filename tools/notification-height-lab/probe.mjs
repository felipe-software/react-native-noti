import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';

const [serial, session] = process.argv.slice(2);
if (!serial || !session) throw Error('Usage: node probe.mjs SERIAL SESSION');
const out = path.resolve('artifacts/notification-height', serial);
fs.mkdirSync(out, {recursive:true});
const deadline = Date.now() + 90_000;
const cli = '/Users/mac/.t3/userdata/device/bin/agent-device';
const flags = ['--platform','android','--serial',serial,'--config','/Users/mac/.t3/userdata/device/hosts/25bf8e1a2393f1108d37029b.json','--session',session];
const run = (cmd, ...args) => {
  const timeout = Math.min(15_000, deadline - Date.now());
  if (timeout <= 0) throw Error('90-second layout probe deadline');
  const result = spawnSync(cmd, args, {encoding:'utf8', timeout, maxBuffer:5e6});
  if (result.status !== 0) throw Error(result.stderr || result.stdout || String(result.error));
  return result.stdout;
};
const agent = (...args) => run(cli,...args,...flags);
const adb = (...args) => run('/Users/mac/Library/Android/sdk/platform-tools/adb','-s',serial,...args);
const tree = () => agent('snapshot','--raw','--force-full').split('\n').filter(line=>line.startsWith('{')).map(JSON.parse);
const wait = () => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)),0,0,500);
const results = [];
adb('shell','pm','grant','dev.noti.heightlab','android.permission.POST_NOTIFICATIONS');
for (const size of ['640x400','1280x800','1280x1280','640x1600']) {
  adb('shell','cmd','statusbar','collapse');
  agent('open','dev.noti.heightlab');
  wait();
  const button = tree().find(n=>n.label?.toLowerCase()===size && n.kind==='button');
  if (!button) throw Error('Missing probe button '+size);
  agent('click','@'+button.ref);
  wait();
  adb('shell','cmd','statusbar','expand-notifications');
  wait();
  let nodes;
  for (let attempt=0; attempt<3; attempt++) {
    nodes=tree();
    const image=nodes.find(n=>n.label==='Height probe '+size && n.kind==='image' && n.identifier==='android:id/message_text' && n.rect.height>200);
    if (image) break;
    const ids=new Map(nodes.map(n=>[n.index,n]));
    const expand=nodes.filter(n=>n.kind==='button'&&n.visibleToUser&&['Expand','Développer'].includes(n.label)).find(n=>{
      let parent=n;
      while(parent&&!parent.identifier?.endsWith('/expandableNotificationRow')) parent=ids.get(parent.parentIndex);
      if (!parent) return false;
      return nodes.some(child=>child.label?.includes('Height probe')&&(()=>{let p=child;while(p){if(p.index===parent.index)return true;p=ids.get(p.parentIndex);}return false;})());
    });
    if (!expand) throw Error('No expanded probe image or owned expander for '+size);
    agent('click','@'+expand.ref);
    wait();
  }
  const image=nodes.find(n=>n.label==='Height probe '+size && n.kind==='image' && n.identifier==='android:id/message_text' && n.rect.height>200);
  if (!image) throw Error('Missing expanded image '+size);
  const ids=new Map(nodes.map(n=>[n.index,n]));
  let row=image;
  while(row&&!row.identifier?.endsWith('/expandableNotificationRow')) row=ids.get(row.parentIndex);
  results.push({source:size,image:image.rect,row:row?.rect??null});
  fs.writeFileSync(path.join(out,size+'-tree.json'),JSON.stringify(nodes,null,2));
  agent('screenshot',path.join(out,size+'.png'));
  fs.writeFileSync(path.join(out,'results.json'),JSON.stringify({serial,scope:'Static PNG in the same MessagingStyle configuration as live Doom. Layout only; no animation benchmark.',results},null,2)+'\n');
  console.log(size,JSON.stringify(results.at(-1)));
}
adb('shell','cmd','statusbar','collapse');
agent('open','dev.noti.heightlab');
const clear=tree().find(n=>n.kind==='button'&&n.label?.toLowerCase()==='clear probe');
if (clear) agent('click','@'+clear.ref);
