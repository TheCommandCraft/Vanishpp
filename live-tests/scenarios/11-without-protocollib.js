'use strict';
// Teams and tab names must work exactly the same without ProtocolLib: Vanish++ edits those packets itself. The server
// restarts without ProtocolLib, the team tests from 10-teams run again, and ProtocolLib comes back for later tests.

const teams = require('./10-teams.js');

const restartedWithout = {
  name: 'without ProtocolLib: the server restarts without ProtocolLib',
  guard: true, // setup step; its control is the check that ProtocolLib really is gone
  timeout: 300000,
  async run(t) {
    await t.restartServer({ without: ['ProtocolLib'] });
    const since = t.server.lines.slice(t.server.startedAt);
    t.check(!since.some(l => /\[Vanishpp\] Hooked into ProtocolLib/.test(l)), 'ProtocolLib is still loaded');
    t.check(since.some(l => /Vanish\+\+ .* enabled/.test(l)), 'Vanish++ did not start without ProtocolLib');
  },
};

const restoredWith = {
  name: 'without ProtocolLib: ProtocolLib is back for the rest of the run',
  guard: true,
  timeout: 300000,
  async run(t) {
    await t.restartServer();
    const since = t.server.lines.slice(t.server.startedAt);
    t.check(since.some(l => /\[Vanishpp\] Hooked into ProtocolLib/.test(l)), 'ProtocolLib did not come back');
  },
};

module.exports = [
  restartedWithout,
  // Same code path as with ProtocolLib, so the mutants are checked once, on the 10-teams originals.
  ...teams.map(test => ({ ...test, guard: true, name: 'without ProtocolLib: ' + test.name })),
  restoredWith,
];
