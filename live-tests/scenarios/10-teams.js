'use strict';
// Scoreboard teams (/team). Vanish++ must never change the server's teams: other plugins and team rules (friendly
// fire, team chat, minigames) rely on them. Staff get the [V] nametag prefix through packets only, and players who
// can't see a vanished player learn nothing about them from team packets.
//
// mineflayer's own team tracking is lenient, so each observer gets a strict model of what a real client believes,
// built from the raw team packets with the vanilla client's rules (ClientPacketListener.handleSetPlayerTeamPacket and
// Scoreboard.addPlayerToTeam/removePlayerFromTeam in the 26.1 client): joining a team leaves the old one, and removing
// a player from a team they are not on throws IllegalStateException, which disconnects a real client.

const MODES = ['add', 'remove', 'change', 'join', 'leave'];
const RED = 'ltred', BLUE = 'ltblue';

/** Strict client-side team model for one bot, from the moment it is attached. */
function clientTeams(t, bot) {
  const m = { teams: new Set(), teamOf: {}, prefix: {}, params: {}, errors: [], packets: [] };
  for (const [name, team] of Object.entries(bot.teams || {})) {
    m.teams.add(name);
    const prefix = team.prefix ? JSON.stringify(team.prefix.json ?? team.prefix.toString()) : '';
    m.prefix[name] = prefix;
    m.params[name] = { prefix, suffix: team.suffix ? JSON.stringify(team.suffix.json ?? team.suffix.toString()) : '', color: team.color };
  }
  for (const [player, team] of Object.entries(bot.teamMap || {})) m.teamOf[player] = team.team;
  t.listen(bot, 'teams', p => {
    const mode = typeof p.mode === 'number' ? MODES[p.mode] : p.mode;
    const players = p.players || [];
    m.packets.push({ mode, team: p.team, players: [...players], prefix: p.prefix === undefined ? undefined : JSON.stringify(p.prefix) });
    if (mode === 'add') m.teams.add(p.team);
    else if (!m.teams.has(p.team)) return; // the vanilla client logs "unknown team" and ignores the packet
    if (p.prefix !== undefined && (mode === 'add' || mode === 'change')) {
      m.prefix[p.team] = JSON.stringify(p.prefix);
      m.params[p.team] = { prefix: JSON.stringify(p.prefix), suffix: JSON.stringify(p.suffix), color: p.formatting };
    }
    if (mode === 'add' || mode === 'join') for (const n of players) m.teamOf[n] = p.team;
    if (mode === 'leave') for (const n of players) {
      if (m.teamOf[n] !== p.team) m.errors.push(`leave ${p.team} [${n}] while the client has ${n} on ${m.teamOf[n] ?? 'no team'} (a real client throws IllegalStateException)`);
      else delete m.teamOf[n];
    }
    if (mode === 'remove') {
      for (const [n, team] of Object.entries(m.teamOf)) if (team === p.team) delete m.teamOf[n];
      m.teams.delete(p.team);
    }
  });
  /** True when the client shows `name` with the [V] nametag prefix. */
  m.tagged = name => m.teamOf[name] !== undefined && (m.prefix[m.teamOf[name]] || '').includes('[V]');
  m.mentions = (name, since = 0) => m.packets.slice(since).filter(p => p.players.includes(name) || (p.prefix || '').includes('[V]'));
  return m;
}

/** The members of a team according to the server's own /team list. */
async function serverMembers(t, team) {
  const line = await t.ask(`team list ${team}`, new RegExp(`Team \\[${team}\\] has|no members on team \\[${team}\\]|Unknown team`, 'i'), 3000);
  const m = line.replace(/\x1b\[[0-9;]*m/g, '').match(/member\(s\): (.*)$/);
  return m ? m[1].split(', ').map(s => s.trim()) : [];
}

async function setupTeams(t, members) {
  t.cmd(`team add ${RED}`);
  t.cmd(`team add ${BLUE}`);
  await t.ask(`team modify ${RED} color red`, /Updated|nothing changed/i, 3000);
  await t.ask(`team modify ${RED} prefix "R-"`, /prefix set|nothing changed/i, 3000);
  for (const name of members) await t.ask(`team join ${RED} ${name}`, /Added .* to team|nothing changed/i, 3000);
}
function removeTeams(t) { t.cmd(`team remove ${RED}`); t.cmd(`team remove ${BLUE}`); }

const noErrors = (t, ...models) => {
  for (const [who, m] of models) t.check(m.errors.length === 0, `${who}'s client got team packets a real client cannot handle:\n      ${m.errors.join('\n      ')}`);
};

module.exports = [
  {
    name: '/team: vanishing never changes the server\'s teams, and Vanish++ registers no team of its own',
    async run(t) {
      const [v] = await t.bots(1);
      await setupTeams(t, [v.username]);
      try {
        t.check((await serverMembers(t, RED)).includes(v.username), `control: ${v.username} should be on ${RED} before vanishing`);
        await t.vanish(v);
        await t.wait(600);
        t.check((await serverMembers(t, RED)).includes(v.username), `vanishing took ${v.username} off their team ${RED} on the server`);
        const all = await t.ask('team list', /There are \d+ team|There are no teams/i, 3000);
        t.check(!/vanish/i.test(all), `Vanish++ registered its own team on the server's scoreboard: ${all}`);
        await t.vanish(v, false);
        await t.wait(600);
        t.check((await serverMembers(t, RED)).includes(v.username), `${v.username} is no longer on ${RED} after unvanishing`);
      } finally { removeTeams(t); }
    },
  },
  {
    name: '/team: staff see the [V] nametag prefix on a vanished player and the real team again after unvanishing; the vanished player keeps their own team',
    async run(t) {
      const [v, s] = await t.bots(2);
      await t.op(s);
      const staff = clientTeams(t, s), self = clientTeams(t, v);
      await setupTeams(t, [v.username]);
      try {
        await t.until(() => staff.teamOf[v.username] === RED, 3000, `control: staff client has ${v.username} on ${RED}`);
        await t.vanish(v);
        await t.until(() => staff.tagged(v.username), 3000, `staff client to show ${v.username} with the [V] prefix`);
        // [V] goes in front of the team's own prefix; color and suffix stay (the nametag of any plugin that uses teams).
        const tagged = () => staff.params[staff.teamOf[v.username]] || {};
        t.check(/\[V\].*R-/.test(tagged().prefix || ''), `the [V] prefix must come before the team's own prefix "R-", got ${tagged().prefix}`);
        t.check(tagged().color === staff.params[RED].color, `the team color was replaced: ${tagged().color} instead of ${staff.params[RED].color}`);
        t.check(self.teamOf[v.username] === RED, `the vanished player's own client lost their team: ${self.teamOf[v.username]}`);
        await t.ask(`team modify ${RED} prefix "Q-"`, /prefix set|nothing changed/i, 3000);
        await t.until(() => /\[V\].*Q-/.test(tagged().prefix || ''), 3000, 'the [V] prefix to follow a team prefix change made while vanished');
        await t.vanish(v, false);
        await t.until(() => staff.teamOf[v.username] === RED, 3000,
          `staff client to show ${v.username} on ${RED} again after unvanishing (has ${staff.teamOf[v.username]})`);
        t.check(!staff.tagged(v.username), 'the [V] prefix stayed after unvanishing');
        noErrors(t, ['staff', staff], ['vanished player', self]);
      } finally { removeTeams(t); }
    },
  },
  {
    name: '/team: players who cannot see a vanished player learn nothing about them from team packets, and see the right team after unvanishing',
    async run(t) {
      const [v, n, s] = await t.bots(3);
      await t.op(s);
      const normal = clientTeams(t, n), staff = clientTeams(t, s);
      await setupTeams(t, [v.username]);
      try {
        await t.until(() => normal.teamOf[v.username] === RED, 3000, `control: normal client has ${v.username} on ${RED}`);
        // Team change while vanished: the server moves the player to BLUE.
        await t.vanish(v);
        await t.wait(300);
        let since = normal.packets.length;
        await t.ask(`team join ${BLUE} ${v.username}`, /Added .* to team/i, 3000);
        await t.ask(`team modify ${BLUE} prefix "B "`, /prefix set|nothing changed/i, 3000);
        await t.wait(600);
        t.check(normal.mentions(v.username, since).length === 0,
          `a normal player got team packets about the vanished player: ${JSON.stringify(normal.mentions(v.username, since))}`);
        t.check(staff.tagged(v.username), `staff lost the [V] prefix when the vanished player changed team (has ${staff.teamOf[v.username]})`);
        await t.vanish(v, false);
        await t.until(() => normal.teamOf[v.username] === BLUE && staff.teamOf[v.username] === BLUE, 3000,
          `both clients to show ${v.username} on ${BLUE} after unvanishing (normal: ${normal.teamOf[v.username]}, staff: ${staff.teamOf[v.username]})`);
        // Leaving every team while vanished.
        await t.vanish(v);
        await t.wait(300);
        since = normal.packets.length;
        await t.ask(`team leave ${v.username}`, /Removed .* from any team/i, 3000);
        await t.wait(600);
        t.check(normal.mentions(v.username, since).length === 0, 'a normal player learned that the vanished player left their team');
        await t.vanish(v, false);
        await t.until(() => normal.teamOf[v.username] === undefined && staff.teamOf[v.username] === undefined, 3000,
          `both clients to show ${v.username} on no team after unvanishing (normal: ${normal.teamOf[v.username]}, staff: ${staff.teamOf[v.username]})`);
        // A team change in the same tick as the unvanish, before Vanish++ has caught the clients up: the server's
        // leave packet must not reach a client that still has the player on another team.
        await t.ask(`team join ${BLUE} ${v.username}`, /Added .* to team/i, 3000);
        await t.until(() => normal.teamOf[v.username] === BLUE, 3000, 'control: normal client has the player on blue');
        await t.vanish(v);
        await t.ask(`team join ${RED} ${v.username}`, /Added .* to team/i, 3000);
        await t.wait(300);
        t.cmd(`vanish ${v.username}`);
        t.cmd(`team leave ${v.username}`);
        await t.until(async () => !(await t.isVanished(v)), 5000, 'the player to be visible again');
        await t.until(() => normal.teamOf[v.username] === undefined && staff.teamOf[v.username] === undefined, 3000,
          `both clients to show ${v.username} on no team (normal: ${normal.teamOf[v.username]}, staff: ${staff.teamOf[v.username]})`);
        t.check(!normal.packets.some(p => (p.prefix || '').includes('[V]')), 'a normal player received the [V] team');
        noErrors(t, ['normal', normal], ['staff', staff]);
      } finally { removeTeams(t); }
    },
  },
  {
    name: 'tab list: staff see [VANISHED] in front of the tab name another plugin set; the server\'s tab name is never changed',
    async run(t) {
      const [v, s] = await t.bots(2);
      await t.op(s);
      const shown = () => (s.players[v.username]?.displayName?.toString() ?? '');
      const serverName = async () => (await t.ask(`ltprobe listname ${v.username}`, /LTPROBE listname \S+ = /, 3000)).split(' = ')[1].trim();
      try {
        await t.ask(`ltprobe listname ${v.username} &cCustom ${v.username}`, /LTPROBE listname/, 3000);
        await t.until(() => shown() === `Custom ${v.username}`, 3000, `control: staff tab shows the plugin's name "Custom ${v.username}" (has "${shown()}")`);
        await t.vanish(v);
        await t.until(() => /^\[VANISHED\] ?Custom /.test(shown()), 3000, `staff tab to show "[VANISHED] Custom ${v.username}" (has "${shown()}")`);
        t.check(await serverName() === `Custom ${v.username}`, `Vanish++ changed the server's own tab name: "${await serverName()}"`);
        await t.ask(`ltprobe listname ${v.username} &aOther ${v.username}`, /LTPROBE listname/, 3000);
        await t.until(() => /^\[VANISHED\] ?Other /.test(shown()), 3000, `the [VANISHED] tab prefix to follow a name change made while vanished (has "${shown()}")`);
        await t.vanish(v, false);
        await t.until(() => shown() === `Other ${v.username}`, 3000, `staff tab to show the plugin's name "Other ${v.username}" after unvanishing (has "${shown()}")`);
        t.check(await serverName() === `Other ${v.username}`, `the server's tab name was changed by unvanishing: "${await serverName()}"`);
      } finally { t.cmd(`ltprobe listname ${v.username} reset`); }
    },
  },
  {
    name: '/team: staff who lose the right to see a vanished player lose the [V] prefix, and get it back with the permission',
    async run(t) {
      const [v, s] = await t.bots(2);
      await t.op(s);
      const staff = clientTeams(t, s);
      await setupTeams(t, [v.username]);
      try {
        await t.vanish(v);
        await t.until(() => staff.tagged(v.username), 3000, 'control: staff client shows the [V] prefix');
        t.cmd(`deop ${s.username}`);
        await t.until(() => !staff.tagged(v.username), 3000, 'the [V] prefix to go once the staff member can no longer see the vanished player');
        await t.op(s);
        await t.until(() => staff.tagged(v.username), 3000, 'the [V] prefix to come back with the permission');
        await t.vanish(v, false);
        await t.until(() => staff.teamOf[v.username] === RED, 3000, `staff client to show ${v.username} on ${RED} after unvanishing`);
        noErrors(t, ['staff', staff]);
      } finally { removeTeams(t); }
    },
  },
];
module.exports.clientTeams = clientTeams;
