# Vanish++

<div align="center">

**The absolute standard for modern admin stealth on Paper/Folia servers.**

[![Modrinth](https://img.shields.io/modrinth/v/kbKpK1bc?label=Modrinth&logo=modrinth)](https://modrinth.com/plugin/vanish-plus-plus)
[![Modrinth Downloads](https://img.shields.io/modrinth/dt/kbKpK1bc?logo=modrinth)](https://modrinth.com/plugin/vanish-plus-plus)
[![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk)](https://adoptium.net/)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.6--26.2-brightgreen)](https://www.minecraft.net/)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

</div>

---

Vanish++ renders other vanish plugins obsolete. Built for modern **Paper** servers, it uses packet interception, native physics manipulation, and deep API hooks to make you **mathematically undetectable** — with first-class support for **Folia**, Purpur, Spigot, and Bukkit too.

Works out of the box with zero configuration required.

---

## Features

<details>
<summary><b>True Intangibility (Physics Engine)</b></summary>
<br>

Most plugins just hide you visually. Vanish++ removes you physically.

- **Invincibility:** No damage, immune to all potion effects, cannot burn.
- **Smart Mob AI:** Mobs completely ignore vanished players. Targeting is cancelled via `EntityTargetEvent` before the mob ever commits to an attack path. Mobs that had already locked on before you vanished are force-detargeted immediately.
- **Projectile Pass-Through:** Arrows, tridents, and snowballs fly physically through your body via native Paper events — impossible to hit a vanished player.
- **Zero Collision:** You cannot push players, mobs, or boats, and they cannot push you.
- **No Physical Triggers:** Walk over Turtle Eggs, Crops, Pressure Plates, Tripwires, and Sculk Sensors without triggering a single vibration.
- **Raid Prevention:** Won't trigger Bad Omen raids while watching a village.

</details>

<details>
<summary><b>Deep Protocol Invisibility (Packet-Level)</b></summary>
<br>

Hooks directly into the server protocol to scrub your existence from clients. *(Requires ProtocolLib)*

- **Nuclear Tab-Completion Scrubbing:** Tab-completing your name in chat, vanilla commands, or plugin commands returns nothing.
- **Server List Hiding:** Player count is mathematically adjusted. If you're the only one online, the server shows 0/20.
- **Ghost View for Staff:** Staff with permission see vanished players in the Tab List as gray, italicized spectators.
- **Staff Glow Indicator:** Vanished players render with a glowing outline visible only to staff — injected at the packet level.
- **Dynmap & EssentialsX Hooks:** Automatically hides you from web maps and `/who`, `/list`, `/online`.

</details>

<details>
<summary><b>Immersion & Compatibility</b></summary>
<br>

- **Native Language Fake Messages:** The fake "Player left the game" message uses the server's native translation packet — indistinguishable from a real disconnect in any language.
- **Native TAB Plugin Support:** Hooks directly into TAB (by NEZNAMY) to display your vanish prefix automatically.
- **Legacy Plugin Support:** Sets standard Bukkit Metadata (`vanished`), so CMI, TAB, and custom skripts automatically respect your vanish status.
- **Silent Chests:** Open containers silently — no animation, no sound, full item interaction. The live inventory is used, including overlapping staff sessions.
- **DiscordSRV Integration:** Suppresses join, quit, advancement, and death announcements on Discord. Fake messages honour DiscordSRV's full embed, color, avatar, and webhook configuration.
- **Simple Voice Chat Integration:** Filters voice packets while vanished, including modded clients and newly connected voice sessions. Authorized seers can still hear you.
- **Smart Item Pickup:** Toggle item pickup with `/vanishpickup`.

</details>

<details>
<summary><b>Granular Control & Safety</b></summary>
<br>

- **Spectator Quick-Switch:** Double-tap Shift while vanished to enter Spectator mode instantly. Requires `vanishpp.spectator`.
- **Vanish Scoreboard (`/vscoreboard`):** Fully configurable sidebar scoreboard showing world, TPS, player counts, real-time coordinates, direction, biome, ping, health, food, armor, time, date, and more. `%time%`/`%date%` respect `timezone` (IANA ID or `"default"` for server time) and `timezone-offset-hours` in `scoreboards.yml`. Coordinates refresh on movement via ProtocolLib packet listening. Supports all built-in placeholders plus full PlaceholderAPI.
- **Live Config Editor (`/vconfig`):** Edit any setting in `config.yml` directly in-game. Command completion requires `vanishpp.config` and never suggests database passwords, webhook authorization values, or webhook URLs. Applying local settings network-wide with `/vack apply_proxy` also requires `vanishpp.config`; dismissing notifications does not.
- **Personal Rules System (`/vrules`):** Per-player toggles for block breaking, entity interaction, chat confirmation, item pickup, mob targeting, and more.
- **Async Data Persistence:** Ordered state writes run asynchronously and are drained during clean shutdown. Completed writes survive restarts.
- **Database Connection Monitoring:** Staff notified in-game when database connectivity fails.
- **Native Velocity Proxy Plugin:** The companion `vanishpp-velocity` plugin provides a dedicated real-time messaging channel between all Paper servers and Velocity. Vanish state, config changes, and `/vanishreload` propagate network-wide instantly. Timed rule expiry notifications are delivered to whichever server the player is currently on — no reconnect required. Set `proxy.enabled: true` on secured Velocity backends and restart Paper to enable the handshake; standalone servers leave it off. Upgrade both jars together. See [proxy integration guide](PROXY_INTEGRATION_GUIDE.md).

</details>

---

## Commands

| Command | Alias | Description | Permission |
| :--- | :--- | :--- | :--- |
| `/vhelp [command]` | `/vanishhelp` | Interactive help menu & guide | *(none)* |
| `/vanish [player]` | `/v`, `/sv` | Toggle vanish state | `vanishpp.vanish` |
| `/vrules [player] <rule> [val]` | `/vanishrules` | Configure per-player rules | `vanishpp.rules` |
| `/vconfig <key> [val]` | `/vanishconfig` | Edit config settings live | `vanishpp.config` |
| `/vperms` | — | Manage permissions without a perm plugin | `vanishpp.manageperms` |
| `/vlist` | `/vanishlist` | Interactive list of vanished players | `vanishpp.list` |
| `/vignore [player]` | `/vanishignore` | Toggle startup warnings | `vanishpp.ignorewarning` |
| `/vchat confirm` | `/vanishchat` | Confirm a chat message (if safety is on) | `vanishpp.chat` |
| `/vreload` | `/vanishreload` | Reload config and resync all vanish effects | `vanishpp.reload` |
| `/vscoreboard` | — | Toggle the vanish sidebar scoreboard | `vanishpp.scoreboard` |
| `/vspec <player\|stop>` | — | Quick-spectate a player. `/vspec stop` to return. | `vanishpp.spec` |
| `/vfollow <player\|stop>` | — | Lock camera to silently follow a player. | `vanishpp.follow` |
| `/vhistory [player]` | — | View vanish/unvanish audit log. | `vanishpp.history` |
| `/vautovanish [player]` | — | Toggle auto-vanish on join for a player. | `vanishpp.autovanish` |
| `/vstats [player]` | — | View vanish time statistics. | `vanishpp.stats` |
| `/vadmin` | — | In-game dashboard GUI for vanish overview. | `vanishpp.admin` |
| `/vwand` | — | Give the vanish wand (Blaze Rod toggle item). | `vanishpp.wand` |
| `/vzone <create\|delete\|list\|reload>` | — | Manage no-vanish zones. | `vanishpp.zone` |
| `/vincognito [player] [fakename]` | — | Enable/disable fake name mode. | `vanishpp.incognito` |

---

## PlaceholderAPI

| Placeholder | Example | Description |
| :--- | :--- | :--- |
| `%vanishpp_is_vanished%` | `Yes` / `No` | Current status text |
| `%vanishpp_is_vanished_bool%` | `true` / `false` | Boolean status |
| `%vanishpp_vanished_count%` | `3` | Online vanished players this viewer may see |
| `%vanishpp_visible_online%` | `15` | Total minus vanished (fake count) |
| `%vanishpp_prefix%` | `[VANISHED]` | Configured prefix (empty if visible) |
| `%vanishpp_pickup%` | `Enabled` | Current item pickup status |
| `%vanishpp_vanished_list%` | `Notch, Herobrine` | List of online vanished names |
| `%vanishpp_visible_player_list%` | `Steve, Alex` | List of online non-vanished (visible) player names |

---

## Personal Rules (`/vrules`)

| Rule | Default | Description |
| :--- | :--- | :--- |
| `can_break_blocks` | `false` | Allow breaking blocks while vanished |
| `can_place_blocks` | `false` | Allow placing blocks while vanished |
| `can_interact` | `true` | Allow interacting (chests, buttons) |
| `can_hit_entities` | `false` | Allow hitting players/mobs |
| `can_pickup_items` | `false` | Allow picking up items |
| `can_drop_items` | `false` | Allow dropping items from inventory |
| `can_chat` | `false` | Requires confirmation to speak |
| `can_trigger_physical` | `false` | Pressure plates, crops, etc. |
| `can_throw` | `false` | Throw items, shoot bows |
| `mob_targeting` | `false` | Mobs ignore you |
| `spectator_gamemode` | `true` | Double-tap Shift to enter Spectator |
| `show_notifications` | `true` | Action-blocked warnings |

---

## Requirements & Compatibility

**Requirements:**
- Java 21
- Paper 1.20.6+ (or a compatible fork)
- ProtocolLib 5.3.0+ *(highly recommended — required for stealth features)*

**Supported platforms:**

| Platform | Status | Notes |
| :--- | :--- | :--- |
| **Paper** | Recommended | Full feature support |
| **Purpur** | Supported | Paper fork, fully compatible |
| **Folia** | Supported | Multi-region scheduler bridge, automatic detection |
| **Spigot** | Compatible | Physics/projectile features degrade without Paper API |
| **Bukkit** | Compatible | Same limitations as Spigot |

**Supported versions:** Minecraft 1.20.6 — 26.2

**Optional integrations:** TAB (NEZNAMY), PlaceholderAPI, Dynmap, EssentialsX, DiscordSRV, Statusbot, Simple Voice Chat

**Storage options:** YAML (default), MySQL 5.7+, PostgreSQL 12+, Redis (cross-server sync)

Vanish state writes are ordered and drained before storage closes. Failed writes retry the latest accepted decision with backoff; superseded retries cannot overwrite newer toggles. A failed database read retains known hidden state and retries. Failed SQL initialization retains that provider for recovery rather than falling back to unrelated YAML; SQL stays unavailable until migration, deadline recovery, and acknowledgements have finished; a login with no safe cached state must reconnect after storage recovers. Reload keeps following, mob checks, bridge retries, and other feature timers running. Equipped armour is never removed when unvanishing.

Original gamemodes are saved with player data before plugin-entered spectator changes, so restoring them needs no database read. Temporary-rule deadlines and warning acknowledgements survive reconnects and restarts. A newer rule decision cancels an older expiry, including shared SQL changes. Automatic migration to an empty backend copies preferences, presets, history, statistics, rules, levels, acknowledgements, and vanish state in either direction. YAML reload reuses its existing file owner. Acknowledgements, preferences, presets and state mutations share the ordered worker with storage migration; the previous provider stays open until migration completes. History is copied from one stable snapshot, including rows with identical timestamps. SQL destination copies commit as one transaction, so an interrupted copy rolls back and retries without duplicated history. YAML copies stage a complete data/history checkpoint before replacing either file; an interrupted installation is replayed on startup. Failed deadline scans retain the previous schedules and retry; replaced timers are cancelled.

The Velocity companion persists vanish and explicit unvanish decisions in `state.yml`, including with default YAML storage. During bootstrap it imports unknown vanished players from backends without overriding known proxy decisions. Proxy state takes precedence over backend YAML on login. With `proxy.enabled`, joins remain hidden until a targeted state reply arrives; a five-second timeout requires reconnecting instead of revealing unknown state. Server switches wait up to five seconds for the source backend to settle its accepted toggle before the destination can query state. An unanswered transfer is denied, and new source-side toggles are held until confirmed detach or cancellation, including when the connection takes longer than ten seconds. Ordinary visible joins preserve spectator mode and flight. Queued local toggles are protected until the proxy acknowledges them; unacknowledged updates are retried and older revisions are ignored. Network configuration patches are saved to proxy `config.yml`. Override order is global defaults, `all`, matching comma-separated groups (alphabetical key order), then an exact server name; Velocity resolves these once. Redis subscriptions reconnect with backoff from 250 ms to 30 seconds and close their connection on shutdown. Shared SQL state is reconciled behind queued writes when a subscription recovers; unsettled local decisions are preserved. Shutdown attempts every final session save and closes resources even if one save fails, logging each failure.


---

## Installation

1. Download the latest release from [Modrinth](https://modrinth.com/plugin/vanish-plus-plus)
2. Place `vanishpp-x.x.x.jar` in your server's `plugins/` folder
3. Start or restart your server
4. *(Optional)* Install ProtocolLib for full stealth feature support

No configuration required to get started. Run `/vhelp` in-game to explore.

---

## Building from Source

```bash
# Clone the repository
git clone https://github.com/TheCommandCraft/Vanishpp.git
cd Vanishpp

# Build (requires Java 21 and Maven)
mvn clean package -DskipTests

# Output JAR
ls target/vanishpp-*.jar
```

---

## Contributing

Pull requests are welcome. For significant changes, please open an issue first to discuss what you'd like to change.

Before submitting a PR, make sure the full test suite passes:

```bash
mvn clean verify
```

---

## License

This project is licensed under the [GNU General Public License v3.0](LICENSE).

As a Spigot/Bukkit plugin, GPL v3 is required for compliance with the Bukkit API license.
