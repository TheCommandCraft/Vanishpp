'use strict';
// Sleep count and night vision (issue #2 / PR #34).

const NIGHT_VISION = 15; // MobEffect registry id the client receives for minecraft:night_vision
const nightVision = bot => Object.values(bot.entity.effects || {}).find(e => e.id === NIGHT_VISION);
const lastSleepBar = bot => bot.actionBars.filter(s => s.includes('sleep.players_sleeping')).pop();
const bar = (a, b) => `{"with":["${a}","${b}"],"translate":"sleep.players_sleeping"}`;

async function bedAt(t, bot, dx, dz) {
  const x = t.area.x + dx, z = t.area.z + dz, y = t.ground;
  t.cmd(`setblock ${x} ${y} ${z} minecraft:red_bed[facing=east,part=foot]`);
  t.cmd(`setblock ${x + 1} ${y} ${z} minecraft:red_bed[facing=east,part=head]`);
  return t.until(() => { const b = bot.blockAt(bot.entity.position.offset(x - Math.floor(bot.entity.position.x), 0, z - Math.floor(bot.entity.position.z)).floored()); return b && b.name === 'red_bed' && b; },
    5000, 'bed to appear');
}

module.exports = [
  {
    name: 'night vision: own potion survives a vanish cycle, the plugin effect is fully removed',
    async run(t) {
      const [v] = await t.bots(1);
      await t.op(v); // vanishpp.nightvision
      await t.place(v);
      t.cmd(`effect give ${v.username} minecraft:night_vision 60 0`);
      await t.until(() => nightVision(v), 5000, 'potion effect');
      await t.vanish(v);
      await t.until(() => nightVision(v)?.duration === -1, 5000, 'infinite night vision while vanished');
      await t.vanish(v, false);
      await t.until(() => nightVision(v)?.duration > 0, 3000, 'own potion to come back').catch(() => {});
      const own = nightVision(v);
      t.check(own && own.duration > 0 && own.duration <= 1200, 'own potion should come back with its remaining time, got ' + JSON.stringify(own));

      t.cmd(`effect clear ${v.username} minecraft:night_vision`);
      await t.until(() => !nightVision(v), 5000, 'effect cleared');
      await t.vanish(v);
      await t.until(() => nightVision(v), 5000, 'plugin night vision while vanished');
      await t.vanish(v, false);
      await t.wait(1000); // the old 1-tick trick would "expire" within this window if it worked at all
      t.check(!nightVision(v), 'night vision stuck after unvanish: ' + JSON.stringify(nightVision(v)));
    },
  },
  {
    name: 'sleep bar leaves out vanished players, an awake player blocks the skip, a vanished one does not',
    async run(t) {
      const [v, a, b] = await t.bots(3);
      await t.place(v, -3, 0); await t.place(a, 0, 0); await t.place(b, 0, 4);
      await t.vanish(v);
      await t.gamerule('advance_time', true); // with advance_time off, vanilla never skips the night
      const bedA = await bedAt(t, a, 2, 0);
      const bedB = await bedAt(t, b, 2, 4);
      t.cmd('time set 18000');
      await t.until(() => a.time.timeOfDay >= 17900, 3000, 'night');
      await a.sleep(bedA);
      await t.until(() => lastSleepBar(a) && lastSleepBar(b), 5000, 'sleep action bar for both players');
      await t.wait(5500); // past the 100 ticks a sleeper needs before the night may skip
      // Real count: Sleeper + vanished (sleeping-ignored) = 2 of 3; shown without the vanished player: 1 of 2.
      t.check(lastSleepBar(a) === bar(1, 2), `sleeper bar should read 1/2, got ${lastSleepBar(a)}`);
      t.check(lastSleepBar(b) === bar(1, 2), `awake player bar should read 1/2, got ${lastSleepBar(b)}`);
      t.check(a.time.timeOfDay > 17000, 'night skipped although a visible player was awake');
      await b.sleep(bedB);
      // Once enough players sleep, vanilla replaces the count with "Sleeping through this night".
      await t.until(() => a.actionBars.some(s => s.includes('sleep.skipping_night')), 5000, 'the "sleeping through this night" bar once both visible players sleep');
      await t.until(() => a.time.timeOfDay < 12000, 10000, 'night to skip with both visible players asleep and the vanished one awake');
    },
  },
  {
    name: 'a vanished player alone never skips the night',
    guard: true, // nothing in Vanish++ makes this pass; it guards against sleeping-ignored skipping the night by itself
    async run(t) {
      const [v, o] = await t.bots(2);
      await t.place(v); await t.place(o, 3, 0);
      await t.gamerule('players_sleeping_percentage', 50);
      await t.gamerule('advance_time', true); // otherwise the night could never skip and this would prove nothing
      await t.vanish(v);
      t.cmd('time set 18000');
      await t.until(() => o.time.timeOfDay >= 17900, 3000, 'night');
      await t.wait(6000);
      t.check(o.time.timeOfDay > 17900, 'night skipped with nobody in a bed, time is ' + o.time.timeOfDay);
      // Control: the same setup does skip once a visible player sleeps, so the check above could have failed.
      const bed = await bedAt(t, o, 2, 0);
      await o.sleep(bed);
      await t.until(() => o.time.timeOfDay < 12000, 10000, 'control: night skips once the visible player sleeps');
    },
  },
];
