'use strict';
// Core hiding: world, tab list, tab-complete, server list, fake join/quit, staff visibility.
//
// What a real client shows (checked against the decompiled 26.1 client):
// - The tab list is ClientPacketListener#getListedOnlinePlayers: only entries the server marked listed.
// - Name completion in chat and commands (ClientSuggestionProvider#getOnlinePlayerNames) uses EVERY player
//   info entry, listed or not. So a vanished player must be removed from the player list completely;
//   being unlisted is not enough.
const mc = require('minecraft-protocol');

const playerEntity = (bot, name) => Object.values(bot.entities).find(e => e.type === 'player' && e.username === name);
const infoEntry = (bot, name) => bot.players[name];
// minecraft-data decodes the listed flag as a number (1), the vanilla client reads the same byte as a boolean.
const inTab = (bot, name) => Boolean(infoEntry(bot, name)?.listed);
const ping = port => mc.ping({ host: '127.0.0.1', port, version: '1.21.11' });
const names = list => JSON.stringify(list);

module.exports = [
  {
    name: 'vanished player disappears from the world and the player list for normal players',
    async run(t) {
      const [v, o] = await t.bots(2);
      await t.place(v, 0, 0); await t.place(o, 3, 0);
      await t.until(() => playerEntity(o, v.username) && inTab(o, v.username), 5000, 'control: observer sees the player and has them in tab');
      await t.vanish(v);
      await t.until(() => !playerEntity(o, v.username), 5000, 'vanished entity to disappear for the observer');
      await t.until(() => !infoEntry(o, v.username), 5000, 'vanished player to be removed from the observer player list');
      await t.wait(1500);
      t.check(!infoEntry(o, v.username) && !playerEntity(o, v.username), 'vanished player came back for the observer');
      t.check(playerEntity(v, o.username), 'the vanished player should still see the observer');
      await t.vanish(v, false);
      await t.until(() => playerEntity(o, v.username) && inTab(o, v.username), 5000, 'player to reappear in the world and in tab after unvanish');
    },
  },
  {
    name: 'staff with vanishpp.see still see the vanished player',
    async run(t) {
      const [v, s] = await t.bots(2);
      await t.op(s);
      await t.place(v, 0, 0); await t.place(s, 3, 0);
      await t.vanish(v);
      await t.wait(1500);
      t.check(inTab(s, v.username), 'staff should keep the vanished player in tab');
      t.check(playerEntity(s, v.username), 'staff should still see the vanished player');
    },
  },
  {
    name: 'fake quit and fake join messages are broadcast to normal players',
    async run(t) {
      const [v, o] = await t.bots(2);
      await t.vanish(v);
      await t.until(() => o.chatLog.some(m => m.includes(v.username) && /left/i.test(m)), 5000, 'fake quit message');
      o.chatLog.length = 0;
      await t.vanish(v, false);
      await t.until(() => o.chatLog.some(m => m.includes(v.username) && /joined/i.test(m)), 5000, 'fake join message');
    },
  },
  {
    name: "tab-complete never suggests a vanished player, in vanilla and in other plugins' commands",
    async run(t) {
      // Both bots share the LtBot prefix, so a reply is never empty (the server sends nothing for an empty list).
      const [v, o] = await t.bots(2);
      const quoted = n => `"${n}"`;
      for (const command of ['/msg LtBot', '/ltnames LtBot']) {
        const before = await o.tabComplete(command);
        t.check(names(before).includes(quoted(v.username)) && names(before).includes(quoted(o.username)),
          `control: ${command} should suggest both players before vanish, got ${names(before)}`);
      }
      await t.vanish(v);
      for (const command of ['/msg LtBot', '/ltnames LtBot']) {
        const after = await o.tabComplete(command);
        t.check(names(after).includes(quoted(o.username)), `${command} should still suggest the visible player, got ${names(after)}`);
        t.check(!names(after).includes(quoted(v.username)), `${command} leaked the vanished player: ${names(after)}`);
      }
    },
  },
  {
    name: 'server list player count and sample leave out the vanished player',
    async run(t) {
      const [v] = await t.bots(2);
      const before = await ping(t.server.port);
      t.check(JSON.stringify(before.players.sample || []).includes(v.username), 'control: the sample should list the player before vanish');
      await t.vanish(v);
      const after = await ping(t.server.port);
      t.check(after.players.online === before.players.online - 1,
        `online count should drop by one, was ${before.players.online} then ${after.players.online}`);
      const sample = JSON.stringify(after.players.sample || []);
      t.check(!sample.includes(v.username), 'server list sample leaked the vanished player: ' + sample);
    },
  },
];
