# Open Complaints — Unconfirmed by User

These are all issues raised by the user that have been addressed in code but **not yet confirmed as fixed** by the user. Each item will be removed once the user verifies the fix works.

---

## Iteration 2 Bugs (from FIXES_ITERATION_2.md)

### 1. Throwable items not blocked while vanished
- **Complaint**: Player can still throw eggs and other throwable items while vanished even when `CAN_INTERACT` is off.
- **Fix applied**: Added `ProjectileLaunchEvent` and `EntityShootBowEvent` handlers in `PlayerListener.java` that cancel throws when `CAN_INTERACT` is false.
- **Status**: Code fix applied, NOT confirmed by user.

### 2. Join prefix missing after rejoin
- **Complaint**: When a vanished player rejoins, the `[VANISHED]` tab prefix is missing.
- **Fix applied**: Increased delayed re-apply from 20L → 60L ticks, and added TAB API + integration hook re-push inside the delayed task.
- **Status**: Code fix applied, NOT confirmed by user.

### 3. No vanish indicator on the player entity
- **Complaint** (repeated, user very frustrated): Staff cannot see any visual indicator on a vanished player — no nametag prefix, no glow, nothing visible on the entity itself.
- **Fixes applied**:
  - Enabled `staff-glow: true` by default in `config.yml` (was `false`). Existing configs with `false` will NOT be auto-updated — must manually change or delete config to get new default.
  - Updated `ConfigManager` fallback default for `staffGlowEnabled` from `false` → `true`.
  - Added TAB API `NameTagManager` hook in `TabPluginHook.java` to set the nametag prefix via TAB's own API (instead of relying on Bukkit scoreboard teams which TAB overrides).
  - Staff-glow injects the glowing outline flag (`0x40`) at the ProtocolLib packet level — requires ProtocolLib.
- **Status**: Code fix applied, NOT confirmed by user. User must also ensure `staff-glow: true` is in their server's `config.yml` (the server's existing config may still have `false`).

### 4. CAN_INTERACT rule broken for spawn eggs
- **Complaint**: Clicking "Allow" for spawn eggs in the rule doesn't work. The whole `can_interact` rule is broken for eggs.
- **Fix applied**: Reordered checks in `PlayerListener.java` — `CAN_INTERACT` is now checked first, spawn egg block runs after (only when `CAN_INTERACT` is true).
- **Status**: Code fix applied, NOT confirmed by user.

---

## Iteration 3 UX Fixes

### 5. /vchat confirm must be typed — clicking does nothing
- **Complaint**: Player has to manually type `/vchat confirm` instead of clicking the chat message.
- **Fix applied**: Updated `messages.yml` `chat.locked` to use a MiniMessage `<click:run_command:'/vchat confirm'>` tag so clicking the text runs the command automatically.
- **Status**: Code fix applied, NOT confirmed by user.

### 6. Action bar appears delayed on vanish (not instant)
- **Complaint**: After typing `/vanish`, the "You are currently VANISHED" action bar shows with a delay (up to 1 second lag from scheduler tick).
- **Fix applied**: Added an immediate `player.sendActionBar(...)` call at the end of `applyVanishEffects()`, and `player.sendActionBar(Component.empty())` in `removeVanishEffects()`. No longer purely relying on the scheduler.
- **Status**: Code fix applied, NOT confirmed by user.

### 7. Visibility checks only run on scheduler — not at join/quit
- **Complaint**: When a player joins, there's a window where vanished players might be visible. Checks should trigger on events (join/quit), not just on the scheduler tick.
- **Fix applied**:
  - The periodic sync task now only handles mob targeting (not visibility updates).
  - The `onJoin` loop now calls `player.showPlayer()` OR `player.hidePlayer()` for each vanished player (not just `hidePlayer`), ensuring staff see the player and non-staff do not — immediately at join without waiting for a scheduler cycle.
- **Status**: Code fix applied, NOT confirmed by user.

---

## Notes
- The server's `testserver_clean/plugins/config.yml` (the live config) may still have `staff-glow: false`. The fix only changes the **default** in the JAR resource. You must either delete the server's `config.yml` so it regenerates, or manually change `staff-glow: false` → `staff-glow: true` in the live server config.
