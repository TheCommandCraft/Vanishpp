# Vanish++ Changelog

All notable changes to this project will be documented in this file.

## [1.1.2] - 2026-01-22

## Fixed
- **Config Bug:** The config now affects if the fake join and leave messages display when vanishing.

## [1.1.1] - 2026-01-17

### Added
- **Smart Mob AI (True Sight Engine):**
    - Vanish++ now injects custom AI goals into server mobs. Mobs will physically ignore vanished players—they will not look at you, track you, or turn their heads, even if you stand directly in front of them.
    - This replaces the old "Invisibility Potion" workaround, allowing Staff to see each other's armor/skins clearly while remaining invisible to mobs.
- **Modrinth Update Checker:**
    - Added an asynchronous update checker. OPs (or those with `vanishpp.update`) will receive a notification on join if a newer version is available on Modrinth.
    - Settings added to `config.yml` to toggle this feature or restrict it to a specific list of players.
- **Data Separation (Config Fix):**
    - Created `data.yml`. Dynamic data (vanished players, ignored warnings, custom rules) is now stored here.
    - **Fix:** This prevents `config.yml` from being overwritten on server restart, allowing you to edit settings safely while the server is running.
- **Temporary Rules:**
    - The `/vrules` command now accepts a duration.
    - Example: `/vrules can_break_blocks true 60` will allow breaking blocks for 60 seconds, then automatically disable it.
- **Visual Feedback:**
    - Added Action Bar alerts ("✖ Action Blocked") when a player attempts a prohibited action, providing immediate feedback without spamming chat.
    - Added an **[ENABLE 1m]** button to chat warnings for quick temporary overrides.

### Fixed
- **ProtocolLib Crashes:** Fixed critical `NullPointerException` and `FieldAccessException` crashes on 1.21+ servers caused by malformed packets from other plugins.
- **PlaceholderAPI Integration:** Restored PAPI support. Placeholders like `%vanishpp_visible_online%` now work correctly in Scoreboards and Tablists.
- **TAB Plugin Compatibility:** Fixed a crash with the TAB plugin hook by switching to the correct API method for prefix management.

## [1.1.0] - 2026-01-03

### Added
- **Java 21 / MC 1.21 Support:** Full compatibility with the latest Minecraft versions.
- **Legacy Plugin Compatibility:** Vanished players now automatically have the standard Bukkit `"vanished"` metadata set.
- **Dependency Warning System:** Added a chat/title/sound warning for OP players on join if **ProtocolLib** is missing.
- **Ignore Warning Command:** Added `/vanishignore` to permanently silence the ProtocolLib warning.
- **Universal Command Targets:** `/vrules`, `/vpickup`, and `/vignore` now accept an optional target player.
- **Heartbeat Synchronization:** Force-refreshes visibility every second to handle permission changes instantly.
- **ProtocolLib Tab Scrubbing:** Vanished players are removed from Tab-Complete packets.
- **Dropping Rule:** Added `can_drop_items` rule (Default: false).
- **TAB Plugin Hook:** Native integration with NEZNAMY's TAB plugin to set prefixes without placeholders.

### Changed
- **Visibility Logic:** Removed the Vanilla Invisibility Potion effect. This ensures that **Staff with permission** can see the vanished player's armor and skin, while normal players still see nothing (handled via packet hiding).
- **Mob AI:** Mobs are prevented from targeting vanished players via event cancellation, though head tracking may occur visually due to the removal of the invisibility potion.

### Fixed
- **Mob Gazing:** Fixed mobs looking at vanished players.
- **Join Visibility Flash:** Moved join event priority to `LOWEST`.
- **Server List Hover:** Fixed a bug where vanished players appeared in the sample list.
- **Chat Confirmation Loop:** Fixed logic so confirmed messages don't re-trigger the blocker.
## [1.0.4] - 2025-12-25

### Added
- **ProtocolLib Integration:**
    - **Ghost View:** Staff members see vanished players in the TabList as **Spectators** (gray/italic).
    - **True Invisibility:** Packet-level hiding from Server List counts.
- **Plugin Hooks:** EssentialsX, Dynmap, PlaceholderAPI.
- **Per-Player Rules:** Added `/vrules` configuration.
- **Chat Confirmation:** Added `/vanishchat`.

### Fixed
- **Projectile Physics:** Switched to Paper's `ProjectileCollideEvent`. Arrows physically pass through vanished players.
- **Entity Collision:** Disabled collision via Scoreboard Teams.

## [1.0.3] - 2025-12-25

### Added
- **ProtocolLib Integration:**
    - **Ghost View:** Staff members with permission (`vanishpp.see`) now see vanished players in the TabList as **Spectators** (gray and italicized), distinguishing them from normal players.
    - **True Invisibility:** Packet-level handling ensures vanished players are completely hidden from the Server List player count.
- **Plugin Hooks:**
    - **EssentialsX:** Vanished players are now hidden from `/who`, `/list`, and `/online`.
    - **Dynmap:** Vanished players are automatically hidden from the web map.
    - **PlaceholderAPI:** Added `%vanishpp_is_vanished%` and `%vanishpp_vanished_count%` placeholders.
- **Per-Player Rules System:** Added `/vanishrules` (alias `/vrules`) to configure personal restrictions (breaking blocks, hitting entities, etc.).
- **Chat Confirmation:** Added `/vanishchat`. If strict chat rules are enabled, players must confirm messages before sending them to prevent accidental leaks.

### Fixed
- **Projectile Physics (Native Pass-Through):**
    - Switched to Paper's `ProjectileCollideEvent` for absolute stability.
    - Arrows, tridents, and snowballs now natively pass through vanished players without any "teleporting" hacks or visual glitches. It is now physically impossible to hit a vanished player with a projectile.
- **Entity Collision:** Vanished players can no longer push or be pushed by other entities (players, mobs, boats) via Scoreboard Team collision rules.
- **Data Persistence:** Player rules and settings now save correctly to `config.yml` (under `data`).

### Changed
- **Dependencies:** Added `ProtocolLib`, `PlaceholderAPI`, `Dynmap`, and `EssentialsX` as soft dependencies.

All notable changes to this project will be documented in this file.

## [1.0.2] - 2025-012-25

### Added
- **Per-Player Rules System:** Introduced the `/vanishrules` (alias `/vrules`) command. Vanished players can now granulary configure what they can and cannot do while vanished.
    - **Usage:** `/vrules <rule> [true|false]`
    - **Available Rules:**
        - `can_break_blocks` (Default: true): Allow/disallow breaking blocks.
        - `can_place_blocks` (Default: true): Allow/disallow placing blocks.
        - `can_hit_entities` (Default: false): Prevents hitting players or mobs to ensure absolute stealth.
        - `can_pickup_items` (Default: false): Toggles item pickup (Replaces the old toggle command).
        - `can_trigger_physical` (Default: false): Prevents triggering pressure plates, tripwires, farmland, and turtle eggs.
        - `can_interact` (Default: true): Allow/disallow opening chests, using buttons, levers, etc.
        - `can_chat` (Default: false): If disabled, chat messages require manual confirmation.
        - `mob_targeting` (Default: false): If enabled, mobs will attack you even while vanished.
- **Accidental Chat Prevention:** If the `can_chat` rule is disabled, attempting to chat will now block the message and prompt you to run `/vanishchat confirm` to send it.
- **Command Feedback:** Added clear, color-coded chat feedback for all command interactions.

### Fixed
- **Projectile Phasing (The "Matrix" Fix):** Completely overhauled how arrows and projectiles interact with vanished players.
    - Previously, arrows would bounce off or drop at the player's feet, revealing their location.
    - **Now:** Projectiles detect the vanished player, are momentarily removed, and instantly re-spawned on the other side of the player with the exact same velocity and shooter data. To outside observers, arrows now fly perfectly straight through vanished players as if they were truly ghosts.
- **Entity Collision:** Fixed an issue where vanished players could push or be pushed by other entities (players, boats, mobs). Collision is now strictly disabled via Scoreboard Teams logic.
- **Data Persistence:** Player rules and settings are now saved reliably in the `data` section of the configuration, ensuring settings persist across server restarts.

## [1.0.1] - 2025-06-10

### Added
- **Silent Chests:** Opening Chests, Shulker Boxes, and Barrels while vanished is now silent (no animation/sound) and lets you view the inventory without alerting others.
- **Night Vision:** Vanished players automatically get Night Vision.
- **Flight Mode:** Vanished players can now fly automatically, even in Survival.
- **Mob Stealth:** Mobs will no longer target vanished players.
- **No Hunger:** Vanished players no longer lose hunger.
- **Configuration:** Added `invisibility-features` section to `config.yml` to toggle these new features.

### Fixed
- **Arrow Phasing:** Arrows and projectiles now pass through/ignore vanished players, preventing the "floating arrow" glitch and ensuring stealth.
- **Silent Chest Animation:** Utilized a temporary spectator-mode switch to ensure chest lid animations do not trigger for other players.