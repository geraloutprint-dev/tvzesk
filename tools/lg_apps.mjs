// Lists an LG TV's apps for TVs that won't hand them to Free My TV itself (webOS 3: listLaunchPoints is denied to
// Developer Mode apps and the remote control API refuses file:// pages). The computer asks the TV's remote control
// API (SSAP, port 3000) instead, as LG's phone app does; the TV asks once to allow it, and the key it gives back is
// kept in ~/.ssh/freemytv_ssap_<IP>. The list goes to webos-apps.local.json, which webos/build.py builds in.
//
//     node tools/lg_apps.mjs 192.168.1.50     # then python3 webos/build.py and python3 webos/install.py <IP>
//
// Run it again after installing or removing apps on the TV.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const ip = process.argv[2];
if (!/^\d{1,3}(\.\d{1,3}){3}$/.test(ip || '')) { console.error('Usage: node tools/lg_apps.mjs <TV IP>'); process.exit(1); }
const root = path.dirname(path.dirname(new URL(import.meta.url).pathname));
const out = path.join(root, 'webos-apps.local.json');
const keyFile = path.join(os.homedir(), '.ssh', 'freemytv_ssap_' + ip.replace(/\./g, '_'));
const key = fs.existsSync(keyFile) ? fs.readFileSync(keyFile, 'utf8').trim() : null;
const ws = new WebSocket(`ws://${ip}:3000`);

ws.onopen = () => {
  const payload = { forcePairing: false, pairingType: 'PROMPT',
    manifest: { manifestVersion: 1, appVersion: '1.0', permissions: ['READ_INSTALLED_APPS'] } };
  if (key) payload['client-key'] = key;
  ws.send(JSON.stringify({ type: 'register', id: 'register', payload }));
  if (!key) console.log('On the TV, choose Allow ...');
};
ws.onmessage = e => {
  const m = JSON.parse(e.data);
  if (m.type === 'registered') {
    fs.writeFileSync(keyFile, m.payload['client-key'], { mode: 0o600 });
    ws.send(JSON.stringify({ type: 'request', id: 'list', uri: 'ssap://com.webos.applicationManager/listLaunchPoints' }));
  } else if (m.id === 'list') {
    saveList((m.payload && m.payload.launchPoints) || []).then(() => process.exit(0));
  } else if (m.type === 'error') {
    console.error('The TV said: ' + (m.error || JSON.stringify(m)));
    process.exit(1);
  }
};
// The icons' addresses on the API only last while this connection does, so they're downloaded into
// webos-icons.local/ and built in with the list.
async function saveList(points) {
  const icons = path.join(root, 'webos-icons.local');
  fs.rmSync(icons, { recursive: true, force: true });
  fs.mkdirSync(icons);
  const apps = [];
  for (const p of points) {
    let icon = '';
    const url = p.largeIcon || p.icon;
    if (url) {
      try {
        const res = await fetch(url);
        if (res.ok) {
          const name = p.id.replace(/[^\w.-]/g, '_') + path.extname(new URL(url).pathname).toLowerCase();
          fs.writeFileSync(path.join(icons, name), Buffer.from(await res.arrayBuffer()));
          icon = 'appicons/' + name;
        }
      } catch (e) {}
    }
    apps.push({ id: p.id, title: p.title || p.id, icon, color: p.bgColor || p.iconColor || '' });
  }
  fs.writeFileSync(out, JSON.stringify(apps, null, 1) + '\n');
  console.log(`${apps.length} apps (${apps.filter(a => a.icon).length} icons) in webos-apps.local.json: ` +
    apps.map(a => a.title).join(', '));
}

ws.onerror = () => { console.error(`Couldn't reach the TV at ${ip}:3000.`); process.exit(1); };
setTimeout(() => { console.error('No answer in 90 s: was the prompt on the TV missed?'); process.exit(1); }, 90000);
