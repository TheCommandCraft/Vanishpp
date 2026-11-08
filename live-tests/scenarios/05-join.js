'use strict';
// Vanish state across leaving and rejoining. The player toggles with their own /vanish command, as staff do.

/** Every run_command a chat message offers as a clickable button (1.21.5+ click_event or older clickEvent). */
function buttons(json, out = []) {
  if (!json || typeof json !== 'object') return out;
  const click = json.click_event || json.clickEvent;
  if (click && (click.action === 'run_command')) out.push({ text: JSON.stringify(json.text ?? json.extra ?? ''), command: click.command ?? click.value });
  for (const v of Object.values(json)) if (v && typeof v === 'object') buttons(v, out);
  return out;
}
const noticeShown = bot => bot.chatLog.some(m => /auto-vanish is on/i.test(m));

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
  {
    name: 'auto-vanish notice on join: shown when auto-vanish hid a player, and its buttons work',
    timeout: 90000,
    async run(t) {
      let [v, o] = await t.bots(2);
      await t.op(v);
      await t.place(v); await t.place(o, 3, 0);
      v.chat('/vautovanish on');
      await t.wait(800);
      // Control: a plain rejoin while visible and auto-vanish on must show the notice.
      v = await t.rejoin(v);
      await t.until(() => noticeShown(v), 5000, 'the auto-vanish notice after joining');
      const offered = v.chatJson.flatMap(j => buttons(j)).map(b => b.command);
      for (const want of ['/vanish', '/vautovanish off', '/vack auto_vanish'])
        t.check(offered.includes(want), `the notice should offer a button that runs ${want}, it offers ${JSON.stringify(offered)}`);

      // [Unvanish] makes the player visible; auto-vanish stays on.
      v.chat('/vanish');
      await t.until(async () => !(await t.isVanished(v)), 5000, 'the [Unvanish] button to unvanish');
      v = await t.rejoin(v);
      await t.until(() => noticeShown(v), 5000, 'the notice again on the next join');
      t.check(await t.isVanished(v), 'auto-vanish should still be on after [Unvanish]');

      // [Got it] hides the notice for good; the player still joins vanished.
      v.chat('/vack auto_vanish');
      await t.wait(800);
      await t.vanish(v, false);
      v = await t.rejoin(v);
      await t.wait(3000); // the notice is sent 2 seconds after joining
      t.check(await t.isVanished(v), 'auto-vanish should still apply after [Got it]');
      t.check(!noticeShown(v), 'the notice came back after [Got it]');

      // [Turn off auto-vanish] makes the next join visible.
      v.chat('/vautovanish off');
      await t.wait(800);
      await t.vanish(v, false);
      v = await t.rejoin(v);
      await t.place(v);
      await expectState(t, v, o, false, 'after [Turn off auto-vanish], rejoin');
    },
  },
  {
    name: 'no auto-vanish notice when the player left vanished or auto-vanish is off',
    async run(t) {
      let [v] = await t.bots(1);
      await t.op(v);
      await selfVanish(t, v, true);
      v = await t.rejoin(v);
      await t.wait(3000);
      t.check(!noticeShown(v), 'notice shown although the player simply left vanished (auto-vanish off)');
      v.chat('/vautovanish on');
      await t.wait(800);
      v = await t.rejoin(v);
      await t.wait(3000);
      t.check(await t.isVanished(v), 'player should rejoin vanished');
      t.check(!noticeShown(v), 'notice shown although the player left vanished, so auto-vanish changed nothing');
    },
  },
];
