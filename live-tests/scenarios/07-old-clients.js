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

module.exports = [
  oldClientTest('1.20.4', 'LtOld120'),
  oldClientTest('1.16.5', 'LtOld116'),
];
