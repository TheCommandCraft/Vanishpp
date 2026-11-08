'use strict';
// Issue #38: with Vanish++ hidden, /plugins must look exactly like the server's own /plugins, minus Vanish++.
// The expected output is not hard-coded: it is the server's real /plugins with Vanish++ shown, with the
// Vanish++ entry removed and the counts lowered by one. That keeps the test right on every Paper version.

const KEY = 'hide-announcements.hide-from-plugin-list';

/** Flattens a chat component into what a player sees: text runs with color, decorations, hover text and click. */
function flatten(node, inherited = {}, out = []) {
  if (node == null) return out;
  if (typeof node === 'string') { push(out, node, inherited); return out; }
  if (Array.isArray(node)) { for (const n of node) flatten(n, inherited, out); return out; }
  const style = { ...inherited };
  for (const k of ['color', 'bold', 'italic', 'underlined', 'strikethrough', 'obfuscated']) if (node[k] !== undefined) style[k] = node[k];
  const hover = node.hover_event || node.hoverEvent;
  if (hover) style.hover = plain(hover.value ?? hover.contents ?? hover.text);
  const click = node.click_event || node.clickEvent;
  if (click) style.click = `${click.action}:${click.command ?? click.url ?? click.value ?? ''}`;
  if (node.text !== undefined) push(out, String(node.text), style);
  // NBT chat components store plain strings inside mixed lists as {"": "..."}.
  if (node[''] !== undefined) push(out, String(node['']), style);
  if (node.translate !== undefined) push(out, `{${node.translate}}`, style);
  if (node.extra) flatten(node.extra, style, out);
  return out;
}
function push(out, text, style) {
  if (text === '') return;
  const key = JSON.stringify({ ...style, text: undefined });
  const last = out[out.length - 1];
  if (last && last.key === key) last.text += text; else out.push({ text, key });
}
function plain(node) { return flatten(node).map(t => t.text).join(''); }

/** Sends /plugins and returns the flattened lines it produced. */
async function pluginsOutput(t, bot) {
  bot.chatJson.length = 0;
  bot.chat('/plugins');
  await t.until(() => bot.chatJson.some(j => plain(j).includes('Plugins (')), 5000, '/plugins output');
  await t.wait(800); // every line of the list
  return bot.chatJson.map(j => flatten(j)).filter(line => line.length);
}

/** The shown output with Vanish++ removed, the way the server would print it without the plugin. */
function withoutVanishpp(lines) {
  return lines.map(line => {
    const tokens = line.map(x => ({ ...x }));
    const i = tokens.findIndex(x => x.text === 'Vanishpp');
    if (i >= 0) {
      const sepAfter = tokens[i + 1] && /^,\s*$/.test(tokens[i + 1].text);
      const sepBefore = tokens[i - 1] && /,\s*$/.test(tokens[i - 1].text);
      if (sepAfter) tokens.splice(i, 2);
      else if (sepBefore) { tokens[i - 1].text = tokens[i - 1].text.replace(/,\s*$/, ''); tokens.splice(i, 1); }
      else tokens.splice(i, 1);
    }
    for (const x of tokens) x.text = x.text.replace(/^(Server Plugins|Bukkit Plugins) \((\d+)\)/, (m, a, n) => `${a} (${n - 1})`)
      .replace(/^ \((\d+)\)$/, (m, n) => m); // group sizes printed as separate runs are handled below
    return tokens;
  });
}

/** Drops empty runs and merges neighbours that look the same, so equal-looking output compares equal. */
function normalize(line) {
  const out = [];
  for (const x of line) {
    if (x.text === '') continue;
    const last = out[out.length - 1];
    if (last && last.key === x.key) last.text += x.text; else out.push({ ...x });
  }
  return out;
}
const show = lines => lines.map(l => normalize(l).map(x => `${x.text}⟦${x.key}⟧`).join('')).join('\n');

module.exports = [
  {
    name: '/plugins with Vanish++ hidden looks exactly like the server list without Vanish++',
    async run(t) {
      const [p] = await t.bots(1); // a normal player: no vanishpp.see, so no staff hint line
      await t.config(KEY, false, true);
      const shown = await pluginsOutput(t, p);
      const shownText = shown.map(l => l.map(x => x.text).join('')).join('\n');
      t.check(shownText.includes('Vanishpp') && shownText.includes('LiveTestPaperPlugin') && shownText.includes('Paper Plugins'),
        'control: the real /plugins should list Vanishpp and a Paper plugin, got:\n' + shownText);

      await t.config(KEY, true, true);
      const hidden = await pluginsOutput(t, p);
      const hiddenText = hidden.map(l => l.map(x => x.text).join('')).join('\n');
      t.check(!hiddenText.includes('Vanishpp'), '/plugins still lists Vanishpp while hidden:\n' + hiddenText);

      const expected = fixGroupCount(withoutVanishpp(shown));
      const a = show(expected), b = show(hidden);
      t.check(a === b, `hidden /plugins differs from the server's own list without Vanish++.\nexpected:\n${a}\n\ngot:\n${b}`);
    },
  },
];

/** Lowers the Bukkit group's "(n)" by one, whether Paper prints it in the header run or as its own run. */
function fixGroupCount(lines) {
  for (const line of lines) {
    const header = line.map(x => x.text).join('');
    if (!/^Bukkit Plugins/.test(header)) continue;
    for (const x of line) {
      const m = x.text.match(/\((\d+)\)/);
      if (m && !/^Bukkit Plugins \(\d+\)/.test(x.text)) { x.text = x.text.replace(/\((\d+)\)/, `(${Number(m[1]) - 1})`); break; }
    }
  }
  return lines;
}
