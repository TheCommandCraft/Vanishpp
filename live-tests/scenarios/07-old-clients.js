'use strict';
// Issue #37: players on older Minecraft versions joining through ViaVersion + ViaBackwards must not see a
// vanished player either. Via translates every packet, so this checks that the hiding survives translation.

const playerEntity = (bot, name) => Object.values(bot.entities).find(e => e.type === 'player' && e.username === name);
const inTab = (bot, name) => Boolean(bot.players[name]) && bot.players[name].listed !== false && bot.players[name].listed !== 0;

function oldClientTest(version, name) {
  return {
    name: `a ${version} client (through ViaBackwards) does not see a vanished player`,
    async run(t) {
      const [v] = await t.bots(1);
      const o = await t.extraBot(name, version);
      await t.place(v, 0, 0);
      t.cmd(`tp ${name} ${t.area.x + 3.5} ${t.ground} ${t.area.z + 0.5}`);
      await t.until(() => playerEntity(o, v.username) && inTab(o, v.username), 8000, `control: the ${version} client sees the player and has them in tab`);
      await t.vanish(v);
      await t.until(() => !playerEntity(o, v.username), 5000, `the vanished player to disappear for the ${version} client`);
      await t.until(() => !o.players[v.username], 5000, `the vanished player to leave the ${version} client's player list`);
      await t.wait(1500);
      t.check(!o.players[v.username] && !playerEntity(o, v.username), `the vanished player came back for the ${version} client`);
      // "/msg Lt" also matches the observer itself, so the reply is never empty (an empty list gets no reply).
      const names = JSON.stringify(await o.tabComplete('/msg Lt'));
      t.check(names.includes(`"${name}"`), `control: tab-complete should suggest the ${version} client's own name, got ${names}`);
      t.check(!names.includes(`"${v.username}"`), `tab-complete leaked the vanished player to the ${version} client: ${names}`);
      await t.vanish(v, false);
      await t.until(() => playerEntity(o, v.username) && inTab(o, v.username), 8000, `the player to reappear for the ${version} client`);
    },
  };
}

/** Teams through ViaBackwards: an old staff client sees [V] in front of the team prefix, an old normal client learns nothing. */
function oldClientTeamTest(version, staffName, normalName) {
  return {
    name: `a ${version} client (through ViaBackwards) sees teams right: [V] in front of the team prefix for staff, nothing for others`,
    async run(t) {
      const { clientTeams } = require('./10-teams.js');
      const [v] = await t.bots(1);
      const s = await t.extraBot(staffName, version);
      const n = await t.extraBot(normalName, version);
      await t.op(s);
      const staff = clientTeams(t, s), normal = clientTeams(t, n);
      t.cmd('team add ltred');
      t.cmd('team add ltblue');
      try {
        await t.ask('team modify ltred prefix "R-"', /prefix set|nothing changed/i, 3000);
        await t.ask(`team join ltred ${v.username}`, /Added .* to team/i, 3000);
        await t.until(() => staff.teamOf[v.username] === 'ltred' && normal.teamOf[v.username] === 'ltred', 5000, `control: both ${version} clients have the player on ltred`);
        await t.vanish(v);
        const prefix = () => staff.params[staff.teamOf[v.username]]?.prefix || '';
        await t.until(() => /\[V\].*R-/.test(prefix()), 5000, `the ${version} staff client to show [V] in front of "R-" (has ${prefix()})`);
        const since = normal.packets.length;
        await t.ask(`team join ltblue ${v.username}`, /Added .* to team/i, 3000);
        await t.wait(600);
        t.check(normal.mentions(v.username, since).length === 0, `the ${version} normal client got team packets about the vanished player`);
        await t.vanish(v, false);
        await t.until(() => staff.teamOf[v.username] === 'ltblue' && normal.teamOf[v.username] === 'ltblue', 5000,
          `both ${version} clients to show the player on ltblue after unvanishing (staff ${staff.teamOf[v.username]}, normal ${normal.teamOf[v.username]})`);
        for (const [who, m] of [['staff', staff], ['normal', normal]])
          t.check(m.errors.length === 0, `the ${version} ${who} client got team packets a real client cannot handle:\n      ${m.errors.join('\n      ')}`);
      } finally {
        t.cmd('team remove ltred');
        t.cmd('team remove ltblue');
        t.cmd(`deop ${staffName}`);
      }
    },
  };
}

module.exports = [
  oldClientTest('1.20.4', 'LtOld120'),
  oldClientTest('1.16.5', 'LtOld116'),
  oldClientTeamTest('1.20.4', 'LtOld120s', 'LtOld120n'),
  oldClientTeamTest('1.16.5', 'LtOld116s', 'LtOld116n'),
];
