# Issues Review — 2026-03-14

## Summary

| # | Issue | Status |
|---|-------|--------|
| #8 | Allow editing of plugin messages (localization) | ✅ Done |
| #7 | Add /vconfig reload command | ✅ Done |
| #6 | MiniMessage + PlaceholderAPI support | ✅ Done |
| #5 | Folia support | ✅ Done |
| #3 | Fly preserved on unvanish | ✅ Done |
| #2 | DiscordSRV hook | ⚠️ Partial |
| #4 | Velocity support | ⏭️ Skipped (too large) |
| — | Mob still attacks after /vanish | ✅ Fixed this session |

---

## Already Fully Implemented

### #8 — Localization
Messages are fully externalized into `lang/en.yml` via `LanguageManager`. All player-facing strings editable.

### #7 — /vanishreload
`VanishReloadCommand.java` exists and reloads both config and language files at runtime.

### #6 — MiniMessage + PlaceholderAPI
`PlaceholderExpansion` registered in `IntegrationManager` with `%vanishpp_is_vanished%`, `%vanishpp_vanished_count%`, etc. MiniMessage used for rich text rendering throughout.

### #5 — Folia Support
`FoliaSchedulerBridge.java` wraps regional scheduling. Detected at runtime and used in place of Bukkit's scheduler automatically.

### #3 — Fly Preserved on Unvanish
`removeVanishEffects()` checks `essentials.fly` and `bukkit.command.fly` before revoking flight. Players with rank-based fly permission keep it after unvanishing.

---

## Fixed This Session

### Mob Targeting Bug (not a tracked GitHub issue, reported in chat)
Mobs that had already targeted a player before `/vanish` was used kept attacking them because `EntityTargetLivingEntityEvent` only fires on *new* targeting — not existing targets.

**Fix:** `applyVanishEffects()` now iterates all entities within 64 blocks and clears the target of any `Mob` pointing at the vanishing player.

---

## Partially Implemented

### #2 — DiscordSRV Hook

**What works:**
- Chat suppression via `GameChatMessagePreProcessEvent` — vanished players' messages don't appear in Discord.
- Fake quit/join messages when toggling vanish mid-session via `IntegrationManager.updateHooks()`.

**What's missing:**
When a player *joins the server* with a persisted vanish state (e.g., relogged while vanished), DiscordSRV independently fires its own join announcement to Discord — this is not suppressed. The current `onJoin` handler sets the Bukkit join message to `null`, but DiscordSRV's Discord announcement is separate and unaffected by that.

**Why not fixed now:** Suppressing DiscordSRV's join/quit Discord messages requires subscribing to a DiscordSRV-specific pre-process event (e.g., `PlayerJoinLeaveMessagePreProcessEvent`) that may not exist in the version currently targeted, or requires intercepting DiscordSRV internals. Non-trivial — not a quick fix.

---

## Skipped (Too Large)

### #4 — Velocity Support
Requires a dedicated Velocity plugin, proxy-side event handling, and cross-server state sync architecture. Correctly deferred.
