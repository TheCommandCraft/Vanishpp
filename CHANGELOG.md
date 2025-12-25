# Vanish++ Changelog

All notable changes to this project will be documented in this file.

## [1.1.0] - 2025-12-25

### Added
- **ProtocolLib Tab Scrubbing:**
    - Vanish++ now intercepts `TAB_COMPLETE` packets sent to clients.
    - If a non-staff player tries to tab-complete a name in chat, vanilla commands, or plugin commands, vanished players will be **completely removed** from the suggestions list. This ensures 100% hidden status even against plugins that auto-suggest online players.
- **ProtocolLib Integration:**
    - **Ghost View:** Staff members with permission (`vanishpp.see`) see vanished players in the TabList as **Spectators** (gray and italicized).
    - **True Invisibility:** Packet-level handling ensures vanished players are hidden from Server List counts.
- **Plugin Hooks:**
    - **EssentialsX:** Hidden from `/who`, `/list`, `/online`.
    - **Dynmap:** Hidden from web map.
    - **PlaceholderAPI:** Added `%vanishpp_is_vanished%`.
- **Per-Player Rules System:** Added `/vanishrules` (alias `/vrules`). Configure personal restrictions:
    - `can_break_blocks`, `can_place_blocks`
    - `can_hit_entities` (Default: false) - Absolute peace mode.
    - `can_interact` (Default: true) - Chests, buttons.
    - `can_chat` (Default: false) - Prevents accidental chatting.
- **Chat Confirmation:** Added `/vanishchat`. Prevents accidental leaks by requiring confirmation if strict chat is enabled.

### Fixed
- **Projectile Physics:** Switched to Paper's `ProjectileCollideEvent`. Arrows now physically pass through vanished players without stopping or teleporting.
- **Entity Collision:** Vanished players cannot push or be pushed by entities.
- **Code Stability:** Cleaned up chat confirmation logic and corrected dependency versions for stable builds.

### Changed
- **Dependencies:** Added soft-depends for ProtocolLib, PlaceholderAPI, Dynmap, EssentialsX, and SimpleVoiceChat.

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