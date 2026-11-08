'use strict';
// Runs one Paper server as a child process and drives it through its console.
// The server folder is reused between runs: Paper's cache, libraries and patched jar stay, so only the
// first run pays for patching. The world, plugins and plugin data are wiped every run.
const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');

const PROPERTIES = {
  'online-mode': 'false',
  'server-ip': '127.0.0.1',
  'level-type': 'minecraft\\:flat',
  'generate-structures': 'false',
  'allow-nether': 'false',
  'spawn-protection': '0',
  'spawn-monsters': 'false',
  'spawn-animals': 'false',
  'difficulty': 'peaceful',
  'view-distance': '4',
  'simulation-distance': '4',
  'max-players': '30',
  'sync-chunk-writes': 'false',
};
const WIPE = ['world', 'world_nether', 'world_the_end', 'plugins', 'logs', 'ops.json', 'usercache.json', 'console.log'];

class Server {
  constructor({ dir, java, paperJar, plugins, port, log }) {
    Object.assign(this, { dir, java, paperJar, plugins, port, log });
    this.lines = [];
    this.waiters = [];
  }

  prepare() {
    fs.mkdirSync(this.dir, { recursive: true });
    for (const name of WIPE) fs.rmSync(path.join(this.dir, name), { recursive: true, force: true });
    fs.mkdirSync(path.join(this.dir, 'plugins'));
    fs.copyFileSync(this.paperJar, path.join(this.dir, 'paper.jar'));
    for (const jar of this.plugins) fs.copyFileSync(jar, path.join(this.dir, 'plugins', path.basename(jar)));
    fs.writeFileSync(path.join(this.dir, 'eula.txt'), 'eula=true\n');
    fs.writeFileSync(path.join(this.dir, 'bukkit.yml'), 'settings:\n  allow-end: false\n');
    const props = { ...PROPERTIES, 'server-port': String(this.port) };
    fs.writeFileSync(path.join(this.dir, 'server.properties'),
      Object.entries(props).map(([k, v]) => `${k}=${v}`).join('\n') + '\n');
  }

  async start(timeoutMs = 240000, { keepData = false } = {}) {
    if (!keepData) this.prepare();
    this.proc = spawn(this.java, ['-Xms1G', '-Xmx2G', '-XX:+UseSerialGC', '-jar', 'paper.jar', 'nogui'], { cwd: this.dir });
    this.logFile = fs.createWriteStream(path.join(this.dir, 'console.log'), { flags: keepData ? 'a' : 'w' });
    let buf = '';
    const onData = chunk => {
      this.logFile.write(chunk);
      buf += chunk.toString();
      let i;
      while ((i = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, i).replace(/\x1b\[[0-9;]*m/g, '');
        buf = buf.slice(i + 1);
        this.lines.push(line);
        if (this.log) this.log(line);
        this.waiters = this.waiters.filter(w => !(w.re.test(line) && (w.resolve(line), true)));
      }
    };
    this.proc.stdout.on('data', onData);
    this.proc.stderr.on('data', onData);
    this.exited = new Promise(res => this.proc.on('exit', res));
    await this.waitFor(/Done \([\d.]+s\)!/, timeoutMs);
  }

  /** Resolves with the first console line matching re that appears after this call. */
  waitFor(re, timeoutMs = 10000) {
    return new Promise((resolve, reject) => {
      const w = { re, resolve: l => { clearTimeout(t); resolve(l); } };
      const t = setTimeout(() => {
        this.waiters = this.waiters.filter(x => x !== w);
        reject(new Error(`timed out waiting for console line ${re}`));
      }, timeoutMs);
      this.waiters.push(w);
    });
  }

  cmd(command) { this.proc.stdin.write(command + '\n'); }

  /** Runs a command and resolves with the first console line matching re that follows it. */
  async ask(command, re, timeoutMs = 5000) {
    const line = this.waitFor(re, timeoutMs);
    this.cmd(command);
    return line;
  }

  /** Lines logged since index `from` that look like plugin errors. */
  errorsSince(from) {
    return this.lines.slice(from).filter(l =>
      /(WARN|ERROR)\]/.test(l) && /exception|error|Vanishpp|ProtocolLib|LiveTestHelper/i.test(l)
      && !/Setup Check|NOT installed|UpdateChecker|offline mode|server is running in offline|has not yet been tested/i.test(l));
  }

  async stop() {
    if (!this.proc || this.proc.exitCode !== null) return;
    this.cmd('stop');
    const killer = setTimeout(() => this.proc.kill('SIGKILL'), 60000);
    await this.exited;
    clearTimeout(killer);
    this.logFile.end();
  }

  /** Stops the server normally and starts it again with the same world, plugins and plugin data. */
  async restart() {
    await this.stop();
    this.waiters = [];
    await this.start(240000, { keepData: true });
  }
}

module.exports = { Server };
