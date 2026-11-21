'use strict';
// /vhelp links to the wiki: the list has a [Wiki] button for the whole wiki, and each command's detail page has one
// for that command's page. Checked on what the client receives: the click must open the right URL.

const WIKI = 'https://github.com/TheCommandCraft/Vanishpp/wiki';

/** Every open_url target in the chat the bot received since `from`. */
function urls(bot) {
  const out = [];
  const walk = n => {
    if (!n || typeof n !== 'object') return;
    if (Array.isArray(n)) return n.forEach(walk);
    const click = n.click_event || n.clickEvent;
    if (click && click.action === 'open_url') out.push(click.url ?? click.value);
    Object.values(n).forEach(walk);
  };
  bot.chatJson.forEach(walk);
  return out;
}

module.exports = [
  {
    name: '/vhelp: the list and each command page have a [Wiki] button that opens the right wiki page',
    async run(t) {
      const [s] = await t.bots(1);
      await t.op(s);
      s.chatJson.length = 0;
      s.chat('/vhelp');
      await t.until(() => urls(s).length > 0, 5000, 'a [Wiki] button in /vhelp');
      t.check(urls(s).includes(WIKI), `the /vhelp list should link to ${WIKI}, got ${JSON.stringify(urls(s))}`);

      for (const [cmd, page] of [['vconfig', 'Commands#vconfig'], ['vrules', 'Personal-Rules-%28VRules%29'], ['vanish', 'Vanish-Basics']]) {
        s.chatJson.length = 0;
        s.chat(`/vhelp ${cmd}`);
        await t.until(() => urls(s).length > 0, 5000, `a [Wiki] button in /vhelp ${cmd}`);
        t.check(urls(s).includes(`${WIKI}/${page}`), `/vhelp ${cmd} should link to ${WIKI}/${page}, got ${JSON.stringify(urls(s))}`);
      }
    },
  },
];
