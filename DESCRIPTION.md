# Vanish++
<div align="center">

## 👻 The Absolute Standard for Modern Admin Stealth.

**Stop getting caught. Start watching.**

</div>

---

Are you tired of "vanish" plugins that leave traces? Players tab-completing your name? Arrows bouncing off your invisible body? Mobs looking strangely at "empty" air?

**Vanish++** renders other vanish plugins obsolete. Built for modern **Paper**, **Folia**, **Purpur**, **Spigot**, and **Bukkit** servers, it uses advanced packet interception, native physics manipulation, and deep API hooks to ensure you are **mathematically undetectable**.

It works perfectly out of the box with zero configuration required, but offers granular control for those who need it.

---

## 🚀 Why Vanish++ is Unrivaled

<details>
<summary><b>🛡️ The "Matrix" Physics Engine (True Intangibility)</b></summary>
<br>

Most plugins just hide you visually. **Vanish++ removes you physically.**

*   **Titan God Mode:** While vanished, you are strictly invincible. You take no damage, are immune to all potion effects, and cannot burn. You are a spectator in survival mode.
*   **Smart Mob AI (True Sight):** Mobs completely ignore vanished players. Targeting is cancelled via `EntityTargetEvent` before the mob ever commits to an attack path. Mobs that had already locked on before you vanished are force-detargeted immediately.
*   **Projectile Pass-Through:** We don't use "teleport hacks." Using native Paper events, arrows, tridents, and snowballs fly **physically through** your body. It is impossible to hit a vanished player.
*   **Zero Collision:** You cannot push players, mobs, or boats, and they cannot push you. You are a ghost.
*   **No Physical Triggers:** You can walk over Turtle Eggs, Crops, Pressure Plates, Tripwires, and Sculk Sensors without triggering a single vibration or block update.
*   **Raid Prevention:** You won't trigger Bad Omen raids while watching a village.

</details>

<details>
<summary><b>📶 Deep Protocol Invisibility (Packet-Level Hiding)</b></summary>
<br>

We hook directly into the server protocol to scrub your existence from clients. *(Requires ProtocolLib)*

*   **Nuclear Tab-Completion Scrubbing:** If a normal player tries to Tab-Complete your name in Chat, Vanilla Commands, or Plugin Commands, **you are not there**. Your name is stripped from the packet sent to the client.
*   **Server List Hiding:** The player count in the multiplayer server list is mathematically adjusted. If you are the only one online, the server says "0/20".
*   **Ghost View for Staff:** While normal players see nothing, Staff (with permission) see vanished players in the Tab List as **Gray, Italicized Spectators**, making it easy to coordinate.
*   **Staff Glow Indicator:** Vanished players render with a glowing outline for staff — a clear visual indicator injected at the packet level. Non-staff never see it. Configurable via `vanish-appearance.staff-glow`.
*   **Dynmap & EssentialsX Hooks:** Automatically hides you from dynamic web maps and `/who`, `/list`, or `/online` commands.

</details>

<details>
<summary><b>👁️ Immersion & Compatibility</b></summary>
<br>

*   **Native Language Fake Messages:** When you vanish, the fake "Player left the game" message isn't just text—it uses the **server's native translation packet**. This means a German player sees the message in German, and a US player sees it in English. It is indistinguishable from a real disconnect.
*   **Native TAB Plugin Support:** If you use **TAB (by NEZNAMY)**, Vanish++ hooks directly into it to display your vanish prefix automatically. No manual Placeholder configuration required.
*   **Legacy Plugin Support:** Even without specific hooks, Vanish++ sets standard Bukkit Metadata (`"vanished"`). This means plugins like **CMI**, **TAB**, or custom skripts automatically respect your vanished status.
*   **Silent Chests:** Open Chests, Shulker Boxes, Barrels, and Ender Chests silently. The container opens directly against the real inventory — no animation, no sound, no item duplication.
*   **DiscordSRV Integration:** Registers as a native vanish hook in DiscordSRV. Join, quit, advancement, and death announcements are all suppressed on Discord — even if you reconnect while already vanished. Fake join/leave messages honour DiscordSRV's full embed, colour, avatar, and webhook configuration from `messages.yml`. Staff notifications still appear in console and for players with the see permission.
*   **Simple Voice Chat Integration:** Automatically isolates/mutes you in voice chat so you can't be heard or hear proximity chat while stalking.
*   **Smart Item Pickup:** Toggle item pickup with `/vanishpickup`. Don't accidentally steal the diamonds you are watching a player mine.
*   **`/msg` / `/tell` Detection Prevention:** Non-staff can no longer `/msg` or `/tell` a vanished player — they receive a vanilla-style "player not found" error. `/r` reply is blocked when the last target was a vanished sender. `/me` from a vanished player is restricted to staff-only audience. Configurable message text.

</details>

<details>
<summary><b>🔧 Granular Control & Safety</b></summary>
<br>

*   **Spectator Quick-Switch:** Double-tap Shift while vanished to enter Spectator mode instantly. Double-tap again to return to your previous gamemode. Unvanishing forces you back automatically. Requires `vanishpp.spectator`. (`vanishpp.spectator.bypass` lets you stay in Spectator after unvanishing.)
*   **`/vspec` Quick-Spectate:** Instantly enter spectator mode locked to any player with `/vspec <player>`. Use `/vspec stop` to return to your previous location and gamemode. Requires `vanishpp.spec`.
*   **`/vfollow` Camera Tracking:** Lock your camera to silently follow any player with `/vfollow <player>`. A HUD indicator shows the active target. Stops automatically if the target disconnects. Requires `vanishpp.follow`.
*   **`/vhistory` Audit Log:** Full vanish/unvanish history with timestamps, executor, and reason stored in the database. Configurable retention. Requires `vanishpp.history`.
*   **`/vstats` Vanish Time Statistics:** View total vanish time, session count, and longest session per player. Requires `vanishpp.stats`.
*   **`/vadmin` Dashboard GUI:** In-game GUI overview of all vanished players, active rules, and quick actions. Requires `vanishpp.admin`.
*   **`/vincognito` Fake Name Mode:** Replace your display name and tab entry with a configurable fake name while vanished. Requires `vanishpp.incognito`.
*   **`/vwand` Toggle Wand:** Grants a configurable vanish wand — right-click to toggle vanish state, shift-right-click to open the rules GUI. Requires `vanishpp.wand`.
*   **`/vzone` No-Vanish Zones:** Define radius-based zones where vanishing/unvanishing is blocked or forced. Manage with `/vzone create|delete|list|reload`. Requires `vanishpp.zone`.
*   **`/vautovanish` Auto-Join Vanish:** Players opt into automatic vanish on join. Persisted per UUID — survives restarts and server switches. Requires `vanishpp.autovanish`.
*   **Timed Vanish:** `/vanish [player] <duration>` (e.g. `30s`, `5m`, `2h`) auto-unvanishes after the timer expires. Remaining time is visible in the action bar. Cancel early with `/vanish cancel [player]`.
*   **AFK Auto-Vanish:** Automatically vanishes players after a configurable idle threshold. Players are notified via action bar; they are unvanished the moment they move again.
*   **Anti-Combat Vanish:** Configurable cooldown that blocks `/vanish` after dealing or receiving PvP or PvE damage. Separate timers for each. `vanishpp.combat.bypass` to skip.
*   **Vanish Rate Limit:** Prevents rapid toggle cycling. Configurable minimum seconds between toggles. `vanishpp.ratelimit.bypass` to skip.
*   **Partial Visibility:** `/vanish --visible <player>[,...]` makes you visible only to the listed players — everyone else still can't see you. Shown as `◑` in `/vlist`. Collapse to full vanish with `/vanish --visible none`.
*   **Bulk Vanish:** `/vanish all` vanishes every eligible online player at once. `/vanish world <world>` targets all players in a specific world. Vanish reason tracking: `/vanish <player> [reason]` records a reason visible to staff via `/vhistory`.
*   **Bossbar Vanish Indicator:** Optional persistent bossbar shown to vanished players as a stealth reminder. Configurable colour, style, and text in `config.yml`.
*   **Staff Sounds:** Play configurable sounds to all `vanishpp.see` holders on vanish, unvanish, silent join, and silent quit. Separate sound, volume, and pitch per event.
*   **Per-World Rule Defaults:** Override any vanish rule for specific worlds via `world-rules:` in `config.yml`. Applied on world entry, restored on exit — ideal for staff worlds or PvP arenas.
*   **Live Config Editor (`/vconfig`):** Edit any setting in `config.yml` (Messages, Rules, Boolean toggles) directly in-game. Changes apply instantly without reloading.
*   **Config Validation:** Every constrained config field is validated on load and reload. Bad values fall back to defaults in-game without touching the file. Ops and `vanishpp.config` holders are notified in chat with the exact path, invalid value, valid options, and applied default.
*   **Interactive Help (`/vhelp`):** Forget the wiki. The plugin includes a clickable, interactive guide explaining every command and feature.
*   **Smart-Merge Migration:** Updates are stress-free. The plugin automatically detects old configs and migrates your custom messages/settings to the new version structure safely. Missing message keys are auto-written to `messages.yml` on load — upgrading never leaves a key undefined.
*   **Personal Rules System (`/vrules`):** Decide exactly what you want to do while vanished. Supports named presets — save, load, list, and delete rule configurations with `/vrules preset <save|load|list|delete> <name>`.
    *   *Want to break blocks?* Toggle it.
    *   *Want absolute peace?* Disable "Can Hit Entities".
    *   *Afraid of leaking info?* Enable "Chat Confirmation".
    *   *Need to drop items?* Enable "Can Drop Items".
*   **Vanish Scoreboard (`/vscoreboard`):** A fully configurable sidebar scoreboard shown automatically when you vanish. Displays world, TPS, player counts, real-time coordinates and direction, biome, ping, health, food, armor, time, and more. Coordinates refresh the instant you move via packet-level ProtocolLib listening — no tick lag. Supports all built-in placeholders plus full PlaceholderAPI. Column separators (`|`) are auto-aligned regardless of label length. Configured in `scoreboards.yml`, reloads with `/vreload`.
*   **Heartbeat Synchronization:** Changed a permission in LuckPerms? Promoted someone via Console? The **Heartbeat Task** refreshes visuals instantly. No relogging required.
*   **Setup Advisor:** On every startup, the plugin scans the active config and warns you if a hook is enabled but its dependency is missing, or if a PlaceholderAPI placeholder is used without PlaceholderAPI installed. Warnings are printed to the console *and* shown in-chat to all staff with `vanishpp.see` — so you always know when your setup is incomplete.
*   **Dependency Warnings:** The plugin intelligently warns admins if ProtocolLib is missing, but allows you to silence these warnings permanently with `/vignore`.
*   **Accidental Chat Prevention:** If enabled, typing in chat blocks the message and asks you to confirm with `/vchat confirm`. Never leak your presence by accident again.
*   **Async Data Persistence:** All data is saved asynchronously. Server crash? Restart? Your vanish state is saved instantly. No accidental logins.
*   **Native Velocity Proxy Plugin:** The companion `vanishpp-velocity` plugin provides a dedicated real-time messaging channel between all Paper servers and Velocity. Vanish state, config changes, and `/vanishreload` propagate network-wide instantly. Staff are notified on-screen when a player vanishes or unvanishes on *any* connected server. Network-wide `/vlist` shows all vanished players with their server name. Timed rule expiry notifications are delivered to the server the player is currently on — no reconnect required. Servers auto-detect the proxy on startup and fall back to standalone mode if none is present.
*   **Proxy Config GUI (`/vproxygui`):** In-game 54-slot inventory GUI for editing the Velocity proxy config live. Toggles update the value, save the file, and sync the change to all connected Paper backends in real time. Requires `vanishpp.config`.
*   **Database Connection Monitoring:** When database connectivity fails, staff are notified in-game so infrastructure issues don't go unnoticed. Includes graceful error handling and connection pooling.

</details>

<details>
<summary><b>🔌 Developer & Integration APIs</b></summary>
<br>

*   **Public VanishAPI:** A clean developer API (`VanishAPI`) exposes vanish state queries, vanish/unvanish calls, event hooks, and rule reads for third-party plugin integration. No internals required.
*   **LuckPerms Context Integration:** Registers a `vanished` context node in LuckPerms so permissions can be conditionally granted or revoked while a player is vanished.
*   **WorldGuard Region Flags:** Two new WorldGuard flags: `vanishpp-force-vanish` (auto-vanishes players entering the region) and `vanishpp-deny-vanish` (blocks toggling vanish inside the region).
*   **Webhook Support:** Configurable HTTP webhooks fire on vanish/unvanish events for external integrations — Discord bots, dashboards, audit systems, anything. Optional `Authorization` header. Template variables: `{player}`, `{uuid}`, `{action}`, `{reason}`, `{server}`, `{timestamp}`.
*   **Shift-Right-Click Invsee:** Shift-right-clicking a player while vanished opens their inventory via OpenInv or InvSee++ if installed, falling back to the built-in viewer. Permissions are granted for the duration and revoked on close.
*   **Geyser / Floodgate Bedrock Forms:** Bedrock players using Geyser/Floodgate receive native Bedrock UI — a ModalForm for the vanish toggle and a SimpleForm button list for `/vrules`. Triggered automatically from the vanish wand and `/vrules`. Graceful fallback when Floodgate is absent.

</details>

---

## 📋 Commands

Most commands support an optional `[player]` argument, allowing admins to modify the state/rules of other staff members.

| Command | Alias | Description | Permission |
| :--- | :--- | :--- | :--- |
| `/vhelp [command]` | `/vanishhelp` | Interactive help menu & guide. | `no permission` |
| `/vanish [player] [duration\|reason\|--visible]` | `/v`, `/sv` | Toggle vanish. Supports `/vanish all`, `/vanish world <w>`, `/vanish <player> 5m`, `/vanish --visible <list>`. | `vanishpp.vanish` |
| `/vrules [player] <rule> [val] [seconds]` | `/vanishrules` | Configure physics/interaction rules. Supports presets. | `vanishpp.rules` |
| `/vconfig <key> [val]` | `/vanishconfig` | Edit config settings live. | `vanishpp.config` |
| `/vperms` | - | Manage permissions without a perm plugin. | `vanishpp.manageperms` |
| `/vlist` | `/vanishlist` | Interactive list of vanished players (all servers on Velocity). Click to unvanish. | `vanishpp.list` |
| `/vignore [player]` | `/vanishignore` | Toggle start-up warnings. | `vanishpp.ignorewarning` |
| `/vchat confirm` | `/vanishchat` | Confirm a chat message (if safety is on). | `vanishpp.chat` |
| `/vreload` | `/vanishreload` | Reload config and resync all vanish effects. | `vanishpp.reload` |
| `/vscoreboard` | `/vsb` | Toggle the vanish sidebar scoreboard. | `vanishpp.scoreboard` |
| `/vspec <player\|stop>` | - | Quick-spectate a player. `/vspec stop` to return. | `vanishpp.spec` |
| `/vfollow <player\|stop>` | - | Lock camera to silently follow a player. | `vanishpp.follow` |
| `/vhistory [player]` | - | View vanish/unvanish audit log. | `vanishpp.history` |
| `/vautovanish [player]` | - | Toggle auto-vanish on join for a player. | `vanishpp.autovanish` |
| `/vstats [player]` | - | View vanish time statistics. | `vanishpp.stats` |
| `/vadmin` | - | In-game dashboard GUI for vanish overview. | `vanishpp.admin` |
| `/vwand` | - | Give the vanish wand (right-click to toggle, shift-right-click for rules GUI). | `vanishpp.wand` |
| `/vzone <create\|delete\|list\|reload>` | - | Manage no-vanish zones. | `vanishpp.zone` |
| `/vincognito [player] [fakename]` | - | Enable/disable fake name mode. | `vanishpp.incognito` |
| `/vproxygui` | - | Open the Velocity proxy config GUI (live editor). | `vanishpp.config` |

---

## 🧩 Placeholders (PlaceholderAPI)

Only needed if you are building custom HUDs or Scoreboards. (TAB Plugin works automatically without these).

| Placeholder | Output Example | Description |
| :--- | :--- | :--- |
| `%vanishpp_is_vanished%` | `Yes` / `No` | Current player's vanish status as text. |
| `%vanishpp_is_vanished_bool%` | `true` / `false` | Boolean vanish status for logic/conditions. |
| `%vanishpp_is_vanished_<player>%` | `true` / `false` | Vanish status of any named online player. |
| `%vanishpp_vanished_count%` | `3` | Number of online vanished players. |
| `%vanishpp_visible_online%` | `15` | Total players minus vanished players (fake count for HUDs). |
| `%vanishpp_prefix%` | `[VANISHED]` | Configured vanish prefix (empty if visible). |
| `%vanishpp_pickup%` | `Enabled` | Current item pickup rule status. |
| `%vanishpp_vanished_list%` | `Notch, Herobrine` | Comma-separated list of online vanished player names. |
| `%vanishpp_visible_player_list%` | `Steve, Alex` | Comma-separated list of online non-vanished (visible) player names. |
| `%vanishpp_vanish_duration%` | `5m 12s` | How long the current player has been vanished. |
| `%vanishpp_vanish_reason%` | `Checking grief` | Current vanish reason, or empty string. |
| `%vanishpp_vanish_level_<player>%` | `3` | Vanish level of any named online player. |
| `%vanishpp_rule_<rule>%` | `true` / `false` | Current value of a vanish rule for the requesting player. |
| `%vanishpp_can_see_<player>%` | `true` / `false` | Whether the requesting player can see the named player. |
| `%vanishpp_time%` | `3:45 PM` / `15:45` | Current server time. Format controlled by `time-format` in `config.yml` (`12h` or `24h`). |

---

## 🔒 Personal Rules (`/vrules`)

Customize your ghost experience. Default behavior can be tweaked per player.

*   `can_break_blocks` (Default: `false` - Cannot break blocks)
*   `can_place_blocks` (Default: `false` - Cannot place blocks)
*   `can_interact` (Default: `false` - Chests, Buttons)
*   `can_hit_entities` (Default: `false` - Prevents hitting players/mobs)
*   `can_pickup_items` (Default: `false` - Cannot pick up items)
*   `can_drop_items` (Default: `false` - Cannot drop items from inventory)
*   `can_chat` (Default: `false` - Requires confirmation to speak)
*   `can_trigger_physical` (Default: `false` - Pressure plates, crops, etc.)
*   `can_throw` (Default: `false` - Cannot throw items like eggs, snowballs, ender pearls, or shoot bows)
*   `mob_targeting` (Default: `false` - Mobs ignore you)
*   `spectator_gamemode` (Default: `true` - Double-tap Shift to toggle Spectator mode while vanished)
*   `show_notifications` (Default: `true` - Receive action-blocked warnings in chat)

---

## ⚙️ Compatibility & Requirements

Vanish++ is built for modern ecosystems.

### Supported Versions
| Minecraft Version      | Status               | Notes                                                              |
| :--------------------- | :------------------- | :----------------------------------------------------------------- |
| **1.21 — 1.21.11**    | ✅ Supported         | Native support. Built against Paper 1.21 API, tested on 1.21.11.  |
| **1.20.4 and older**  | ❌ Unsupported       | Incompatible API changes. Use older Vanish++ versions.             |

### Supported Platforms
| Platform            | Status               | Notes                                                              |
| :------------------ | :------------------- | :----------------------------------------------------------------- |
| **Paper**           | ✅ Recommended       | Best performance. Required for full physics/projectile support.    |
| **Purpur**          | ✅ Supported         | Fully compatible (Paper fork).                                     |
| **Folia**           | ✅ Supported         | Multi-region scheduler bridge with automatic runtime detection. Full support for regional execution. Tested on 1.21.x. |
| **Spigot**          | ⚠️ Compatible        | Works, but Paper-specific features (projectile passthrough) degrade. |
| **Bukkit**          | ⚠️ Compatible        | Same limitations as Spigot.                                        |

**Requirements:**
*   **Java 21**
*   **ProtocolLib 5.3.0+** (Highly Recommended for Stealth)

**Storage (Optional):**
*   **YAML** (Default) - File-based storage, built-in
*   **MySQL 5.7+** - Network database support
*   **PostgreSQL 12+** - Network database support
*   **Redis** (Optional) - For cross-server vanish state synchronization

**Optional Hooks:**
*   **TAB (NEZNAMY)** (Native Support)
*   PlaceholderAPI, Dynmap, EssentialsX, Simple Voice Chat, DiscordSRV, LuckPerms, WorldGuard, OpenInv / InvSee++, Geyser / Floodgate (Bedrock UI)

**Just drop the JAR in your plugins folder.** No complex setup required. It works securely out of the box.

---

<div align="center">

### 📄 License & Support

This project is open-source under the GNU GPL v3.
Report bugs via the **Issues** tab.

**[ Download Now ]**
*And become truly invisible.*

</div>
