'use strict';
// Mob targeting and the per-player rules.

module.exports = [
  {
    name: 'hostile mobs never target a vanished player, even right next to them (control: a visible one is targeted)',
    timeout: 30000,
    async run(t) {
      // Vanished players are also invisible, and vanilla mobs only notice an invisible player within ~7% of
      // their follow range (about 2.4 blocks for a zombie). Spawning the zombie 1 block away makes sure the
      // plugin's own target blocking is what protects the player, not just invisibility.
      t.cmd('difficulty easy');
      t.cmd('time set 18000');
      const [v, c] = await t.bots(2);
      await t.place(v, 0, 0); await t.place(c, 0, 16);
      await t.vanish(v);
      for (const dz of [0, 16]) t.cmd(`summon minecraft:zombie ${t.area.x + 1.5} ${t.ground} ${t.area.z + dz + 0.5} {PersistenceRequired:1b,IsBaby:0b}`);
      await t.until(async () => (await t.targets()).some(l => l.endsWith('-> ' + c.username)), 8000, 'control: the server reports a zombie targeting the visible player');
      await t.wait(2500);
      const targets = await t.targets();
      t.check(!targets.some(l => l.endsWith('-> ' + v.username)), 'a zombie is targeting the vanished player: ' + targets.join('; '));
      t.check(v.health === 20, 'vanished player took damage from a zombie, health ' + v.health);
    },
  },
  {
    name: 'god mode: a vanished player takes no damage at all (control: a visible player does)',
    async run(t) {
      const [v, c] = await t.bots(2);
      await t.place(v, 0, 0); await t.place(c, 0, 6);
      await t.vanish(v);
      t.cmd(`damage ${c.username} 4 minecraft:generic`);
      await t.until(() => c.health < 20, 3000, 'control: the visible player takes the damage');
      t.cmd(`damage ${v.username} 4 minecraft:generic`);
      await t.wait(1000);
      t.check(v.health === 20, 'vanished player took damage with god mode on, health ' + v.health);
    },
  },
  {
    name: 'with god mode off, damage a mob deals to a vanished player is still cancelled (control: a visible player takes it)',
    async run(t) {
      t.cmd('difficulty easy'); // zombies despawn at once in peaceful
      await t.config('invisibility-features.god-mode', false, true); // so only the mob-damage rule protects the player
      const [v, c] = await t.bots(2);
      await t.place(v, 0, 0); await t.place(c, 0, 6);
      await t.vanish(v);
      t.cmd(`summon minecraft:zombie ${t.area.x + 0.5} ${t.ground} ${t.area.z + 3.5} {PersistenceRequired:1b,NoAI:1b,Tags:["lt_attacker"]}`);
      await t.until(() => Object.values(v.entities).some(e => e.name === 'zombie'), 3000, 'zombie to spawn');
      t.cmd(`damage ${v.username} 2 minecraft:generic`);
      await t.until(() => v.health < 20, 3000, 'control: with god mode off, non-mob damage reaches the vanished player');
      t.cmd(`damage ${c.username} 6 minecraft:mob_attack by @e[type=zombie,tag=lt_attacker,limit=1]`);
      await t.until(() => c.health < 20, 3000, 'control: the visible player takes the mob damage');
      await t.wait(1200); // players ignore new damage for 1 second after being hurt
      const before = v.health;
      t.cmd(`damage ${v.username} 6 minecraft:mob_attack by @e[type=zombie,tag=lt_attacker,limit=1]`);
      await t.wait(1000);
      // Natural regeneration may raise health a little, but it cannot hide 6 damage within a second.
      t.check(v.health >= before, `vanished player took mob damage, health ${before} -> ${v.health}`);
    },
  },
  {
    name: 'can_break_blocks: off keeps the block, on lets it break (checked on the server)',
    async run(t) {
      // mineflayer shows a dug block as air before the server's correction arrives, so the result is read
      // from the server, and the client is checked again once any correction would have arrived.
      const [v] = await t.bots(1);
      await t.place(v);
      await t.vanish(v);
      const x = t.area.x + 1, y = t.ground, z = t.area.z;
      t.cmd(`setblock ${x} ${y} ${z} minecraft:dirt`);
      const block = () => v.blockAt(v.entity.position.offset(1, 0, 0).floored());
      await t.until(() => block()?.name === 'dirt', 5000, 'dirt to appear');
      try { await v.dig(block(), true); } catch {}
      await t.wait(800);
      t.check(await t.blockIs(x, y, z, 'minecraft:dirt'), 'block broke on the server with can_break_blocks off');
      t.check(block()?.name === 'dirt', 'the client still shows the block as broken: ' + block()?.name);
      t.cmd(`vrules ${v.username} can_break_blocks true`);
      await t.wait(300);
      try { await v.dig(block(), true); } catch {}
      await t.until(() => t.blockIs(x, y, z, 'minecraft:air'), 4000, 'block to break on the server once can_break_blocks is on');
      await t.wait(500);
      t.check(block()?.name === 'air', 'the client should show the block as broken: ' + block()?.name);
    },
  },
  {
    name: 'can_chat: off holds the message, on sends it only to staff with the vanished prefix',
    async run(t) {
      const [v, o, s] = await t.bots(3);
      await t.op(s);
      await t.vanish(v);
      // Precondition: the staff bot really counts as staff (it still has the vanished player in tab).
      await t.until(() => s.players[v.username]?.listed, 5000, 'staff to see the vanished player');
      await t.until(() => !o.players[v.username], 5000, 'the normal player to no longer see the vanished player');
      v.chat('secret-one');
      await t.until(() => v.chatLog.length > 0, 3000, 'vanished player to be told the chat was held');
      await t.wait(1000);
      t.check(!o.chatLog.some(m => m.includes('secret-one')), 'normal player got chat from a vanished player with can_chat off');
      t.check(!s.chatLog.some(m => m.includes('secret-one')), 'staff got chat with can_chat off (it should be held for confirmation)');
      t.cmd(`vrules ${v.username} can_chat true`);
      await t.wait(300);
      v.chat('hello-two');
      await t.until(() => s.chatLog.some(m => m.includes('hello-two')), 5000, 'staff to get the chat once can_chat is on')
        .catch(e => { throw new Error(`${e.message}; vanished player saw ${JSON.stringify(v.chatLog.slice(-4))}, staff saw ${JSON.stringify(s.chatLog.slice(-4))}`); });
      const raw = s.rawChat.filter(m => m.includes('hello-two'));
      t.check(raw.some(m => /vanish/i.test(m)), 'staff copy should carry the vanished prefix: ' + raw.join(' | ').slice(0, 600));
      await t.wait(800);
      t.check(!o.chatLog.some(m => m.includes('hello-two')), 'normal player got chat from a vanished player');
    },
  },
];
