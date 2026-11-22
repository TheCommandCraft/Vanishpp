'use strict';
// Removing Vanish++ from a running server must leave every client exactly as it would be without Vanish++: no [V] or
// [VANISHED] left behind, the real team and tab name back, also for team changes made while hidden.
// 13: disabling the plugin (what plugin managers do first). Paper can't enable a disabled plugin instance again, so
//     the server restarts afterwards.
// 14: /reload, where a fresh Vanish++ comes back on a running server and must decorate players already online.

const { clientTeams } = require('./10-teams.js');
const RED = 'ltred', BLUE = 'ltblue';

/** Team red (prefix R-) with the player on it, a tab name set by another plugin, and the player vanished. */
async function vanishedOnTeam(t, v, staff, shown) {
  if (await t.isVanished(v)) await t.vanish(v, false); // still vanished from before a restart (the state is saved)
  t.cmd(`team add ${RED}`);
  t.cmd(`team add ${BLUE}`);
  await t.ask(`team modify ${RED} prefix "R-"`, /prefix set|nothing changed/i, 3000);
  await t.ask(`team join ${RED} ${v.username}`, /Added .* to team/i, 3000);
  await t.ask(`ltprobe listname ${v.username} &cCustom ${v.username}`, /LTPROBE listname/, 3000);
  await t.vanish(v);
  await t.until(() => staff.tagged(v.username) && /^\[VANISHED\] ?Custom /.test(shown()), 5000,
    `control: staff see [V] and [VANISHED] (team ${staff.teamOf[v.username]}, tab "${shown()}")`);
}

function cleanUp(t, v) {
  t.cmd(`team remove ${RED}`);
  t.cmd(`team remove ${BLUE}`);
  t.cmd(`ltprobe listname ${v.username} reset`);
}

const noErrors = (t, ...models) => {
  for (const [who, m] of models)
    t.check(m.errors.length === 0, `${who}'s client got team packets a real client cannot handle:\n      ${m.errors.join('\n      ')}`);
};

module.exports = [
  {
    name: 'uninstall: disabling Vanish++ while a player is vanished leaves every client as it would be without it',
    timeout: 300000,
    async run(t) {
      const [v, s, n] = await t.bots(3);
      await t.op(s);
      const staff = clientTeams(t, s), normal = clientTeams(t, n);
      const shown = () => (s.players[v.username]?.displayName?.toString() ?? '');
      try {
        await vanishedOnTeam(t, v, staff, shown);
        await t.ask(`team join ${BLUE} ${v.username}`, /Added .* to team/i, 3000); // a change normal players don't learn yet
        await t.wait(500);
        t.check(normal.teamOf[v.username] === RED, `control: the normal client must not learn the team change yet (has ${normal.teamOf[v.username]})`);

        const line = await t.ask('ltprobe plugin disable Vanishpp', /LTPROBE plugin Vanishpp enabled=/, 10000);
        t.check(line.includes('enabled=false'), 'Vanish++ did not disable');
        await t.until(() => staff.teamOf[v.username] === BLUE && !staff.tagged(v.username) && shown() === `Custom ${v.username}`, 5000,
          `staff to see the real team and tab name after Vanish++ is gone (team ${staff.teamOf[v.username]}, tab "${shown()}")`);
        await t.until(() => normal.teamOf[v.username] === BLUE, 5000,
          `the normal client to get the team change made while hidden (has ${normal.teamOf[v.username]})`);
        noErrors(t, ['staff', staff], ['normal', normal]);
      } finally {
        cleanUp(t, v);
        await t.restartServer(); // brings Vanish++ back for the following tests
      }
    },
  },
  {
    name: 'reload: after /reload the new Vanish++ gives staff the prefixes back for players who stayed online',
    timeout: 300000,
    async run(t) {
      // ViaVersion kicks every player on a reload, so nobody would stay online: this test runs without it.
      await t.restartServer({ without: ['ViaVersion', 'ViaBackwards'] });
      const [v, s] = await t.bots(2);
      await t.op(s);
      const staff = clientTeams(t, s);
      const shown = () => (s.players[v.username]?.displayName?.toString() ?? '');
      try {
        await vanishedOnTeam(t, v, staff, shown);
        await t.ask('bukkit:reload confirm', /Reload complete/i, 60000);
        await t.until(async () => t.isVanished(v), 10000, 'the player to still be vanished after the reload');
        await t.until(() => staff.tagged(v.username) && /^\[VANISHED\] ?Custom /.test(shown()), 10000,
          `staff to have [V] and [VANISHED] after the reload (team ${staff.teamOf[v.username]}, tab "${shown()}")`);
        await t.vanish(v, false);
        await t.until(() => staff.teamOf[v.username] === RED && shown() === `Custom ${v.username}`, 5000,
          `staff to see the real team and tab name after unvanishing (team ${staff.teamOf[v.username]}, tab "${shown()}")`);
        noErrors(t, ['staff', staff]);
      } finally {
        cleanUp(t, v);
        await t.restartServer(); // ViaVersion back for the following tests
      }
    },
  },
];
