'use strict';
// With the TAB plugin (NEZNAMY), which draws nametags and tab names with its own team and tab packets: staff see
// [V] and [VANISHED] in front of TAB's prefixes, TAB's prefixes come back unchanged, and players who can't see the
// vanished player learn nothing. Vanish++ must not need TAB for any of this (10-teams and 11 run without it); this
// checks the combination. The server restarts with TAB and without it again afterwards.

const path = require('path');
const fs = require('fs');
const TAB_JAR = path.join(__dirname, '..', '.cache', 'TAB-6.2.1.jar');
const GROUPS = '_DEFAULT_:\n  tabprefix: "&bTAB "\n  tagprefix: "&bTAB "\n  customtabname: "%player%"\n  tabsuffix: ""\n  tagsuffix: ""\n';

module.exports = [
  {
    name: 'TAB plugin: the server restarts with TAB',
    guard: true, // setup step; its control is the check that TAB really loaded
    timeout: 300000,
    async run(t) {
      await t.restartServer({ extra: [TAB_JAR], files: { 'plugins/TAB/groups.yml': GROUPS } });
      const since = t.server.lines.slice(t.server.startedAt);
      t.check(since.some(l => /\[TAB\] Enabled in/.test(l) || /\[TAB\].*(Enabling|enabled)/i.test(l)), 'TAB did not load');
    },
  },
  {
    name: 'TAB plugin: staff see [V] and [VANISHED] in front of TAB\'s prefixes, which come back unchanged; others learn nothing',
    guard: true, // integration check; the rules it relies on are mutation-tested in 10-teams. Control: TAB's prefixes before vanishing.
    async run(t) {
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
    name: 'TAB plugin: the server restarts without TAB',
    guard: true,
    timeout: 300000,
    async run(t) {
      await t.restartServer();
      const since = t.server.lines.slice(t.server.startedAt);
      t.check(!since.some(l => /\[TAB\]/.test(l)), 'TAB is still loaded');
    },
  },
];
