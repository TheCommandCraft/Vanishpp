# Vanish++ Changelog

All notable changes to this project will be documented in this file.

## [1.1.8] - Unreleased

### Fixed
- **Mob Targeting Prevention Reliability:** Removed the duplicate `MONITOR`-priority `EntityTargetEvent` handler that called `mob.setTarget(null)` inside the event. This was triggering a secondary `EntityTargetEvent` (FORGOT reason) which reset `NearestAttackableTargetGoal` cooldowns, starving nearby non-vanished players of mob attacks. The single `HIGHEST`-priority cancel is sufficient and correct.
- **Mob Target Clear on Vanish Missed Far Mobs:** When a player vanished, existing mob targets were cleared using a 64-block radius scan. Mobs further away that already had the player as their target were never cleared. The scan now covers the entire world the player is in.

### Performance
- **MobAiManager Sweep Scoped to Vanished Players:** The 5-tick periodic sweep previously iterated every entity in every world. It now iterates only online vanished players and checks a 100-block radius around each — zero cost when nobody is vanished.

---

## [1.1.7] - 2026-04-18

### Added
- **Cross-Server Vanish State Reconciliation:** When a player joins a server that shares a MySQL/PostgreSQL database with other servers, their vanish state from any other server is immediately applied — no manual `/vanish` needed after switching. Works on BungeeCord/Velocity networks with a shared database. Stale in-memory entries for offline players are also periodically purged so no server accumulates phantom vanished UUIDs.
- **Instant Proxy-Ready Vanish State on Join:** Vanish state is now pre-fetched from the database during `AsyncPlayerPreLoginEvent` (before the player fully connects), so it is applied on the very first tick of `PlayerJoinEvent` with zero additional delay. On a proxy network, players switching servers appear vanished or visible to staff immediately, with no visible flicker or catch-up period.
- **Native Velocity Proxy Plugin:** A companion Velocity plugin (`vanishpp-velocity`) provides a dedicated `vanishpp:proxy` plugin messaging channel for real-time cross-server state sync, config push, and network-wide `/vanishreload`. The proxy maintains an authoritative vanished-player list and broadcasts state changes to all connected Paper servers instantly. Automatically detected on startup — servers fall back to standalone mode if no Velocity proxy is found.
- **Scoreboard Timezone Offset (`timezone-offset-hours`):** The scoreboard's `%time%` and `%date%` placeholders now support a configurable hour offset applied on top of the base timezone. Set `timezone-offset-hours: 2` in `scoreboards.yml` to shift the displayed time forward by two hours from the base zone (supports decimals like `5.5` for IST). `timezone: "default"` already uses the server's system clock; the offset is applied on top of whichever zone is configured.
- **ProtocolLib Missing Warning — Disabled Features List:** The startup warning shown to staff when ProtocolLib is not installed now includes a `[Disabled Features ▶]` hover button listing all 7 features that require ProtocolLib (tab-complete scrubbing, entity packet filtering, ghost-proof spawning, scoreboard team scrubbing, server list count adjustment, silent chest animations/sounds, staff glow).

### Fixed
- **Mobs Looking at Vanished Players (Passive Head Tracking):** Re-introduced `MobAiManager` with a correct implementation. The root cause of the 1.1.5 regression was that `SafeLookAtPlayerGoal` returned `false` from `shouldActivate()` when only vanished players were nearby — this freed the `LOOK` goal slot, allowing the vanilla `LookAtPlayerGoal` to fill it (vanilla has no vanish awareness). `SafeLookAtPlayerGoal` now claims the `LOOK` slot whenever **any** player is in range (returning `true` with a null target when all nearby players are vanished), so the vanilla goal can never activate for vanished players. A periodic 20-tick sweep also force-clears any combat targets pointing at vanished players as a safety net.
- **Cross-Server Timed Rule Expiry Message Not Delivered:** When a timed rule (e.g. `/vrules <player> mob_targeting time 30`) expired on server A while the player was already connected to server B, the notification was never shown. The fix is three-layered: (1) a `PLAYER_MESSAGE` packet type routes the message through the Velocity proxy to wherever the player currently is; (2) if no carrier player is available to send the packet, a `__notify_expired__<rule>` flag is written to the shared database; (3) a 5-second async poll on every server checks online players for pending DB flags and delivers + clears them immediately — no reconnect required. The rule revert itself always completes successfully; only the notification delivery was affected.
- **ProtocolLib Async Entity Lookup Spam:** `getEntityFromID()` was being called from ProtocolLib packet listeners on async threads (e.g. triggered by Orebfuscator), causing console spam and unsafe cross-thread world access. Entity ID to UUID resolution is now handled via a local `ConcurrentHashMap` cache populated at join and cleared at quit, keeping all lookups off the world thread entirely. (Thanks SimplyRan, PR #5)
- **Folia Crash: Night Vision Applied from Global Scheduler:** When cross-server vanish sync (Redis) applied vanish effects from the global region scheduler, calling `player.addPotionEffect()` and `player.removePotionEffect()` directly caused an `UnsupportedOperationException` on Folia because the global scheduler does not own any player entity's region. Night vision potion calls in `applyVanishEffects`, `removeVanishEffects`, and `resyncVanishEffects` are now dispatched through `vanishScheduler.runEntity()`, which routes to the entity's own region scheduler on Folia and to the main thread on all other platforms. (Thanks SimplyRan, PR #1)
- **Silent Container Item Duplication:** Opening containers while vanished could cause items to duplicate or appear as wrong state. The root cause was a snapshot-copy approach — changes were written to a copy of the inventory and synced back on close, but the sync-back could overwrite concurrent item movements (hoppers, other players) and double-count items. Containers are now opened directly against the real inventory, matching vanilla behaviour exactly.
- **Storage Backend Not Applied After `/vanishreload`:** Changing `storage.type` in `config.yml` (e.g. from `YAML` to `POSTGRESQL`) had no effect until the server was fully restarted. `/vanishreload` now shuts down the active storage provider and reinitializes it from the current config, making storage type changes take effect immediately.
- **`/vanishscoreboard` Visible in Tab-Complete for All Players:** The command appeared in tab-complete for players who lacked `vanishpp.scoreboard`, revealing the feature's existence. The command entry in `plugin.yml` now declares `permission: vanishpp.scoreboard`, causing Bukkit to hide it from players without that node.
- **MobAiManager Crash on Spigot/Bukkit:** `MobAiManager` called `Bukkit.getMobGoals()` — a Paper-only API — unconditionally on startup, causing `NoSuchMethodError` crashes on Spigot and Bukkit when the listener was registered. The manager is now only registered when the Paper API surface is confirmed present (Paper, Purpur, Folia). Mob AI goal injection and the 20-tick sweep remain fully operational on all Paper-family servers; on Spigot/Bukkit mob targeting prevention continues to work via `EntityTargetEvent`.
- **Folia Startup Crash: `Delay ticks may not be <= 0`:** `FoliaSchedulerBridge.runLaterGlobal()` passed the caller-supplied tick value directly to Folia's `runDelayed`, which throws `IllegalArgumentException` for any value `<= 0`. The bridge now falls back to immediate `runGlobal` execution when `ticks <= 0`, matching the intent of the caller and preventing the plugin from failing to enable on Folia. (Thanks XChen446, PR #11)

---

## [1.1.6] - 2026-04-07

### Added
- **Vanish Scoreboard:** A configurable sidebar scoreboard shown automatically to vanished players (`vanishpp.scoreboard`). Displays world, TPS, online count, coordinates, direction, biome, ping, health, food, armor, time, date, vanish level, and more. Updates coordinates in real-time on movement via ProtocolLib packet listener (with configurable cooldown). Toggle manually with `/vscoreboard`. Auto-shows on vanish, hides on unvanish. Configured in `scoreboards.yml`. Reloads with `/vreload`.
- **`/vscoreboard` Command:** Toggle the vanish scoreboard on/off. Requires `vanishpp.scoreboard`.
- **Scoreboard Placeholders:** Full set of built-in placeholders: `%world%`, `%tps%`, `%tps_raw%`, `%online%`, `%max_players%`, `%vanished_count%`, `%x%`, `%y%`, `%z%`, `%direction%`, `%biome%`, `%ping%`, `%gamemode%`, `%health%`, `%max_health%`, `%food%`, `%armor%`, `%level%`/`%vanish_level%`, `%player%`, `%displayname%`, `%memory_used%`, `%memory_max%`, `%entities%`, `%chunks%`, `%time%`, `%date%`. PlaceholderAPI supported.
- **`/vlist` Interactive Player Names:** Each player name in `/vlist` output is now clickable. Hovering shows the player's vanish level and world; clicking runs `/vanish <player>` to unvanish them instantly.
- **Periodic Update Checker:** The update checker now re-runs every hour in the background — not just once on startup. Staff with `vanishpp.update` are notified without needing a server restart.
- **SQL Schema Versioning:** MySQL/PostgreSQL storage now tracks a schema version in `vpp_schema_version` and runs structured migrations on startup, allowing for future schema changes. Schema v2 adds `created_at`/`updated_at` columns for future audit trail features.
- **Real-Time Database Sync:** Vanish state changes (vanish/unvanish) are now persisted to the database asynchronously, so storage I/O never blocks the main thread on join or leave. Rules and vanish state are kept in a per-player in-memory cache — pre-populated async on join and cleared on quit — eliminating database round-trips on hot event paths (block breaks, entity damage, etc.).
- **Database Connection Error Notifications:** When database connectivity fails, staff with `vanishpp.admin` or OP status are notified in-game (throttled every 5 minutes to prevent spam). Helps identify infrastructure issues without requiring log file access.
- **Proxy Plugin Integration Documentation:** Complete guide for proxy plugins (BungeeCord/Velocity) to read vanish state directly from the database. Includes example adapters and security best practices.

### Changed
- **Spectator Quick-Switch Restores Exact Gamemode:** Double-shifting out of Spectator now returns the player to the gamemode they were in *before* entering Spectator (Creative, Adventure, Survival), not always Survival.
- **DiscordSRV Fake Join/Leave Use Embed Format:** Fake join and leave messages sent to Discord on vanish/unvanish now use DiscordSRV's own `sendJoinMessage()`/`sendLeaveMessage()` methods, honouring the embed, colour, avatar, and webhook settings configured in DiscordSRV's `messages.yml`. Previously they were always plain bold text regardless of configuration.
- **Scoreboard Column Auto-Alignment:** Lines containing a `|` separator are automatically padded so the separator lands at the same column on every line. Label widths are measured after stripping color codes. Works for any custom label length — no manual padding needed.
- **Scoreboard Hides Score Numbers:** Score numbers on the right side of the sidebar are hidden using Paper's `NumberFormat.blank()` API for a cleaner look.
- **Config Defaults Hardened:** All config reads now use explicit fallback defaults. Previously, deleted or missing keys would silently produce `false`/`0` — now the intended default is always applied even on a stripped config file.
- **Database Transaction Safety:** `removePlayerData()` now uses transactions to ensure all-or-nothing deletion. Connection errors during cleanup are logged but don't partially corrupt data.
- **Network Sync Idempotency:** Cross-server vanish sync (Redis) now ignores duplicate messages to prevent visibility state divergence if network flakiness causes message replays.

### Fixed
- **Folia Crash on Startup:** Folia 1.21.11 renamed the internal detection class used by the scheduler bridge, causing the plugin to load `BukkitSchedulerBridge` instead of `FoliaSchedulerBridge`. Added `Bukkit.getName()` as a fallback detection method. Additionally, Folia forbids `ScoreboardManager.registerNewTeam()` on the startup thread — team setup is now deferred to the global region scheduler, and `vanishTeam` usages are null-guarded for the brief window before it completes.
- **Action Bar Warning Overwritten:** Warning messages (e.g., "Action Blocked") shown in the action bar were immediately erased by the vanish status bar on the next scheduler tick. Warnings now display for their full intended duration before the status bar resumes.
- **`prevent-potion-effects` Wrong Default:** This setting defaulted to `true` in code, silently blocking all potion effects (including healing potions thrown by staff) on servers where the key was missing from `config.yml`. Corrected to `false`.
- **SQL Acknowledgements Not Persisted:** The `vpp_acknowledgements` table was missing from the MySQL/PostgreSQL schema. Persistent acknowledgements (ProtocolLib missing warning, config migration reports) were silently ignored for SQL storage users — they are now stored and respected correctly.
- **SQL `removePlayerData` Left Stale Acknowledgements:** Removing a player's data via SQL did not delete their acknowledgement rows. Stale entries could suppress future notifications for that UUID. Now cleared along with rules and level data in a single transaction.
- **SQL `getRules` Returned Strings Instead of Booleans:** `getRules()` returned raw SQL text values (`"true"`, `"false"`) instead of `Boolean` objects, breaking any code comparing rule values by type. Values are now parsed to `Boolean` before being returned.
- **PostgreSQL `addAcknowledgement()` Syntax Error:** PostgreSQL `ON CONFLICT` clause was incomplete, missing the constraint columns. Now correctly uses `ON CONFLICT (uuid, notification_id) DO NOTHING`.
- **Redis Subscriber Thread Leak:** The Redis subscriber thread was never gracefully shut down on plugin reload, causing lingering connections and resource exhaustion. Now properly interrupts the thread with timeout and closes Jedis resources.
- **Folia Visibility Sync Thread Safety:** Visibility sync task could cause `ConcurrentModificationException` in Folia's multi-region environment. Now uses immutable snapshots for safe iteration.
- **SQL Schema Migration Idempotency:** Calling `init()` multiple times (e.g., on reload) would fail with primary key violations. Now idempotent and safe to call repeatedly.
- **Database Error Visibility:** Silent database failures provided no feedback to admins. Connection errors are now logged to console and notified to staff in-game.
- **DiscordSRV Advancement Leak:** Vanished players completing advancements no longer trigger Discord announcements. Suppressed via `AchievementMessagePreProcessEvent` as a safety net in addition to DiscordSRV's native vanish check.
- **DiscordSRV Death Leak:** Vanished players dying no longer trigger Discord death announcements. Suppressed via `DeathMessagePreProcessEvent` as a safety net.

## [1.1.5] - 2026-03-28

### Added
- **Spectator Quick-Switch:** Vanished players with `vanishpp.spectator` can double-tap Shift to toggle Spectator mode instantly. Double-tap again to return to the previous gamemode. Unvanishing while in Spectator forces the player back automatically (bypassed by `vanishpp.spectator.bypass`).
- **`spectator_gamemode` VRule:** New per-player rule to enable or disable the Spectator quick-switch. Default: `true`. Configurable globally under `vanish-gamemodes.enabled`.

### Changed
- **Spectator Mode Feedback:** The action bar notification shown when entering or exiting Spectator mode is now displayed for 3 seconds and is no longer immediately overwritten by the vanish status indicator.
- **Unknown Command on Permission Denied:** Players without permission to use any Vanish++ command now receive a vanilla-style "Unknown command" response instead of a permission-denied message, preventing server staff plugin discovery.

### Fixed
- **Mob Despawning Near Vanished Players:** Mobs no longer despawn when chunks are reloaded near a vanished player. The previous `setAffectsSpawning(false)` call incorrectly caused the server to ignore vanished players for mob lifecycle calculations. Pressure plate suppression is now handled exclusively by the `CAN_TRIGGER_PHYSICAL` rule, which was already in place.
- **Mobs Not Attacking Non-Vanished Players:** Removed `MobAiManager`, which replaced the vanilla `LookAtPlayerGoal` for all mobs globally. The Paper `MobGoals` API used to inject the custom goal was causing mob attack AI to break for all players. Attack prevention for vanished players is now handled entirely through `EntityTargetEvent`.
- **`can_throw` Rule Had No Effect:** Throwing projectiles (snowballs, eggs, ender pearls, potions, bows, crossbows) while `can_throw` was enabled was still blocked because `PlayerInteractEvent` cancelled the right-click before `ProjectileLaunchEvent` could fire. The interact handler now permits throwable items through when `can_throw` is enabled, even if `can_interact` is disabled.
- **Spawn Eggs Always Blocked:** Spawn eggs were blocked unconditionally regardless of the `can_throw` rule state. They now respect the rule correctly.
- **Periodic Mob Pathfinding Interruption:** The background sync task was calling `stopPathfinding()` on all nearby mobs every 0.5 seconds. This caused mobs to appear passive and disrupted vanilla mob behaviour for all players. Mob targeting is now handled reactively via `EntityTargetEvent` only.

## [1.1.4] - 2026-03-18

### Added
- **`can_throw` VRule:** New personal rule controlling whether vanished players can throw items (eggs, snowballs, ender pearls, potions, bows, crossbows). Default: `false`. Fully integrated with interactive chat buttons (`[Allow 1m]`, `[Allow permanently]`, `[Hide notifications]`).
- **Staff Glow Indicator:** Vanished players now render with a glowing outline for staff with `vanishpp.see`. Uses packet-level metadata injection — non-staff never see it. Enabled by default (`vanish-appearance.staff-glow: true`).
- **Vanish State Resync Engine:** New `resyncVanishEffects()` system that reapplies all vanish state (team, prefix, metadata, fly, night vision, collision, mob targeting, visibility, glow) without touching storage. Triggered automatically on respawn, world change, and gamemode change.
- **Respawn Handler:** Vanish state (fly, invisibility, glow, collision) is now fully restored after death and respawn.
- **World Change Handler:** Nether/End portal transitions no longer break vanish state.
- **Gamemode Change Handler:** Changing gamemode (e.g., `/gamemode survival`) while vanished no longer disables flight.
- **[Unvanish] Button:** All blocked-action messages now include a convenient `[Unvanish]` button alongside `[Allow 1m]`, `[Allow permanently]`, and `[Hide notifications]`.
- **Fast Sync Loop:** Heartbeat task now runs every 10 ticks with visibility fix, team/prefix reapply, glow metadata resend, and mob targeting — ensuring all vanish features stay in sync continuously.
- **Explicit Glow Metadata Packets:** Staff glow is now sent via direct ProtocolLib `ENTITY_METADATA` packets rather than relying on entity respawn metadata, ensuring the glow appears instantly.
- **Multi-Stage Join Prefix:** Tab prefix is now reapplied at 2, 20, and 60 ticks after join — catches TAB plugin async overrides at different pipeline stages.
- **Snapshot Silent Chests:** Barrels and containers are now opened as inventory snapshots (copy of the real inventory). Changes sync back on close. This eliminates `Container.startOpen()` entirely, preventing animation and sound at the source.
- **Reload Resync:** `/vreload` now refreshes team prefix and resyncs all online vanished players' effects.
- **Setup Advisor (Config Sanity Checker):** On startup, Vanish++ now scans the active configuration and warns if:
    - `hooks.simple-voice-chat.enabled` is `true` but SimpleVoiceChat is not installed.
    - `hooks.essentials.simulate-join-leave` is `true` but EssentialsX is not installed.
    - Any message string contains a PlaceholderAPI placeholder (`%token%`) but PlaceholderAPI is not installed.
    - Warnings are printed to the console **and** shown in-chat to all players with `vanishpp.see` on their next login, making it easy to spot setup issues without reading logs.
- **Comprehensive Automated Test Suite:** 143 unit tests across 6 test classes covering every feature — commands, event listeners, storage, permissions, rules, and integration scenarios. All tests pass on clean build.
- **Full Localization (i18n):** Complete multi-language support.
    - All messages and system reports moved to `lang/en.yml`.
    - Automatic fallback system for missing keys.
    - Simplified `config.yml` with a new `language` toggle.
- **Rich Text Support:** Integrated **MiniMessage** for modern, easy-to-read chat formatting using tags (e.g., `<red>`, `<bold>`, `<click>`).
- **PlaceholderAPI Integration:** Full support for dynamic placeholders in all plugin messages and the Action Bar.
- **Folia Support:** Rewritten scheduler and event handling to support Folia's multi-threaded region architecture.
- **Config Reload:** Added `/vreload` command to apply configuration and language changes instantly without server restarts.
- **Interactive Reports:** Professional clickable components in all command outputs.
- **Titan Stealth Engine:** Absolute packet-level invisibility for vanished administrators. The server now acts as if the vanished player does not exist in the network data.
    - **Tab List & Info Filtering:** Blocked `PLAYER_INFO` and `PLAYER_INFO_UPDATE` packets to prevent any tab list flicker or presence detection.
    - **Ghost-Proof Spawning:** Intercepted `SPAWN_ENTITY` and `NAMED_ENTITY_SPAWN` to ensure vanished players are never spawned on unauthorized clients.
    - **Absolute Metadata & Update Blocking:** High-priority interceptors for `ENTITY_METADATA`, `ENTITY_EQUIPMENT`, `ANIMATION`, `ENTITY_EFFECT`, `ENTITY_STATUS`, and `ENTITY_SOUND_EFFECT` to ensure zero data leakage.
    - **Movement & Velocity Filtering:** Blocked `ENTITY_VELOCITY`, `REL_ENTITY_MOVE`, `ENTITY_LOOK`, `ENTITY_TELEPORT`, and `ENTITY_HEAD_ROTATION` to eliminate coordinate-level leaks.
    - **Action Scrubber:** Intercepted `COLLECT` (item pickups) and `SET_PASSENGERS` (mounting) to scrub vanished names even when they are not the primary entity.
    - **Team Scrubbing:** Intercepted `SCOREBOARD_TEAM` to remove vanished names from any raw team packets sent to non-staff.
- **Plugin Hiding:** "Vanishpp" is now hidden from `/plugins` (or `/pl`) for non-OPs. Administrators see a filtered list with a delayed interactive notification to temporarily unhide or acknowledge the feature.
- **Strict Command Permissions:** All commands now enforce strict permissions in `plugin.yml`. Unauthorized players cannot see commands in Tab-Complete.
- **Config Logic:** `vanishMessage`, `unvanishMessage`, `fakeJoinMessage`, and `fakeQuitMessage` logic updated. Setting these to `"false"`, `"none"`, or leaving them empty in `config.yml` now properly disables the message.
- **Persistence:** Acknowledging the plugin hiding warning now saves the preference to `data.yml` specifically for the current version.

### Changed
- **Silent Chest Architecture:** Replaced the spectator-mode workaround with a snapshot inventory system. Vanished players now open a copy of the container, and edits sync back on close — no animation, no sound, full item interaction.
- **Throwable Blocking:** Throwables (eggs, bows, ender pearls, etc.) are now governed by the `can_throw` VRule instead of being unconditionally blocked. Players can toggle this rule like any other.
- **Spawn Egg Messages:** Spawn eggs now use the standard `sendRuleDeny` system with `can_throw`, providing interactive buttons instead of a static message.
- **Sound Suppression:** Silent chest sound listener now covers both `NAMED_SOUND_EFFECT` and `CUSTOM_SOUND_EFFECT` packets with robust multi-offset coordinate detection.
- **Config Structure:** Significant cleanup of `config.yml`. Legacy message blocks removed in favor of `lang/*.yml`.
- **Performance:** Optimized visibility checks and metadata handling for better server performance.

### Fixed
- **DiscordSRV Join/Quit Suppression:** DiscordSRV now fully suppresses join and quit announcements on Discord for players who rejoin with persisted vanish state. Previously, only chat suppression worked — Discord join messages leaked through.
- **Barrel/Shulker Silent Opening:** Opening barrels and shulker boxes while vanished no longer leaks the open/close animation or sound to nearby players. Previously, the `Container.startOpen()` call triggered both.
- **Join Prefix Delay:** The vanish tab prefix (`[V]`) now appears within ~100ms of joining instead of taking 3+ seconds. Multi-stage reapplication handles TAB plugin async overrides.
- **Throwable Items Leaked Position:** Throwing eggs, snowballs, ender pearls, and shooting bows while vanished created visible projectile entities that revealed the player's position. Now blocked by default via the `can_throw` rule.
- **Spawn Egg Rule Bypass:** The `CAN_INTERACT` rule had no effect on spawn eggs due to incorrect check ordering. Spawn eggs are now properly governed by `can_throw`.
- **Vanish State Lost on Respawn:** Dying and respawning while vanished caused loss of fly, invisibility, glow, collision settings, and night vision.
- **Vanish State Lost on World Change:** Entering nether/end portals broke vanish state (visibility, fly, glow, prefix).
- **Flight Lost on Gamemode Change:** Running `/gamemode survival` while vanished disabled fly mode permanently until re-vanishing.
- **Reload Didn't Resync:** `/vreload` previously only reloaded config files without resyncing active vanish effects for online players.
- **Mob Targeting on Vanish:** Mobs that had already acquired a player as their target before `/vanish` was used would continue attacking. Fixed by explicitly clearing all matching mob targets within 64 blocks the moment a player vanishes.
- **Console Staff Notification:** Console was silently excluded from vanish/unvanish staff notifications. It now receives the same message as staff players with `vanishpp.see`.
- **Fly Mode Logic:** Improved flight persistence on unvanish when configured.
- **Dependency Resolution:** Fixed an issue where the ProtocolLib dependency was not correctly detected on first install.

## [1.1.3] - 2026-02-02

### Added
- **No-Delay Notifications:** Completely removed the 60-second cooldown for rule notification alerts. Players now receive immediate feedback every time an action is blocked while vanished.
- **Enhanced Splash Protection:** Vanished players are now strictly immune to **Splash Potions** and **Lingering Potion Clouds**. They are automatically removed from the list of affected entities.
- **Titan God Mode:** Vanished players are now strictly invincible. They take no damage and are immune to all external potion effects (splash potions, mob effects).
- **Interactive Help System:** Added `/vhelp` and `/vhelp <command>`. A professional, clickable chat menu that explains every command, usage, aliases, and permissions.
- **Smart-Merge Migration (v2):** Completely overhauled the configuration update system. Future config changes now recursively copy all user-customized values (messages, rules, prefixes) into new versions without loss. NOTE: Critical fix applied to prevent data loss on update failure.
- **Join Notification Delay:** All join notifications (warnings, updates, migrations) now wait 250ms to ensure they appear at the bottom of the chat, visible above other plugin spam.
- **Acknowledgement System:** Migration reports now stay visible on join until specifically hidden via the new **[HIDE]** button.

### Changed
- **Unified Rules:** `/vpickup` and other individual toggles now strictly sync with the RuleManager engine.
- **Config Cleanup:** Removed redundant `data` section from `config.yml` default template to prevent user confusion (uses `data.yml` exclusively).
- **Downgraded Requirement:** Reverted target Minecraft version to **1.21.11** (Paper) and ProtocolLib **5.4.0** for broader compatibility.

### Fixed
- **Critical Migration Bug:** Fixed a logic error where the migration manager would overwrite the configuration file with a default template *before* saving the merged data, leading to reset settings if the save failed.
- **Prefix Leakage:** Strictly decoupled Tab prefixes from Nametag prefixes. Prefix text will no longer "leak" into Scoreboards or the Social Interaction menu.
- **Persistence Fix:** Fixed an issue where manual config edits were overwritten by the plugin's automatic data saving.

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