# Vanish++ — Iteration 2 Fix Plan

## Issue 1 — Throwable / Usable Items Not Blocked While Vanished

**Symptom:** Vanished players can throw eggs, snowballs, ender pearls, shoot bows, etc. even when `CAN_INTERACT` is false.

**Root Cause:** `PlayerListener.onInteract` cancels `RIGHT_CLICK` events for the `CAN_INTERACT` rule, but throwable items fire an independent `ProjectileLaunchEvent` (and `EntityShootBowEvent` for bows) that is never intercepted. In some Paper versions cancelling the interact does not suppress the launch.

**Fix:** Add two new `@EventHandler` methods in `PlayerListener`:
- `onProjectileLaunch(ProjectileLaunchEvent)` — covers eggs, snowballs, ender pearls, wind charges, potions, exp bottles, tridents.
- `onShootBow(EntityShootBowEvent)` — covers bows and crossbows.
Both cancel and call `sendRuleDeny` when the player is vanished and `CAN_INTERACT == false`.

**Files:** `PlayerListener.java`

---

## Issue 2 — Nametag Prefix Missing After Vanished Player Rejoins

**Symptom:** A player who was vanished before disconnecting rejoins; the `[V]` nametag prefix above their head doesn't appear (or disappears shortly after).

**Root Cause:** The `reapplyTeamEntry` delay in `onJoin` is only 20 ticks (1 second). The TAB plugin processes its own join logic asynchronously and re-sends its SCOREBOARD_TEAM packets *after* the vanish plugin's 20-tick reapplication, overwriting the prefix.

**Fix:**
- Increase the reapply delay from `20L` → `60L` ticks (3 seconds).
- Inside the delayed task, also re-call `integrationManager.updateHooks(player, true)` and `tabPluginHook.update(player, true)` so TAB's API is notified *after* it has finished its own join processing.
- Add a public `getTabPluginHook()` getter to `Vanishpp.java`.

**Files:** `PlayerListener.java`, `Vanishpp.java`

---

## Issue 3 — No Visual Indicator on Vanished Player Entity for Staff

**Symptom:** Staff with `vanishpp.see` can see vanished players but have no clear visual indicator distinguishing them from normal players (no glow, nametag prefix unreliable).

**Root Cause / Design Gap:** The scoreboard team prefix (`[V]`) is set server-side and broadcast via SCOREBOARD_TEAM packets, but the existing ProtocolLib listener scrubs those packets for non-staff only — it does *not* ensure they always reach staff. There is also no glowing outline effect.

**Fix:**
- Add `staffGlowEnabled` boolean to `ConfigManager` (config key `vanish-appearance.staff-glow`, default `false`).
- Extend `ProtocolLibManager.modifyMetadataForStaff` to additionally set the glow bit (`0x40`) in entity flags byte (metadata index 0) when `staffGlowEnabled` is true. This makes the player render with a glowing outline *only* for the staff observer receiving that packet.
- Add config entry to `config.yml` and `messages.yml`.

**Note:** The glow effect is purely per-packet (ProtocolLib intercepts outgoing ENTITY_METADATA packets individually per recipient). Non-staff never see the glow. The scoreboard prefix is still scrubbed from non-staff via the existing SCOREBOARD_TEAM interceptor — it will not appear in sidebars or the TAB list for non-staff.

**Files:** `ProtocolLibManager.java`, `ConfigManager.java`, `config.yml`

---

## Issue 4 — CAN_INTERACT Rule Broken for Spawn Eggs (and [Allow] Button Non-Functional)

**Symptom:** A vanished player using a spawn egg sees the `[Allow 1m]` / `[Allow permanently]` buttons. Clicking them sets `CAN_INTERACT = true`, but spawn eggs are *still* blocked. The rule has no effect on eggs.

**Root Cause:** In `PlayerListener.onInteract`, the spawn-egg block runs **unconditionally before** the `CAN_INTERACT` check:
```
if (isSpawnEgg) → cancel + sendRuleDeny → return   // always fires
if (!CAN_INTERACT) → cancel                        // never reached for eggs
```
So even with `CAN_INTERACT = true`, eggs are always blocked. The `[Allow]` buttons are misleading — setting the rule doesn't help.

**Fix:** Reorder the logic so `CAN_INTERACT` is checked *first*:
- If `CAN_INTERACT == false` → cancel and call `sendRuleDeny` (with action name `"using spawn eggs"` for eggs, `"interaction"` otherwise). The `[Allow]` buttons will now actually work.
- If `CAN_INTERACT == true` but item is a spawn egg → cancel and show a *separate* informational message (`warnings.spawn-egg-blocked`) that does **not** include `[Allow]` buttons (since spawn eggs are always blocked to protect cover, even when CAN_INTERACT is on).

Add language key `warnings.spawn-egg-blocked` to `messages.yml`.

**Files:** `PlayerListener.java`, `messages.yml`

---

## Test Checklist

After deploying, test each fix in-game:

- [ ] **#1** Vanish yourself, try throwing an egg → should be blocked with action-bar warning. Set `CAN_INTERACT true` → eggs can be thrown.
- [ ] **#1** Vanish yourself, shoot a bow → blocked. Set `CAN_INTERACT true` → bow fires.
- [ ] **#2** Vanish, disconnect, reconnect → `[V]` prefix appears above your head within 3 seconds.
- [ ] **#3** Enable `vanish-appearance.staff-glow: true` in config. Vanish a player from staff account → glowing outline visible. Log in as non-staff → no glow visible.
- [ ] **#4** Vanish, try spawn egg with `CAN_INTERACT false` → blocked, `[Allow permanently]` button shown. Click it → `CAN_INTERACT true`. Try egg again → now works (or shows "always blocked" message if you want to confirm the separate path).
- [ ] **#4** Vanish, `CAN_INTERACT true`, try spawn egg → blocked with informational message (no [Allow] button shown).
