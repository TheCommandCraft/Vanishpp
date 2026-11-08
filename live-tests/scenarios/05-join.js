'use strict';
// Vanish state across leaving and rejoining. The player toggles with their own /vanish command, as staff do.

const playerEntity = (bot, name) => Object.values(bot.entities).find(e => e.type === 'player' && e.username === name);

async function selfVanish(t, bot, on) {
  bot.chat('/vanish');
  await t.until(async () => (await t.isVanished(bot)) === on, 5000, `${bot.username} to be ${on ? 'vanished' : 'visible'} after /vanish`);
}

/** Checks the rejoined player's state on the server and what a normal observer sees. */
async function expectState(t, bot, observer, vanished, what) {
  t.check((await t.isVanished(bot)) === vanished, `${what}: rejoined ${vanished ? 'visible' : 'vanished'}, expected ${vanished ? 'vanished' : 'visible'}`);
  await t.wait(500);
  const seen = Boolean(observer.players[bot.username]) && Boolean(playerEntity(observer, bot.username));
  t.check(seen === !vanished, `${what}: the observer ${seen ? 'sees' : 'does not see'} the rejoined player`);
}

module.exports = [
  {
    name: 'a vanished player who leaves rejoins vanished',
    async run(t) {
      let [v, o] = await t.bots(2);
      await t.op(v);
      await t.place(v); await t.place(o, 3, 0);
      await selfVanish(t, v, true);
      v = await t.rejoin(v);
      await t.place(v);
      await expectState(t, v, o, true, 'vanish, leave, rejoin');
    },
  },
  {
    name: 'a player who unvanished before leaving rejoins visible, even when leaving right away',
    async run(t) {
      let [v, o] = await t.bots(2);
      await t.op(v);
      await t.place(v); await t.place(o, 3, 0);
      await selfVanish(t, v, true);
      await selfVanish(t, v, false);
      v = await t.rejoin(v); // leaves the moment the unvanish is confirmed
      await t.place(v);
      await expectState(t, v, o, false, 'vanish, unvanish, leave at once, rejoin');
      await selfVanish(t, v, true);
      await t.wait(2000);
      await selfVanish(t, v, false);
      await t.wait(2000);
      v = await t.rejoin(v);
      await t.place(v);
      await expectState(t, v, o, false, 'vanish, unvanish, wait, leave, rejoin');
    },
  },
  {
    name: 'auto-vanish: on joins vanished even after unvanishing, off joins visible',
    async run(t) {
      let [v, o] = await t.bots(2);
      await t.op(v);
      await t.place(v); await t.place(o, 3, 0);
      v.chat('/vautovanish on');
      await t.wait(800);
      v = await t.rejoin(v);
      await t.place(v);
      await expectState(t, v, o, true, 'auto-vanish on, rejoin');
      await selfVanish(t, v, false);
      v = await t.rejoin(v);
      await t.place(v);
      await expectState(t, v, o, true, 'auto-vanish on, unvanish, rejoin');
      v.chat('/vautovanish off');
      await t.wait(800);
      await selfVanish(t, v, false);
      v = await t.rejoin(v);
      await t.place(v);
      await expectState(t, v, o, false, 'auto-vanish off, unvanish, rejoin');
    },
  },
  {
    name: 'vanish state survives a server restart, also when the player unvanished just before it',
    timeout: 120000,
    async run(t) {
      let [v] = await t.bots(1);
      await t.op(v);
      await selfVanish(t, v, true);
      await t.restartServer();
      let o;
      [v, o] = await t.bots(2);
      await t.place(v); await t.place(o, 3, 0);
      await t.wait(1500);
      await expectState(t, v, o, true, 'vanish, restart, rejoin');
      await selfVanish(t, v, false);
      await t.restartServer(); // right after the unvanish, while the save may still be pending
      [v, o] = await t.bots(2);
      await t.place(v); await t.place(o, 3, 0);
      await t.wait(1500);
      await expectState(t, v, o, false, 'unvanish, restart at once, rejoin');
    },
  },
];
