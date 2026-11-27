'use strict';
// With the TAB plugin (NEZNAMY), which draws nametags and tab names with its own team and tab packets: staff see
// [V] and [VANISHED] in front of TAB's prefixes, TAB's prefixes come back unchanged, and players who can't see the
// vanished player learn nothing. Vanish++ must not need TAB for any of this (10-teams and 11 run without it); this
// checks the combination. The server restarts with TAB and without it again afterwards.

const path = require('path');
const fs = require('fs');
const TAB_JAR = path.join(__dirname, '..', '.cache', 'TAB-6.2.1.jar');
// Minecraft versions TAB 6.2.1 supports (its Modrinth page); on the others the TAB tests are skipped.
const TAB_SUPPORTS = ['1.21.5', '1.21.6', '1.21.7', '1.21.8', '1.21.9', '1.21.10', '1.21.11', '26.1', '26.1.1', '26.1.2', '26.2', '26.3'];
const needsTab = t => { if (!TAB_SUPPORTS.includes(t.mc)) t.skip(`TAB 6.2.1 doesn't support ${t.mc}`); };
const GROUPS = '_DEFAULT_:\n  tabprefix: "&bTAB "\n  tagprefix: "&bTAB "\n  customtabname: "%player%"\n  tabsuffix: ""\n  tagsuffix: ""\n';

module.exports = [
  {
    name: 'TAB plugin: the server restarts with TAB',
    guard: true, // setup step; its control is the check that TAB really loaded
    timeout: 300000,
    async run(t) {
      needsTab(t);
      await t.restartServer({ extra: [TAB_JAR], files: { 'plugins/TAB/groups.yml': GROUPS } });
      const since = t.server.lines.slice(t.server.startedAt);
      t.check(since.some(l => /\[TAB\] Enabled in/.test(l) || /\[TAB\].*(Enabling|enabled)/i.test(l)), 'TAB did not load');
    },
  },
  {
    name: 'TAB plugin: staff see [V] and [VANISHED] in front of TAB\'s prefixes, which come back unchanged; others learn nothing',
    guard: true, // integration check; the rules it relies on are mutation-tested in 10-teams. Control: TAB's prefixes before vanishing.
    async run(t) {
      needsTab(t);
      const { clientTeams } = require('./10-teams.js');
      const [v, s, n] = await t.bots(3);
      await t.op(s);
      const staff = clientTeams(t, s), normal = clientTeams(t, n);
      const shown = () => (s.players[v.username]?.displayName?.toString() ?? '');
      const prefixOf = m => m.params[m.teamOf[v.username]]?.prefix || '';
      await t.until(() => /TAB /.test(prefixOf(staff)) && /^TAB /.test(shown()), 15000,
        `control: TAB's nametag and tab prefix on ${v.username} (nametag: ${prefixOf(staff)}, tab: "${shown()}")`);
      const tabTeam = staff.teamOf[v.username];
      await t.vanish(v);
      await t.until(() => /\[V\].*TAB /.test(prefixOf(staff)), 5000, `staff to see [V] in front of TAB's nametag prefix (has ${prefixOf(staff)})`);
      await t.until(() => /^\[VANISHED\] ?TAB /.test(shown()), 5000, `staff tab to show "[VANISHED] TAB ${v.username}" (has "${shown()}")`);
      const since = normal.packets.length;
      await t.wait(3000); // TAB refreshes its teams and names on its own timers
      t.check(/\[V\].*TAB /.test(prefixOf(staff)), `TAB took the [V] away again: ${prefixOf(staff)}`);
      t.check(normal.mentions(v.username, since).length === 0, `a normal player got team packets about the vanished player: ${JSON.stringify(normal.mentions(v.username, since))}`);
      await t.vanish(v, false);
      await t.until(() => staff.teamOf[v.username] === tabTeam && /^TAB /.test(shown()), 5000,
        `staff to see TAB's own team and tab name again (team: ${staff.teamOf[v.username]} vs ${tabTeam}, tab: "${shown()}")`);
      for (const [who, m] of [['staff', staff], ['normal', normal]])
        t.check(m.errors.length === 0, `${who}'s client got team packets a real client cannot handle:\n      ${m.errors.join('\n      ')}`);
    },
  },
  {
    // RFer: with TAB as the scoreboard plugin, vanishing did not show the vanish scoreboard (/vsb did).
    name: 'TAB plugin: with TAB\'s own sidebar on, vanishing shows the vanish board and unvanishing gives TAB\'s back',
    guard: true, // integration check (it passed before the fix too); the board rules are mutation-tested in 14-scoreboard
    async run(t) {
      needsTab(t);
      const file = require('path').join(t.server.dir, 'plugins', 'TAB', 'config.yml');
      const config = fs.readFileSync(file, 'utf8');
      const on = config.replace(/^(scoreboard:\r?\n  enabled:) false/m, '$1 true'); // TAB writes CRLF
      t.check(on !== config, 'could not turn on TAB\'s scoreboard in ' + file);
      fs.writeFileSync(file, on);
      try {
        await t.ask('tab reload', /reload/i, 15000);
        const [v] = await t.bots(1);
        await t.op(v);
        const sidebar = () => v.scoreboard?.sidebar?.name ?? 'none';
        await t.until(() => sidebar() !== 'none' && sidebar() !== 'vanishpp', 10000, `control: TAB's sidebar (shows ${sidebar()})`);
        const tabBoard = sidebar();
        await t.vanish(v);
        await t.until(() => sidebar() === 'vanishpp', 5000, `the vanish scoreboard after vanishing (shows ${sidebar()})`);
        await t.wait(4000); // TAB refreshes the player after the vanish (Vanish++ asks it to); the board must stay
        t.check(sidebar() === 'vanishpp', `the vanish scoreboard must stay while vanished (shows ${sidebar()})`);
        await t.vanish(v, false);
        await t.until(() => sidebar() === tabBoard, 10000, `TAB's sidebar after unvanishing (shows ${sidebar()})`);
      } finally {
        fs.writeFileSync(file, config);
        t.cmd('tab reload');
      }
    },
  },
  {
    name: 'TAB plugin: the server restarts without TAB',
    guard: true,
    timeout: 300000,
    async run(t) {
      needsTab(t);
      await t.restartServer();
      const since = t.server.lines.slice(t.server.startedAt);
      t.check(!since.some(l => /\[TAB\]/.test(l)), 'TAB is still loaded');
    },
  },
];
