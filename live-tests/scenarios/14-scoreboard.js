'use strict';
// The vanish scoreboard (/vsb): shown on vanish, and the player's own scoreboard comes back afterwards. Hub plugins
// such as DeluxeHub give every player their own scoreboard once on join; Vanish++ used to switch players to the
// server's main scoreboard instead, so that board was gone until the other plugin was reloaded (RFer's report).
// Checked on the sidebar the client shows.

const sidebar = bot => bot.scoreboard?.sidebar?.name ?? 'none';

async function ownBoard(t, bot) {
  await t.ask(`ltprobe board ${bot.username}`, /LTPROBE board .* set/, 3000);
  await t.until(() => sidebar(bot) === 'ltboard', 5000, `control: the client to show its own board (shows ${sidebar(bot)})`);
}

module.exports = [
  {
    name: 'scoreboard: vanishing shows the vanish scoreboard, unvanishing gives the player their own board back',
    async run(t) {
      const [v] = await t.bots(1);
      await t.op(v);
      await ownBoard(t, v);
      await t.vanish(v);
      await t.until(() => sidebar(v) === 'vanishpp', 5000, `the vanish scoreboard after vanishing (shows ${sidebar(v)})`);
      await t.vanish(v, false);
      await t.until(() => sidebar(v) === 'ltboard', 5000, `the player's own board after unvanishing (shows ${sidebar(v)})`);
    },
  },
  {
    name: 'scoreboard: /vsb off gives the player their own board back, /vsb on shows the vanish scoreboard again',
    async run(t) {
      const [v] = await t.bots(1);
      await t.op(v);
      await ownBoard(t, v);
      await t.vanish(v);
      await t.until(() => sidebar(v) === 'vanishpp', 5000, `control: the vanish scoreboard after vanishing (shows ${sidebar(v)})`);
      v.chat('/vsb');
      await t.until(() => sidebar(v) === 'ltboard', 5000, `the player's own board after /vsb off (shows ${sidebar(v)})`);
      v.chat('/vsb');
      await t.until(() => sidebar(v) === 'vanishpp', 5000, `the vanish scoreboard after /vsb on (shows ${sidebar(v)})`);
      await t.vanish(v, false);
      await t.until(() => sidebar(v) === 'ltboard', 5000, `the player's own board after unvanishing (shows ${sidebar(v)})`);
    },
  },
  {
    // RFer: with "*" in LuckPerms the board never showed on vanish. "*" grants every permission, including the old
    // opt-out vanishpp.scoreboard.bypass; set here the same way a permissions plugin does.
    name: 'scoreboard: a player with every permission (also the old opt-out vanishpp.scoreboard.bypass) gets the board on vanish',
    async run(t) {
      const [v] = await t.bots(1);
      await t.op(v);
      await t.ask(`ltprobe perm ${v.username} vanishpp.scoreboard.bypass true`, /LTPROBE perm .*=true/, 3000);
      await t.vanish(v);
      try {
        await t.until(() => sidebar(v) === 'vanishpp', 5000, `the vanish scoreboard after vanishing (shows ${sidebar(v)})`);
      } finally {
        await t.ask(`ltprobe perm ${v.username} vanishpp.scoreboard.bypass false`, /LTPROBE perm/, 3000); // the bot is reused
        await t.vanish(v, false);
      }
    },
  },
  {
    name: 'scoreboard: turning it off with /vsb is remembered on the next vanish and after rejoining, /vsb turns it on again',
    async run(t) {
      let [v] = await t.bots(1);
      await t.op(v);
      await t.vanish(v);
      await t.until(() => sidebar(v) === 'vanishpp', 5000, `control: the vanish scoreboard after vanishing (shows ${sidebar(v)})`);
      v.chat('/vsb');
      await t.until(() => sidebar(v) !== 'vanishpp', 5000, `no vanish board after /vsb off (shows ${sidebar(v)})`);
      try {
        await t.vanish(v, false);
        await t.vanish(v);
        await t.wait(1000);
        t.check(sidebar(v) !== 'vanishpp', `the board must stay off on the next vanish (shows ${sidebar(v)})`);
        v = await t.rejoin(v); // still vanished; the join re-shows the board after about 3 s unless it is off
        await t.wait(4000);
        t.check(sidebar(v) !== 'vanishpp', `the board must stay off after rejoining (shows ${sidebar(v)})`);
        v.chat('/vsb');
        await t.until(() => sidebar(v) === 'vanishpp', 5000, `the vanish scoreboard after /vsb on (shows ${sidebar(v)})`);
      } finally {
        if (sidebar(v) !== 'vanishpp' && await t.isVanished(v)) v.chat('/vsb'); // leave the choice on for other tests
        await t.wait(500);
        if (await t.isVanished(v)) await t.vanish(v, false);
      }
    },
  },
];
