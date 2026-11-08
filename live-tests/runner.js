'use strict';
// Vanish++ live bot tests. One Paper server and one pool of bots serve the whole run.
//   node runner.js --paper <jar> --plugin <jar>... --helper <jar> [--java <bin>] [--port <n>]
//                  [--only <text>] [--mutants] [--verbose]
// --mutants: the Vanish++ jar is a mutant build (see mutate.js). After the normal run, every mutant is
// switched on in turn and the tests that must catch it run again; each of them has to fail.
const path = require('path');
const fs = require('fs');
const mineflayer = require('mineflayer');
const { Server } = require('./lib/server');

const args = process.argv.slice(2);
const opt = (name, def) => { const i = args.indexOf('--' + name); return i >= 0 ? args[i + 1] : def; };
const plugins = args.flatMap((a, i) => (a === '--plugin' ? [args[i + 1]] : []));
const VERSION = '1.21.11';
const PORT = Number(opt('port', '25599'));
const only = opt('only', '');
const verbose = args.includes('--verbose');
const mutationMode = args.includes('--mutants');
const POOL_SIZE = 6;
const GROUND = -60;
const LOBBY = { x: 0, z: 0 };

const wait = ms => new Promise(r => setTimeout(r, ms));
async function until(fn, timeoutMs, what) {
  const end = Date.now() + timeoutMs;
  for (;;) {
    const v = await fn();
    if (v) return v;
    if (Date.now() > end) throw new Error('timed out: ' + what);
    await wait(100);
  }
}
const label = t => `${t.file.replace('.js', '')}: ${t.name}`;

// ---------- bot pool ----------
class Pool {
  constructor(server, defaultRules) {
    this.server = server;
    this.defaultRules = defaultRules;
    this.bots = new Map(); // name -> bot
  }

  async connect(name) {
    const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: VERSION, auth: 'offline' });
    bot.chatLog = []; bot.actionBars = []; bot.rawChat = [];
    bot.on('message', m => bot.chatLog.push(m.toString()));
    bot.chatJson = [];
    bot.on('message', m => bot.chatJson.push(m.json));
    bot.on('actionBar', m => bot.actionBars.push(JSON.stringify(m.json ?? m)));
    // Raw chat packets too: mineflayer's text drops the decoration plugins add with a renderer.
    for (const p of ['player_chat', 'system_chat', 'profileless_chat'])
      bot._client.on(p, d => bot.rawChat.push(p + ' ' + JSON.stringify(d, (k, v) => (typeof v === 'bigint' ? String(v) : v))));
    await new Promise((res, rej) => {
      bot.once('spawn', res);
      bot.once('error', rej);
      bot.once('kicked', r => rej(new Error(`${name} kicked: ${JSON.stringify(r)}`)));
    });
    bot.on('error', () => {});
    this.bots.set(name, bot);
    return bot;
  }

  alive(bot) { return bot && !bot._client.ended && bot.entity; }

  /** Disconnects a bot and connects it again under the same name, like a player leaving and rejoining. */
  async rejoin(bot, { afterMs = 0 } = {}) {
    const name = bot.username;
    const gone = this.server.waitFor(new RegExp(`${name} (left the game|lost connection)`), 10000);
    bot.quit();
    await gone;
    if (afterMs) await wait(afterMs);
    return this.connect(name);
  }

  /** Returns n connected bots, reconnecting any a previous test disconnected. */
  async take(n) {
    if (n > POOL_SIZE) throw new Error(`a test may use at most ${POOL_SIZE} bots`);
    const out = [];
    for (let i = 1; i <= n; i++) {
      const name = 'LtBot' + i;
      let bot = this.bots.get(name);
      if (!this.alive(bot)) bot = await this.connect(name);
      out.push(bot);
    }
    for (const b of out) if (b.game.gameMode !== 'survival') this.server.cmd(`gamemode survival ${b.username}`);
    for (const b of out) await until(() => b.game.gameMode === 'survival', 5000, `${b.username} to be in survival`);
    // Idle pool bots wait as spectators in the lobby: they do not count for sleeping and mobs ignore them.
    const idle = [...this.bots.values()].filter(b => this.alive(b) && !out.includes(b));
    for (const b of idle) this.server.cmd(`gamemode spectator ${b.username}`);
    for (const b of idle) await until(() => b.game.gameMode === 'spectator', 5000, `${b.username} to idle as spectator`);
    return out;
  }

  async state(name) {
    const line = await this.server.ask(`ltprobe state ${name}`, new RegExp(`LTPROBE state ${name} `));
    return Object.fromEntries(line.split(`LTPROBE state ${name} `)[1].trim().split(' ').map(kv => kv.split('=')));
  }

  /** Puts every used bot back to a known clean state and verifies it on the server side. */
  async reset(bots) {
    const s = this.server;
    for (const bot of bots) {
      if (!this.alive(bot)) continue;
      const name = bot.username;
      for (const fn of bot.testListeners || []) bot._client.removeListener(fn.event, fn);
      bot.testListeners = [];
      if ((await this.state(name)).vanished === 'true') {
        s.cmd(`vanish ${name}`);
        await until(async () => (await this.state(name)).vanished === 'false', 5000, `${name} to unvanish`);
      }
      s.cmd(`deop ${name}`);
      s.cmd(`gamemode survival ${name}`);
      s.cmd(`clear ${name}`);
      s.cmd(`effect clear ${name}`);
      s.cmd(`effect give ${name} minecraft:instant_health 1 10 true`);
      s.cmd(`effect give ${name} minecraft:saturation 1 10 true`);
      for (const [rule, value] of Object.entries(this.defaultRules)) s.cmd(`vrules ${name} ${rule} ${value}`);
      s.cmd(`vautovanish ${name} off`);
      await s.ask(`ltprobe unack ${name} auto-vanish-notice`, new RegExp(`LTPROBE unack ${name} .* done`));
      s.cmd(`tp ${name} ${LOBBY.x + 0.5} ${GROUND} ${LOBBY.z + 0.5}`);
    }
    await wait(400);
    for (const bot of bots) {
      if (!this.alive(bot)) continue;
      s.cmd(`effect clear ${bot.username}`);
    }
    for (const bot of bots) {
      if (!this.alive(bot)) continue;
      const st = await until(async () => {
        const x = await this.state(bot.username);
        return x.vanished === 'false' && x.op === 'false' && x.health === '20' && x.effects === '0'
          && x.items === '0' && x.gamemode === 'survival' && x.sleepingIgnored === 'false' && x;
      }, 5000, `${bot.username} to be reset`).catch(async () => { throw new Error(`reset left ${bot.username} in ${JSON.stringify(await this.state(bot.username))}`); });
      void st;
      bot.chatLog.length = 0; bot.actionBars.length = 0; bot.rawChat.length = 0; bot.chatJson.length = 0;
    }
  }
}

function defaultRulesFrom(serverDir) {
  const file = path.join(serverDir, 'plugins', 'Vanishpp', 'config.yml');
  const text = fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : '';
  const block = text.split(/^default-rules:\s*$/m)[1] || '';
  const rules = {};
  for (const line of block.split('\n').slice(1)) {
    const m = line.match(/^\s{2}([a-z_]+):\s*(true|false)\s*$/);
    if (!m) { if (/^\S/.test(line)) break; continue; }
    rules[m[1]] = m[2];
  }
  return rules;
}

// ---------- test context ----------
function makeContext(server, pool, area, used, configChanges) {
  const t = {
    server, wait, until, area, ground: GROUND,
    cmd: c => server.cmd(c),
    check(cond, msg) { if (!cond) throw new Error(msg); },
    /** n clean bots from the pool, all standing in this test's area. */
    async bots(n) {
      const bots = await pool.take(n);
      for (const b of bots) used.add(b);
      return bots;
    },
    async op(bot) {
      await server.ask(`op ${bot.username}`, new RegExp(`Made ${bot.username} a server operator|${bot.username} is already`, 'i'));
      // Vanish++ answers async checks (chat, voice) from a permission cache it refreshes every 10 ticks.
      // Waiting past one refresh keeps the tests about real behaviour instead of that short window.
      await wait(700);
    },
    /** Listens to a raw client packet for this test only; removed again by the reset. */
    listen(bot, event, fn) {
      fn.event = event;
      (bot.testListeners ||= []).push(fn);
      bot._client.on(event, fn);
    },
    /** Teleports a bot to an offset inside this test's area and waits until its client is there. */
    async place(bot, dx = 0, dz = 0) {
      const x = area.x + dx + 0.5, z = area.z + dz + 0.5;
      server.cmd(`tp ${bot.username} ${x} ${GROUND} ${z} 0 0`);
      await until(() => bot.entity && Math.abs(bot.entity.position.x - x) < 0.6 && Math.abs(bot.entity.position.z - z) < 0.6,
        8000, `${bot.username} to arrive at ${x},${z}`);
    },
    /** The bot leaves and joins again; returns the new bot. Waits until the join is fully handled. */
    async rejoin(bot, opts) {
      const fresh = await pool.rejoin(bot, opts);
      used.add(fresh);
      await wait(1500); // the join applies saved state and auto-vanish in stages
      return fresh;
    },
    /** Restarts the server (a normal stop, then start with the same data). Every bot is disconnected. */
    async restartServer() {
      await server.restart();
      server.cmd('gamerule advance_time false');
      server.cmd('gamerule spawn_mobs false');
      server.cmd(`forceload add ${LOBBY.x - 8} ${LOBBY.z - 8} ${LOBBY.x + 8} ${LOBBY.z + 8}`);
      server.cmd(`forceload add ${area.x - 16} ${area.z - 16} ${area.x + 16} ${area.z + 16}`);
    },
    async isVanished(bot) { return (await pool.state(bot.username)).vanished === 'true'; },
    /** Toggles vanish through the console and waits until the server reports the new state. */
    async vanish(bot, on = true) {
      server.cmd(`vanish ${bot.username}`);
      await until(async () => (await t.isVanished(bot)) === on, 5000, `${bot.username} to be ${on ? 'vanished' : 'visible'}`);
      await wait(300); // let the visibility packets reach the clients
    },
    /** Sets a gamerule and fails the test if the server does not confirm it (rule names change between versions). */
    async gamerule(rule, value) {
      await server.ask(`gamerule ${rule} ${value}`, new RegExp(`Gamerule ${rule} is now set to: ${value}`));
    },
    /** Changes a Vanish++ config value for this test; the runner puts `original` back afterwards. */
    async config(key, value, original) {
      configChanges.push([key, original]);
      await server.ask(`vconfig ${key} ${value} --confirm`, new RegExp(`${key.replace(/[.]/g, '\\.')}.*${value}`), 5000);
    },
    /** Asks the server (not a client, which may predict) whether the block at x y z is `block`. */
    async blockIs(x, y, z, block) {
      const line = await server.ask(`execute if block ${x} ${y} ${z} ${block}`, /Test (passed|failed)/);
      return /Test passed/.test(line);
    },
    async targets() {
      const end = server.waitFor(/LTPROBE end/);
      const from = server.lines.length;
      server.cmd('ltprobe targets');
      await end;
      return server.lines.slice(from).filter(l => /LTPROBE target /.test(l)).map(l => l.split('LTPROBE target ')[1]);
    },
  };
  return t;
}

async function runTest(server, pool, test, index) {
  const used = new Set();
  const configChanges = [];
  const area = { x: (index + 1) * 200, z: 0 };
  const t = makeContext(server, pool, area, used, configChanges);
  server.cmd(`forceload add ${area.x - 16} ${area.z - 16} ${area.x + 16} ${area.z + 16}`);
  server.cmd(`fill ${area.x - 10} ${GROUND} ${area.z - 10} ${area.x + 10} ${GROUND + 4} ${area.z + 10} minecraft:air`);
  const logFrom = server.lines.length;
  const started = Date.now();
  let err = null;
  let timer;
  try {
    await Promise.race([test.run(t), new Promise((_, rej) => { timer = setTimeout(() => rej(new Error('test timed out')), test.timeout || 60000); })]);
    const errors = server.errorsSince(logFrom);
    if (errors.length) throw new Error('server logged errors:\n      ' + errors.slice(0, 5).join('\n      '));
  } catch (e) {
    err = e.message || String(e);
  } finally {
    clearTimeout(timer);
  }
  for (const [key, original] of configChanges.reverse()) {
    await server.ask(`vconfig ${key} ${original} --confirm`, new RegExp(`${key.replace(/[.]/g, '\\.')}.*${original}`), 5000)
      .catch(e => { err = (err ? err + '\n      ' : '') + 'cleanup: could not restore ' + key + ': ' + e.message; });
  }
  server.cmd('kill @e[type=!player]');
  server.cmd('difficulty peaceful');
  server.cmd('time set 1000');
  server.cmd('gamerule players_sleeping_percentage 100');
  server.cmd('gamerule advance_time false');
  try { await pool.reset([...used]); } catch (e) { err = (err ? err + '\n      ' : '') + 'cleanup: ' + e.message; }
  return { name: label(test), err, secs: ((Date.now() - started) / 1000).toFixed(1) };
}

async function main() {
  const paper = opt('paper');
  const helper = opt('helper');
  if (!paper || !helper || plugins.length === 0) throw new Error('need --paper <jar>, --helper <jar> and at least one --plugin <jar>');
  const files = fs.readdirSync(path.join(__dirname, 'scenarios')).filter(f => f.endsWith('.js')).sort();
  const all = files.flatMap(f => require(path.join(__dirname, 'scenarios', f)).map(t => ({ ...t, file: f })));
  const tests = all.filter(t => !only || label(t).includes(only));

  const server = new Server({
    dir: path.join(__dirname, '.work', 'server'), java: opt('java', 'java'), paperJar: paper,
    plugins: [...plugins, helper], port: PORT, log: verbose ? l => console.log('  [server] ' + l) : null,
  });
  const t0 = Date.now();
  console.log(`Starting Paper ${VERSION} on 127.0.0.1:${PORT} with ${[...plugins, helper].map(p => path.basename(p)).join(', ')}`);
  await server.start();
  const vanishLoaded = server.lines.some(l => /Vanish\+\+ .* enabled/.test(l));
  const plHooked = server.lines.some(l => /\[Vanishpp\] Hooked into ProtocolLib/.test(l));
  console.log(`Server up in ${((Date.now() - t0) / 1000).toFixed(1)}s. Vanish++ enabled: ${vanishLoaded}, ProtocolLib hooked: ${plHooked}\n`);
  if (!vanishLoaded) throw new Error('Vanish++ did not enable');
  server.cmd('gamerule advance_time false');
  server.cmd('gamerule spawn_mobs false');
  server.cmd(`forceload add ${LOBBY.x - 8} ${LOBBY.z - 8} ${LOBBY.x + 8} ${LOBBY.z + 8}`);
  const defaultRules = defaultRulesFrom(server.dir);
  if (Object.keys(defaultRules).length < 5) throw new Error('could not read default-rules from the Vanish++ config: ' + JSON.stringify(defaultRules));
  const pool = new Pool(server, defaultRules);

  const results = [];
  for (let i = 0; i < tests.length; i++) {
    const r = await runTest(server, pool, tests[i], i);
    results.push(r);
    console.log(`${r.err ? 'FAIL' : 'PASS'}  ${r.name} (${r.secs}s)${r.err ? '\n      ' + r.err : ''}`);
  }
  const failed = results.filter(r => r.err);
  console.log(`\n${results.length - failed.length} passed, ${failed.length} failed`);

  // ---------- mutation phase ----------
  const mutantResults = [];
  if (mutationMode) {
    if (failed.length) console.log('\nSkipping mutants: the normal run must pass first.');
    else {
      const { mutants } = require('./mutants');
      // Every test must be proven able to fail by at least one mutant (guard tests excepted).
      for (const test of all.filter(x => !x.guard)) {
        if (!mutants.some(m => m.tests.some(x => label(test).includes(x))))
          mutantResults.push({ m: { id: 'uncovered', what: label(test) }, verdict: 'UNCOVERED', detail: 'no mutant checks this test' });
      }
      const toggle = path.join(server.dir, 'livetest-mutant.txt');
      console.log(`\nMutants: each one breaks one feature; its tests must fail.`);
      for (const m of mutants) {
        const mine = all.filter(t => m.tests.some(x => label(t).includes(x)));
        if (!mine.length) { mutantResults.push({ m, verdict: 'ERROR', detail: 'no test matches ' + JSON.stringify(m.tests) }); continue; }
        const ack = server.waitFor(new RegExp(`LTMUTANT active=${m.id}$`), 5000);
        fs.writeFileSync(toggle, m.id);
        await ack;
        const outcomes = [];
        for (const test of mine) outcomes.push(await runTest(server, pool, test, all.indexOf(test)));
        const off = server.waitFor(/LTMUTANT active=none$/, 5000);
        fs.writeFileSync(toggle, '');
        await off;
        const caught = outcomes.filter(o => o.err && !/^cleanup:/.test(o.err));
        const verdict = caught.length ? 'KILLED' : 'SURVIVED';
        const detail = caught.length ? `${caught[0].name}: ${caught[0].err.split('\n')[0]}` : 'every test still passed: ' + outcomes.map(o => o.name).join(', ');
        mutantResults.push({ m, verdict, detail });
        console.log(`${verdict.padEnd(8)} ${m.id}: ${m.what}\n         ${detail}`);
      }
      const bad = mutantResults.filter(r => r.verdict !== 'KILLED');
      console.log(`\n${mutantResults.length - bad.length}/${mutantResults.length} mutants killed` + (bad.length ? `, not killed: ${bad.map(r => r.m.id).join(', ')}` : ''));
    }
  }

  await server.stop();
  console.log(`Total ${((Date.now() - t0) / 1000).toFixed(0)}s`);
  const summary = process.env.GITHUB_STEP_SUMMARY;
  if (summary) {
    let md = `### Live bot tests\n\n| | Test | Time |\n|---|---|---|\n` +
      results.map(r => `| ${r.err ? '❌' : '✅'} | ${r.name}${r.err ? `<br>\`${String(r.err).split('\n')[0]}\`` : ''} | ${r.secs}s |`).join('\n') + '\n';
    if (mutantResults.length) md += `\n### Mutants\n\n| | Mutant | Breaks |\n|---|---|---|\n` +
      mutantResults.map(r => `| ${r.verdict === 'KILLED' ? '✅' : '❌'} ${r.verdict} | ${r.m.id} | ${r.m.what} |`).join('\n') + '\n';
    fs.appendFileSync(summary, md);
  }
  const mutantsBad = mutantResults.some(r => r.verdict !== 'KILLED') || (mutationMode && failed.length > 0);
  process.exit(failed.length || mutantsBad ? 1 : 0);
}

main().catch(e => { console.error(e); process.exit(2); });
