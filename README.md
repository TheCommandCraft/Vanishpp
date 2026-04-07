# Vanish++

<div align="center">

**The absolute standard for modern admin stealth on Paper/Folia servers.**

[![Modrinth](https://img.shields.io/modrinth/v/kbKpK1bc?label=Modrinth&logo=modrinth)](https://modrinth.com/plugin/vanish++)
[![Modrinth Downloads](https://img.shields.io/modrinth/dt/kbKpK1bc?logo=modrinth)](https://modrinth.com/plugin/vanish++)
[![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk)](https://adoptium.net/)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21--1.21.x-brightgreen)](https://www.minecraft.net/)
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
- **Smart Mob AI:** Custom AI goals injected into mobs — they look right through you, no head tracking, no awkward staring. Mobs that had already locked on to you before you vanished instantly lose you.
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
- **Silent Chests:** Open containers silently — no animation, no sound, full item interaction. Changes sync back on close.
- **DiscordSRV Integration:** Suppresses join, quit, advancement, and death announcements on Discord. Fake messages honour DiscordSRV's full embed, color, avatar, and webhook configuration.
- **Simple Voice Chat Integration:** Automatically isolates you in voice chat while vanished.
- **Smart Item Pickup:** Toggle item pickup with `/vanishpickup`.

</details>

<details>
<summary><b>Granular Control & Safety</b></summary>
<br>

- **Spectator Quick-Switch:** Double-tap Shift while vanished to enter Spectator mode instantly. Requires `vanishpp.spectator`.
- **Vanish Scoreboard (`/vscoreboard`):** Fully configurable sidebar scoreboard showing world, TPS, player counts, real-time coordinates, direction, biome, ping, health, food, armor, and more. Coordinates refresh on movement via ProtocolLib packet listening. Supports all built-in placeholders plus full PlaceholderAPI.
- **Live Config Editor (`/vconfig`):** Edit any setting in `config.yml` directly in-game.
- **Personal Rules System (`/vrules`):** Per-player toggles for block breaking, entity interaction, chat confirmation, item pickup, mob targeting, and more.
- **Async Data Persistence:** All data saved asynchronously. State is preserved across restarts.
- **Database Connection Monitoring:** Staff notified in-game when database connectivity fails.
- **Proxy-Ready Cross-Server Sync:** Vanish state is pre-fetched from the shared database during login — players switching servers on a BungeeCord/Velocity network appear vanished or visible to staff instantly, with zero flicker or catch-up delay. See [proxy integration guide](PROXY_INTEGRATION_GUIDE.md).

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

---

## PlaceholderAPI

| Placeholder | Example | Description |
| :--- | :--- | :--- |
| `%vanishpp_is_vanished%` | `Yes` / `No` | Current status text |
| `%vanishpp_is_vanished_bool%` | `true` / `false` | Boolean status |
| `%vanishpp_vanished_count%` | `3` | Online vanished player count |
| `%vanishpp_visible_online%` | `15` | Total minus vanished (fake count) |
| `%vanishpp_prefix%` | `[VANISHED]` | Configured prefix (empty if visible) |
| `%vanishpp_pickup%` | `Enabled` | Current item pickup status |
| `%vanishpp_vanished_list%` | `Notch, Herobrine` | List of online vanished names |

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
- Paper 1.21+ (or a compatible fork)
- ProtocolLib 5.3.0+ *(highly recommended — required for stealth features)*

**Supported platforms:**

| Platform | Status | Notes |
| :--- | :--- | :--- |
| **Paper** | Recommended | Full feature support |
| **Purpur** | Supported | Paper fork, fully compatible |
| **Folia** | Supported | Multi-region scheduler bridge, automatic detection |
| **Spigot** | Compatible | Physics/projectile features degrade without Paper API |
| **Bukkit** | Compatible | Same limitations as Spigot |

**Supported versions:** Minecraft 1.21 — 1.21.x

**Optional integrations:** TAB (NEZNAMY), PlaceholderAPI, Dynmap, EssentialsX, DiscordSRV, Simple Voice Chat

**Storage options:** YAML (default), MySQL 5.7+, PostgreSQL 12+, Redis (cross-server sync)

---

## Installation

1. Download the latest release from [Modrinth](https://modrinth.com/plugin/vanish++)
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
