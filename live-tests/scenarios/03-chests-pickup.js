'use strict';
// Silent containers and item pickup.
// A real client animates a chest lid from block_action (ClientPacketListener#handleBlockEvent) and plays
// sounds from sound_effect (positions are block coordinates * 8) and entity_sound_effect (at an entity).

function watchChest(t, bot, pos, vanishedEntityId) {
  const seen = { lid: 0, sounds: [] };
  const near = p => p && Math.abs(p.x - pos.x) <= 1 && Math.abs(p.y - pos.y) <= 1 && Math.abs(p.z - pos.z) <= 1;
  t.listen(bot, 'block_action', p => { if (near(p.location)) seen.lid++; });
  t.listen(bot, 'sound_effect', p => {
    if (near({ x: Math.floor(p.x / 8), y: Math.floor(p.y / 8), z: Math.floor(p.z / 8) })) seen.sounds.push(JSON.stringify(p.sound));
  });
  t.listen(bot, 'entity_sound_effect', p => { if (p.entityId === vanishedEntityId) seen.sounds.push('entity ' + JSON.stringify(p.sound)); });
  return seen;
}

async function chestAt(t, bot, dx, dz) {
  const x = t.area.x + dx, y = t.ground, z = t.area.z + dz;
  t.cmd(`setblock ${x} ${y} ${z} minecraft:chest`);
  t.cmd(`item replace block ${x} ${y} ${z} container.0 with minecraft:diamond 3`);
  const block = await t.until(() => { const b = bot.blockAt(bot.entity.position.offset(dx, 0, dz).floored()); return b && b.name === 'chest' && b; },
    5000, 'chest to appear');
  return { block, pos: { x, y, z } };
}

module.exports = [
  {
    name: 'opening a chest while vanished makes no lid animation or sound for others, and shows the real contents',
    async run(t) {
      const [v, o] = await t.bots(2);
      await t.op(v);
      await t.place(v, 0, 0); await t.place(o, 0, 3);
      const { block, pos } = await chestAt(t, v, 2, 0);
      const vEntity = Object.values(o.entities).find(e => e.username === v.username);

      // Control: a visible player opening it must be seen and heard, or the checks below prove nothing.
      const control = watchChest(t, o, pos, vEntity && vEntity.id);
      let win = await v.openContainer(block);
      await t.until(() => control.lid > 0 && control.sounds.length > 0, 3000, 'control: observer sees the lid and hears the chest');
      win.close();
      await t.wait(800);

      await t.vanish(v);
      const seen = watchChest(t, o, pos, vEntity && vEntity.id);
      win = await v.openContainer(block);
      await t.wait(1200);
      const slot0 = win.slots[0];
      t.check(slot0 && slot0.name === 'diamond' && slot0.count === 3,
        'silent chest should show the real contents, slot 0 = ' + JSON.stringify(slot0 && { name: slot0.name, count: slot0.count }));
      win.close();
      await t.wait(1200);
      t.check(seen.lid === 0, `observer saw the lid move ${seen.lid} time(s) while the player was vanished`);
      t.check(seen.sounds.length === 0, 'observer heard sounds from the chest or the vanished player: ' + seen.sounds.join(', '));
    },
  },
  {
    name: 'vanished players do not pick up items unless can_pickup_items is on',
    async run(t) {
      const [v] = await t.bots(1);
      await t.place(v);
      await t.vanish(v);
      const count = () => v.inventory.items().filter(i => i.name === 'emerald').reduce((n, i) => n + i.count, 0);
      t.cmd(`summon minecraft:item ${t.area.x + 0.5} ${t.ground} ${t.area.z + 0.5} {Item:{id:"minecraft:emerald",count:1},PickupDelay:0}`);
      await t.until(() => Object.values(v.entities).some(e => e.name === 'item'), 3000, 'control: the item lies next to the player');
      await t.wait(2000);
      t.check(count() === 0, 'vanished player picked up an item with can_pickup_items off');
      t.cmd(`vrules ${v.username} can_pickup_items true`);
      await t.until(() => count() === 1, 5000, 'item pickup once can_pickup_items is on');
    },
  },
];
