# Live bot tests

These tests start a real Paper server with the built Vanish++ jar and ProtocolLib, then check the plugin with
bot players ([mineflayer](https://github.com/PrismarineJS/mineflayer)). They check what a real client receives
and shows, not what the plugin's Java code believes it did. A hook that never fires, a wrong API or a
packet that slips through fails a test even when no error is logged.

One server and one pool of bots serve the whole run. The server runs on `127.0.0.1` on the same machine. GitHub
Actions runs the tests on every pull request and on every push to `main`.

## Running them

```bash
mvn -DskipTests package -pl vanishpp-paper -am
live-tests/run.sh                    # all tests against the built plugin
live-tests/run.sh --only chest       # only tests whose file or name contains "chest"
live-tests/run.sh --mutants          # test the tests (see below)
```

You need Java 21 or newer, Maven and Node 20 or newer. `JAVA=` and `MVN=` select specific binaries. Paper and
ProtocolLib are downloaded once into `.cache/`, and their checksums are pinned in `run.sh`. Paper's patched files
are kept in `.work/server/`, so only the first run pays for the patching.

## Testing the tests: mutants

A test that cannot fail proves nothing. `mutants.js` lists **mutants**. Each mutant breaks exactly one feature
in the plugin source, for example "chest lid animations are not suppressed". `--mutants` builds one test jar
with every mutant built in and switched off. It runs all tests on that jar (all must pass), then switches each
mutant on in turn and reruns the tests listed for it. At least one of them must fail. If none does, the
mutant **survived**, and the tests do not really check that feature. Every test that is not a `guard` must be
covered by a mutant.

When a mutant's edit no longer matches the plugin code, the build fails and names the edit to update.

CI runs the mutants when the tests change, when a pull request touches a file a mutant patches, weekly, and on
demand.

## What a real client sees

The checks follow the vanilla client (26.1, which is no longer obfuscated) rather than mineflayer's view:

- **Tab list:** only player entries marked *listed* (`ClientPacketListener#getListedOnlinePlayers`).
- **Name completion:** uses every player entry, listed or not (`ClientSuggestionProvider#getOnlinePlayerNames`).
  So a vanished player must be removed from the player list completely, not just unlisted.
- **Chest lids and sounds:** come from `block_action`, `sound_effect` and `entity_sound_effect` packets.
- **Block breaking:** mineflayer predicts the result, so tests read it from the server (`t.blockIs`).

## Writing a test

Each file in `scenarios/` exports a list of `{ name, run(t), timeout?, guard? }`. A test gets its own patch of
the flat world (`t.area`). Bots come from a shared pool and are reset after every test: unvanished, deopped,
survival, no effects, empty inventory, full health and default rules. The reset is checked on the server. Bots
that a test does not use wait as spectators, so they do not count for sleeping and mobs ignore them.

Helpers:

- `t.bots(n)`: n clean bots. `t.op(bot)`, `t.place(bot, dx, dz)`, `t.vanish(bot, on)`.
- `t.cmd(command)`: any console command.
- `t.gamerule(rule, value)` and `t.config(key, value, original)`: fail if the server does not confirm them, and
  are undone after the test where needed.
- `t.listen(bot, packet, fn)`: a raw packet listener that is removed after the test.
- `t.blockIs(x, y, z, block)`, `t.targets()`, `t.isVanished(bot)`: read facts from the server.
- `t.until(fn, ms, what)`, `t.check(cond, message)`, `t.wait(ms)`.

Start each test with a control step that shows the check can fail. For example, confirm the observer sees the
player before checking that they are hidden. Then add a mutant for the feature to `mutants.js`. A test also
fails if the server logs a Vanish++, ProtocolLib or helper error while it runs.

`helper/` is a small test-only plugin. It provides a command that suggests every online name, the way a
careless plugin does, and console probes for server-side facts the bots cannot see.
