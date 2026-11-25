'use strict';
// Live proxy tests (see proxy.sh): Velocity on 127.0.0.1:30100 with the backends lobby (30101) and pvp (30102).
// Bots join through Velocity exactly like players do.
const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');
const mineflayer = require('mineflayer');
const { Server } = require('./lib/server.js');

const args = process.argv.slice(2);
const opt = name => { const i = args.indexOf(name); return i >= 0 ? args[i + 1] : undefined; };
const verbose = args.includes('--verbose');
const [PROXY_PORT, LOBBY_PORT, PVP_PORT] = [30100, 30101, 30102];
const SECRET = 'vanishpp-live-tests';
const work = path.resolve('.work/proxy');
const sleep = ms => new Promise(r => setTimeout(r, ms));

function startVelocity() {
  const dir = path.join(work, 'velocity');
  fs.rmSync(path.join(dir, 'plugins'), { recursive: true, force: true });
  fs.mkdirSync(path.join(dir, 'plugins'), { recursive: true });
  fs.copyFileSync(opt('--velocity'), path.join(dir, 'velocity.jar'));
  fs.copyFileSync(opt('--velocity-plugin'), path.join(dir, 'plugins', path.basename(opt('--velocity-plugin'))));
  fs.writeFileSync(path.join(dir, 'forwarding.secret'), SECRET);
  fs.writeFileSync(path.join(dir, 'velocity.toml'), `config-version = "2.7"
bind = "127.0.0.1:${PROXY_PORT}"
online-mode = false
force-key-authentication = false
player-info-forwarding-mode = "modern"
forwarding-secret-file = "forwarding.secret"
[servers]
lobby = "127.0.0.1:${LOBBY_PORT}"
pvp = "127.0.0.1:${PVP_PORT}"
try = ["lobby"]
[forced-hosts]
[advanced]
login-ratelimit = 0
`);
  const proc = spawn(opt('--velocity-java'), ['-Xmx256M', '-jar', 'velocity.jar'], { cwd: dir });
  const log = fs.createWriteStream(path.join(dir, 'console.log'));
  const lines = [];
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error('Velocity did not start')), 60000);
    const onData = chunk => {
      log.write(chunk);
      for (const l of chunk.toString().split('\n')) {
        lines.push(l);
        if (verbose && l) console.log('[velocity]', l);
        if (/Done \(/.test(l)) { clearTimeout(t); resolve({ proc, lines, log }); }
      }
    };
    proc.stdout.on('data', onData);
    proc.stderr.on('data', onData);
    proc.on('exit', code => { clearTimeout(t); reject(new Error(`Velocity exited (${code}):\n` + lines.slice(-5).join('\n'))); });
  });
}

async function startBackend(name, port) {
  const dir = path.join(work, name);
  const server = new Server({ dir, java: opt('--java'), paperJar: opt('--paper'), plugins: [opt('--plugin')], port,
    log: verbose ? l => console.log(`[${name}]`, l) : null });
  fs.mkdirSync(path.join(dir, 'config'), { recursive: true });
  fs.writeFileSync(path.join(dir, 'config', 'paper-global.yml'),
    `proxies:\n  velocity:\n    enabled: true\n    online-mode: false\n    secret: ${SECRET}\n`);
  process.env.LT_XMX = process.env.LT_XMX || '1G';
  await server.start(); // writes Vanish++'s default config
  const configFile = path.join(dir, 'plugins', 'Vanishpp', 'config.yml');
  const config = fs.readFileSync(configFile, 'utf8')
    .replace(/^(proxy:[\s\S]*?\n  enabled:) false/m, '$1 true')
    .replace(/^(proxy:[\s\S]*?\n  server-name:) ""/m, `$1 "${name}"`);
  if (!/^proxy:[\s\S]*?\n  enabled: true/m.test(config)) throw new Error('could not enable proxy mode in ' + configFile);
  await server.restart({ files: { 'plugins/Vanishpp/config.yml': config } });
  return server;
}

/** A bot that joins through Velocity. `kicked` holds the reason if the proxy or a backend kicked it. */
async function join(username) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: PROXY_PORT, username, auth: 'offline', version: opt('--bot-version') });
  bot.kicked = null;
  bot.on('kicked', reason => { bot.kicked = typeof reason === 'string' ? reason : JSON.stringify(reason); });
  bot.on('error', () => {});
  bot.once('end', () => { bot.ended = true; });
  await new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error(`${username} did not spawn`)), 30000);
    bot.once('spawn', () => { clearTimeout(t); resolve(); });
    bot.once('end', () => { clearTimeout(t); reject(new Error(`${username} could not join: ${bot.kicked}`)); });
  });
  return bot;
}
const quit = bot => new Promise(r => { if (bot.ended) return r(); bot.once('end', r); bot.quit(); });

/** The backend answers a join within five seconds or kicks; staying longer than that is the check. */
async function staysOnline(bot, ms = 7000) {
  await sleep(ms);
  if (bot.kicked) throw new Error(`${bot.username} was kicked: ${bot.kicked}`);
}

async function until(fn, ms, what) {
  const end = Date.now() + ms;
  while (Date.now() < end) { if (await fn()) return; await sleep(200); }
  throw new Error(`timed out waiting for ${what}`);
}

async function switchTo(bot, server, backend) {
  const line = backend.waitFor(new RegExp(`${bot.username}\\[/127\\.0\\.0\\.1:\\d+\\] logged in`), 15000);
  bot.chat(`/server ${server}`);
  await line;
}

const tests = [
  ['the first join after the backend starts is answered', async () => {
    const bot = await join('LtFirst');
    try { await staysOnline(bot); } finally { await quit(bot); }
  }],
  // RFer's ticket: Velocity registers the channel only after the backend's join, so a lone joiner could not carry
  // the join query and was kicked with "Proxy vanish state is unavailable" once the handshake was done.
  ['joining an empty backend again is answered (nobody else online to carry the query)', async () => {
    for (let i = 0; i < 2; i++) {
      const bot = await join('LtAlone');
      try { await staysOnline(bot); } finally { await quit(bot); }
      await sleep(500);
    }
  }],
  ['a vanished player switching to an empty backend arrives online and still hidden', async ({ lobby, pvp }) => {
    lobby.cmd('op LtStaff'); pvp.cmd('op LtStaff');
    const staff = await join('LtStaff');
    let viewer;
    try {
      await staysOnline(staff); // lobby is empty here too: the join must be answered first
      const vanished = lobby.waitFor(/LtStaff has vanished/, 5000);
      staff.chat('/vanish');
      await vanished;
      await switchTo(staff, 'pvp', pvp);
      await staysOnline(staff);
      viewer = await join('LtViewer');
      await switchTo(viewer, 'pvp', pvp);
      await sleep(2000);
      if (viewer.players.LtStaff) throw new Error('the staff member is visible in the tab list on pvp after the switch');
      staff.chat('/vanish'); // control: the viewer's tab list can show the staff member
      await until(() => viewer.players.LtStaff, 5000, 'the viewer to see the staff member after unvanishing');
      if (staff.kicked || viewer.kicked) throw new Error(`kicked: ${staff.kicked || viewer.kicked}`);
    } finally {
      await quit(staff);
      if (viewer) await quit(viewer);
    }
  }],
];

(async () => {
  fs.mkdirSync(work, { recursive: true });
  let velocity, lobby, pvp, failed = 0;
  try {
    [lobby, pvp] = await Promise.all([startBackend('lobby', LOBBY_PORT), startBackend('pvp', PVP_PORT)]);
    velocity = await startVelocity();
    for (const [name, run] of tests) {
      const from = [lobby.lines.length, pvp.lines.length];
      try {
        await run({ lobby, pvp });
        const errors = [...lobby.errorsSince(from[0]), ...pvp.errorsSince(from[1])];
        if (errors.length) throw new Error('server errors:\n      ' + errors.join('\n      '));
        console.log(`  PASS  ${name}`);
      } catch (e) {
        failed++;
        console.log(`  FAIL  ${name}\n        ${e.message}`);
      }
    }
  } catch (e) {
    failed++;
    console.log(`  ERROR ${e.stack}`);
  } finally {
    if (velocity) velocity.proc.kill();
    await Promise.all([lobby?.stop(), pvp?.stop()]);
  }
  console.log(failed ? `${failed} proxy test(s) failed` : `all ${tests.length} proxy tests passed`);
  process.exit(failed ? 1 : 0);
})();
