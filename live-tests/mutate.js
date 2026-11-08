'use strict';
// Builds the mutant test jar: a copy of the plugin source with every mutant from mutants.js built in, all
// switched off until the runner switches one on. Usage: node mutate.js [--mvn <bin>]
// Prints the path of the built jar on the last line.
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');
const { mutants, edits, switchClass } = require('./mutants');

const args = process.argv.slice(2);
const opt = (name, def) => { const i = args.indexOf('--' + name); return i >= 0 ? args[i + 1] : def; };
const repo = path.resolve(__dirname, '..');
const work = path.join(__dirname, '.work', 'mutant-src');

function copyTree(from, to) {
  fs.mkdirSync(to, { recursive: true });
  for (const e of fs.readdirSync(from, { withFileTypes: true })) {
    if (['target', 'node_modules', '.git', '.work', '.cache'].includes(e.name)) continue;
    const a = path.join(from, e.name), b = path.join(to, e.name);
    if (e.isDirectory()) copyTree(a, b); else fs.copyFileSync(a, b);
  }
}

function fail(msg) { console.error(msg); process.exit(1); }

fs.rmSync(work, { recursive: true, force: true });
fs.mkdirSync(work, { recursive: true });
for (const part of ['pom.xml', 'vanishpp-common', 'vanishpp-paper', 'vanishpp-velocity']) {
  const from = path.join(repo, part);
  if (fs.statSync(from).isDirectory()) copyTree(from, path.join(work, part)); else fs.copyFileSync(from, path.join(work, part));
}

for (const m of mutants) {
  if (!edits.some(e => e.replace.includes(`"${m.id}"`))) fail(`mutant ${m.id} is not used by any edit`);
}
for (const e of edits) {
  const file = path.join(work, e.file);
  const text = fs.readFileSync(file, 'utf8');
  const found = text.split(e.find).length - 1;
  if (found !== 1) fail(`${e.file}: expected exactly one match, found ${found}, for:\n${e.find}\nUpdate live-tests/mutants.js for the current code.`);
  fs.writeFileSync(file, text.replace(e.find, () => e.replace));
}
fs.writeFileSync(path.join(work, 'vanishpp-paper/src/main/java/net/thecommandcraft/vanishpp/LiveTestMutant.java'), switchClass);

const build = spawnSync(opt('mvn', 'mvn'), ['-B', '-q', '-DskipTests', 'package', '-pl', 'vanishpp-paper', '-am'], { cwd: work, encoding: 'utf8' });
if (build.status !== 0) fail('mutant build failed:\n' + (build.stdout + build.stderr).split('\n').filter(l => /ERROR/.test(l)).slice(0, 15).join('\n'));
const dir = path.join(work, 'vanishpp-paper', 'target');
const jar = fs.readdirSync(dir).find(f => /^vanishpp-.*\.jar$/.test(f) && !f.startsWith('original-'));
const out = path.join(__dirname, '.work', 'vanishpp-mutants.jar');
fs.copyFileSync(path.join(dir, jar), out);
console.log(`Built ${mutants.length} mutants into one jar:`);
console.log(out);
