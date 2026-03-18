# Vanish++
<div align="center">

## 👻 The Absolute Standard for Modern Admin Stealth.

**Stop getting caught. Start watching.**

</div>

---

Are you tired of "vanish" plugins that leave traces? Players tab-completing your name? Arrows bouncing off your invisible body? Mobs looking strangely at "empty" air?

**Vanish++** renders other vanish plugins obsolete. Built exclusively for modern **Paper** servers, it utilizes advanced packet interception, native physics manipulation, and deep API hooks to ensure you are **mathematically undetectable**.

It works perfectly out of the box with zero configuration required, but offers granular control for those who need it.

---

## 🚀 Why Vanish++ is Unrivaled

<details>
<summary><b>🛡️ The "Matrix" Physics Engine (True Intangibility)</b></summary>
<br>

Most plugins just hide you visually. **Vanish++ removes you physically.**

*   **Titan God Mode:** While vanished, you are strictly invincible. You take no damage, are immune to all potion effects, and cannot burn. You are a spectator in survival mode.
*   **Smart Mob AI (True Sight):** We inject custom AI goals into mobs. They don't just "not target" you—they look right through you. No head tracking, no awkward staring. Mobs that had already locked on to you before you vanished also instantly lose you.
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
*   **Silent Chests:** Open Chests, Shulker Boxes, Barrels, and Ender Chests silently. The container is opened as a snapshot inventory — no animation, no sound, full item interaction. Changes sync back on close.
*   **DiscordSRV Integration:** Registers as a native vanish hook in DiscordSRV. Join and quit announcements are suppressed on Discord — even if you reconnect while already vanished. Staff notifications still appear in console and for players with the see permission.
*   **Simple Voice Chat Integration:** Automatically isolates/mutes you in voice chat so you can't be heard or hear proximity chat while stalking.
*   **Smart Item Pickup:** Toggle item pickup with `/vanishpickup`. Don't accidentally steal the diamonds you are watching a player mine.

</details>

<details>
<summary><b>🔧 Granular Control & Safety</b></summary>
<br>

*   **Live Config Editor (`/vconfig`):** Edit any setting in `config.yml` (Messages, Rules, Boolean toggles) directly in-game. Changes apply instantly without reloading.
*   **Interactive Help (`/vhelp`):** Forget the wiki. The plugin includes a clickable, interactive guide explaining every command and feature.
*   **Smart-Merge Migration:** Updates are stress-free. The plugin automatically detects old configs and migrates your custom messages/settings to the new version structure safely.
*   **Personal Rules System (`/vrules`):** Decide exactly what you want to do.
    *   *Want to break blocks?* Toggle it.
    *   *Want absolute peace?* Disable "Can Hit Entities".
    *   *Afraid of leaking info?* Enable "Chat Confirmation".
    *   *Need to drop items?* Enable "Can Drop Items".
*   **Heartbeat Synchronization:** Changed a permission in LuckPerms? Promoted someone via Console? The **Heartbeat Task** refreshes visuals instantly. No relogging required.
*   **Setup Advisor:** On every startup, the plugin scans the active config and warns you if a hook is enabled but its dependency is missing, or if a PlaceholderAPI placeholder is used without PlaceholderAPI installed. Warnings are printed to the console *and* shown in-chat to all staff with `vanishpp.see` — so you always know when your setup is incomplete.
*   **Dependency Warnings:** The plugin intelligently warns admins if ProtocolLib is missing, but allows you to silence these warnings permanently with `/vignore`.
*   **Accidental Chat Prevention:** If enabled, typing in chat blocks the message and asks you to confirm with `/vchat confirm`. Never leak your presence by accident again.
*   **Async Data Persistence:** All data is saved asynchronously. Server crash? Restart? Your vanish state is saved instantly. No accidental logins.

</details>

---

## 📋 Commands

Most commands support an optional `[player]` argument, allowing admins to modify the state/rules of other staff members.

| Command | Alias | Description | Permission |
| :--- | :--- | :--- | :--- |
| `/vhelp [command]` | `/vanishhelp` | Interactive help menu & guide. | `no permission` |
| `/vconfig <key> [val]` | `/vanishconfig` | Edit config settings live. | `vanishpp.config` |
| `/vanish [player]` | `/v`, `/sv` | Toggle vanish state. | `vanishpp.vanish` |
| `/vrules [player] <rule> [val]` | `/vanishrules` | Configure physics/interaction rules. | `vanishpp.rules` |
| `/vpickup [player]` | `/tipu` | Toggle item pickup. | `vanishpp.pickup` |
| `/vignore [player]` | `/vanishignore` | Toggle start-up warnings. | `vanishpp.ignorewarning` |
| `/vchat confirm` | `/vanishchat` | Confirm a chat message (if safety is on). | `vanishpp.chat` |
| `/vlist` | `/vanishlist` | GUI-like list of online vanished players. | `vanishpp.list` |
| `/vperms` | - | Manage permissions without a perm plugin. | `vanishpp.manageperms` |

---

## 🧩 Placeholders (PlaceholderAPI)

Only needed if you are building custom HUDs or Scoreboards. (TAB Plugin works automatically without these).

| Placeholder | Output Example | Description |
| :--- | :--- | :--- |
| `%vanishpp_is_vanished%` | `Yes` / `No` | Current status text. |
| `%vanishpp_is_vanished_bool%` | `true` / `false` | Boolean status for logic/conditions. |
| `%vanishpp_vanished_count%` | `3` | Number of **online** vanished players. |
| `%vanishpp_visible_online%` | `15` | Total players minus vanished players (Fake count). |
| `%vanishpp_prefix%` | `[VANISHED]` | Configured prefix (empty if visible). |
| `%vanishpp_pickup%` | `Enabled` | Current item pickup status. |
| `%vanishpp_vanished_list%` | `Notch, Herobrine` | List of online vanished names. |

---

## 🔒 Personal Rules (`/vrules`)

Customize your ghost experience. Default behavior can be tweaked per player.

*   `can_break_blocks` (Default: `false` - Cannot break blocks)
*   `can_place_blocks` (Default: `false` - Cannot place blocks)
*   `can_interact` (Default: `true` - Chests, Buttons)
*   `can_hit_entities` (Default: `false` - Prevents hitting players/mobs)
*   `can_pickup_items` (Default: `false` - Cannot pick up items)
*   `can_drop_items` (Default: `false` - Cannot drop items from inventory)
*   `can_chat` (Default: `false` - Requires confirmation to speak)
*   `can_trigger_physical` (Default: `false` - Pressure plates, crops, etc.)
*   `can_throw` (Default: `false` - Cannot throw items like eggs, snowballs, ender pearls, or shoot bows)
*   `mob_targeting` (Default: `false` - Mobs ignore you)

---

## ⚙️ Compatibility & Requirements

Vanish++ is built for modern ecosystems.

### Supported Versions
| Minecraft Version   | Status               | Notes                                                              |
| :------------------ | :------------------- | :----------------------------------------------------------------- |
| **1.21.x**          | ✅ Supported         | Native support. Recommended.                                       |
| **1.20.6**          | ⚠️ Likely Compatible | Built with cross-version compatibility in mind.                    |
| **1.20.4-**         | ❌ Unsupported       | Incompatible API. Use older versions.                              |

### Supported Platforms
| Platform            | Status               | Notes                                                              |
| :------------------ | :------------------- | :----------------------------------------------------------------- |
| **Paper**           | ✅ Recommended       | Best performance. Required for full physics/projectile support.    |
| **Spigot**          | ⚠️ Compatible        | Works, but some advanced physics features may degrade.             |
| **Purpur**          | ✅ Supported         | Fully compatible.                                                  |
| **Folia**           | ✅ Supported         | Custom regional scheduler bridge included.                         |

**Requirements:**
*   **Java 21**
*   **ProtocolLib 5.3.0+** (Highly Recommended for Stealth)

**Optional Hooks:**
*   **TAB (NEZNAMY)** (Native Support)
*   PlaceholderAPI, Dynmap, EssentialsX, Simple Voice Chat, DiscordSRV

**Just drop the JAR in your plugins folder.** No complex setup required. It works securely out of the box.

---

<div align="center">

### 📄 License & Support

This project is closed-source.
Report bugs via the **Issues** tab.

**[ Download Now ]**
*And become truly invisible.*

</div>