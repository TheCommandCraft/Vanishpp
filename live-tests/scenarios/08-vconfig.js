'use strict';
// /vconfig takes one argument per config level (hooks -> simple-voice-chat -> option), and tab-complete offers only
// the options of the current level, so staff can browse the tree. Checked the way a real client sees it.

const KEY = 'hooks.simple-voice-chat.vanished-can-hear-others';
const names = async (bot, text) => JSON.stringify(await bot.tabComplete(text));

module.exports = [
  {
    name: '/vconfig: tab-complete walks the config tree one level at a time and the tree form changes the value',
    async run(t) {
      const [s] = await t.bots(1);
      await t.config(KEY, false, false); // makes sure the runner puts it back afterwards
      await t.op(s);

      const root = await names(s, '/vconfig ');
      t.check(root.includes('"hooks"') && root.includes('"invisibility-features"'), `first level should list the sections, got ${root}`);
      t.check(!root.includes('hooks.'), `first level must not list dotted paths, got ${root}`);
      const hooks = await names(s, '/vconfig hooks ');
      t.check(hooks.includes('"simple-voice-chat"') && hooks.includes('"essentials"'), `hooks level, got ${hooks}`);
      t.check(!hooks.includes('"enabled"'), `hooks level must not list deeper options, got ${hooks}`);
      const svc = await names(s, '/vconfig hooks simple-voice-chat ');
      t.check(svc.includes('"vanished-can-hear-others"') && svc.includes('"isolate-vanished-players"'), `simple-voice-chat level, got ${svc}`);
      const values = await names(s, '/vconfig hooks simple-voice-chat vanished-can-hear-others ');
      t.check(values.includes('"true"') && values.includes('"false"'), `value level, got ${values}`);

      s.chat('/vconfig hooks simple-voice-chat vanished-can-hear-others true');
      // Checked on the server: the console reads the saved value back.
      await t.until(async () => /currently: true/.test(await t.ask(`vconfig ${KEY}`, /vanished-can-hear-others is currently: /, 3000)),
        5000, 'the tree form /vconfig hooks simple-voice-chat vanished-can-hear-others true to set the value');
    },
  },
];
