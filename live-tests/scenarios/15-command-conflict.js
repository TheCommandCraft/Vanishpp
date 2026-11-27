'use strict';
// RFer: /vanish ran DeluxeHub's vanish instead of Vanish++'s. Hub and essentials plugins (DeluxeHub, EssentialsX,
// CMI) can register /vanish first; Vanish++ takes its command names back once every plugin is enabled, says so in
// the console, and the other plugin's command stays reachable under its namespace. The server restarts with a test
// plugin that registers /vanish before Vanish++, and without it again afterwards.

const path = require('path');
const CONFLICT_JAR = path.join(__dirname, '..', 'helper-conflict', 'target', 'vanishpp-livetest-conflict-1.jar');

module.exports = [
  {
    name: 'command conflict: the server restarts with a plugin that registers /vanish before Vanish++',
    guard: true, // setup step; its control is that the other plugin really got /vanish first
    timeout: 300000,
    async run(t) {
      await t.restartServer({ extra: [CONFLICT_JAR] });
      const since = t.server.lines.slice(t.server.startedAt);
      t.check(since.some(l => /LTCONFLICT registered \/vanish first=true/.test(l)), 'control: the test plugin did not get /vanish first');
    },
  },
  {
    name: 'command conflict: /vanish runs Vanish++ although another plugin registered it first, and the console says from whom',
    async run(t) {
      const [s] = await t.bots(1);
      await t.op(s);
      s.chat('/vanish');
      await t.wait(1500);
      t.check(!s.chatLog.some(m => /LTCONFLICT/.test(m)), '/vanish ran the other plugin\'s command instead of Vanish++\'s');
      await t.until(async () => t.isVanished(s), 5000, '/vanish to vanish the player through Vanish++');
      s.chat('/v'); // the alias, which the other plugin also registered first
      await t.until(async () => !(await t.isVanished(s)), 5000, '/v to unvanish the player through Vanish++');
      t.check(!s.chatLog.some(m => /LTCONFLICT/.test(m)), '/v ran the other plugin\'s command instead of Vanish++\'s');
      s.chat('/livetestconflict:vanish');
      await t.until(() => s.chatLog.some(m => /LTCONFLICT vanish ran/.test(m)), 5000, 'the other plugin\'s command to stay reachable as /livetestconflict:vanish');
      const since = t.server.lines.slice(t.server.startedAt);
      t.check(since.some(l => /Vanishpp.*\/vanish.*LiveTestConflict|LiveTestConflict.*\/vanish/i.test(l) && !/LTCONFLICT/.test(l)),
        'Vanish++ did not say in the console that it took /vanish from LiveTestConflict');
      t.check(since.some(l => /Took .*\/v\b.* from LiveTestConflict/.test(l)), 'Vanish++ did not take back /v');
    },
  },
  {
    name: 'command conflict: the server restarts without the other plugin',
    guard: true,
    timeout: 300000,
    async run(t) { await t.restartServer(); },
  },
];
