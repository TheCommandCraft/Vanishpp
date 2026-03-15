# Vanish++ 1.1.4 — In-Game Testing Checklist

> **Setup:** You need at least **two accounts** to test visibility properly.
> - **Account A** = OP admin (the vanished player)
> - **Account B** = non-OP normal player (the observer)
> Run `docker logs -f vanishppdevserver-mc-1` in a terminal to watch logs live.

---

## 1. Core Vanish Toggle

- [ ] `/vanish` → Account A vanishes (action bar appears, fly granted, night vision applied)
- [ ] `/vanish` again → Account A unvanishes (fly revoked, night vision removed, visible again)
- [ ] Account B runs `/vanish` without OP → **denied**, stays visible
- [ ] `/vanish <AccountB>` from Account A → Account B vanishes, Account A does **not**
- [ ] `/vanish <AccountB>` again → Account B unvanishes
- [ ] `/vanish nonexistentXYZ` → error message sent, no crash
- [ ] Vanish state survives `/vreload` (vanish persists across reload)
- [ ] Vanish state survives **server restart** (reconnect and you're still vanished)

---

## 2. Visibility — Who Sees What

> Vanish Account A. Have Account B online watching.

- [ ] Account B **cannot see** Account A (no player model, no nametag)
- [ ] Account B **cannot see** Account A in the **Tab List**
- [ ] Account A **can see** Account B normally
- [ ] OP Account C (with `vanishpp.see`) **can see** Account A (gray/italic in tab if ProtocolLib active)
- [ ] Non-OP Account D joining after A is vanished → **cannot see** A
- [ ] OP Account E joining after A is vanished → **can see** A
- [ ] `/op <AccountB>` while A is vanished → B now sees A **without relog** (heartbeat sync)
- [ ] `/deop <AccountB>` while A is vanished → B can no longer see A **without relog**

---

## 3. Join / Quit Messages

> All tested with A vanished.

- [ ] Account A **reconnects** while vanished → no real join message broadcast to B
- [ ] Account A **disconnects** while vanished → no real quit message broadcast to B
- [ ] When A **/vanishes** → fake "left the game" message shown to B (if `broadcast-fake-quit: true`)
- [ ] When A **unvanishes** → fake "joined the game" message shown to B (if `broadcast-fake-join: true`)
- [ ] Staff (Account C with `vanishpp.see`) sees `[Staff] A has vanished` notification
- [ ] Console shows the staff vanish/unvanish notification
- [ ] Silent join message shows to staff: `[+] A has silently joined`
- [ ] Silent quit message shows to staff: `[-] A has silently left`

---

## 4. God Mode & Damage

- [ ] Account B attacks Account A while A is vanished → **no damage taken**
- [ ] Account A stands in fire/lava while vanished → **no damage**
- [ ] Splash potion hits Account A → **no effect applied**
- [ ] Disable `invisibility-features.god-mode: false` via `/vconfig` → Account B can now damage A

---

## 5. Hunger

- [ ] Vanish Account A, sprint around → **hunger bar does not drop**
- [ ] Set `invisibility-features.disable-hunger: false` via `/vconfig` → hunger drops again

---

## 6. Night Vision

- [ ] Vanish in a dark cave → **night vision automatically applied**
- [ ] Unvanish → **night vision removed**
- [ ] Remove `vanishpp.nightvision` permission → night vision not granted on vanish

---

## 7. Flight

- [ ] Vanish → **flight automatically enabled**
- [ ] `flight-control.vanish-enable-fly: false` via `/vconfig` → no auto-fly on vanish
- [ ] Unvanish (default config) → **flight disabled**
- [ ] Give Account A `essentials.fly` → unvanish → **flight preserved**
- [ ] `flight-control.unvanish-disable-fly: false` → unvanish → flight preserved regardless

---

## 8. Mob Behavior

- [ ] Vanish Account A → **mobs nearby do not target A** (they ignore you entirely)
- [ ] Spawn a Zombie, let it lock onto A, then `/vanish` → **zombie instantly loses target**
- [ ] Mobs do **not look/turn their head** toward vanished A (True Sight Engine)
- [ ] Unvanish → mobs can target A again normally
- [ ] Set `can_chat` rule to allow `mob_targeting: true` via `/vrules mob_targeting true` → mobs attack A while vanished

---

## 9. Projectiles

- [ ] Account B shoots an arrow at Account A (vanished) → **arrow passes through A** (no hit)
- [ ] Arrow does **not drop at A's feet** (no ghost hit indicator)
- [ ] A throws a trident → same pass-through behavior for B

---

## 10. Collision & Physics

- [ ] Account B walks into Account A (vanished) → **no push**, B walks through A
- [ ] Account A walks into B → no push
- [ ] A walks over a **pressure plate** → **not triggered** (default rule)
- [ ] A walks over a **tripwire** → **not triggered**
- [ ] A walks on **farmland / turtle eggs** → **not broken**
- [ ] A walks near a **sculk sensor** → **not activated**

---

## 11. Silent Chests

- [ ] Account A (vanished) opens a chest → **no lid animation** for Account B
- [ ] Account A opens a barrel → **no sound or animation** for B
- [ ] Account A opens a shulker box → **no animation** for B
- [ ] Account A opens an ender chest → **no animation** for B
- [ ] Account A opens a furnace/hopper → check it doesn't crash (unsupported types silently fail)

---

## 12. Block Interaction Rules

> Default: break=false, place=false.

- [ ] A tries to break a block → **blocked**, action bar warning shown
- [ ] `/vrules can_break_blocks true` → A can now break blocks
- [ ] A tries to place a block → **blocked**
- [ ] `/vrules can_place_blocks true` → A can place blocks
- [ ] A punches an entity (mob/player) → **blocked**
- [ ] `/vrules can_hit_entities true` → A can hit
- [ ] A tries to pick up an item → **blocked**
- [ ] `/vrules can_pickup_items true` → A can pick up items
- [ ] A tries to drop an item → **blocked**
- [ ] `/vrules can_drop_items true` → A can drop items
- [ ] `/vrules all true` → all rules enabled at once
- [ ] `/vrules all false` → all rules disabled at once
- [ ] `/vrules can_break_blocks true 30` → temporary rule, reverts after 30 seconds
- [ ] `/vrules` (no args) → usage message shown
- [ ] `/vrules invalidrule true` → error: invalid rule name
- [ ] `/vrules can_chat maybe` → error: invalid value

---

## 13. Chat Confirmation

- [ ] `can_chat` rule is `false` → A types in chat → **message blocked**, prompt shown
- [ ] `/vchat confirm` → message is sent to all players
- [ ] `/vchat confirm` with no pending message → "no pending message" error
- [ ] `/vrules can_chat true` → A can chat normally without confirmation

---

## 14. Item Pickup Toggle (/vpickup)

- [ ] `/vpickup` → toggles pickup rule (on/off)
- [ ] `/vpickup` twice → returns to original state
- [ ] `/vpickup <AccountB>` (OP) → toggles B's pickup
- [ ] Account C (non-OP) runs `/vpickup <AccountB>` → **denied**

---

## 15. Tab Complete Scrubbing (requires ProtocolLib)

- [ ] Account B types `/tell ` and presses Tab → **A's name does NOT appear**
- [ ] Account B types in chat and presses Tab → **A's name does NOT appear**
- [ ] OP Account C presses Tab → **A's name appears** (staff can see)

---

## 16. Server List

- [ ] Both A and B online; A vanished → server list hover shows B's name only
- [ ] Player count in server list shows **1 fewer** than actual (e.g., shows `1/20` not `2/20`)
- [ ] `vanish-appearance.adjust-server-list-count: false` → count is normal

---

## 17. Death Messages

- [ ] Account A (vanished) dies (fall damage, mob) → **no death message** broadcast
- [ ] Account A (not vanished) dies → death message shown normally

---

## 18. Advancement Announcements

- [ ] Account A (vanished) gets a new advancement → **no announcement** broadcast
- [ ] Account A unvanishes → advancements announce again

---

## 19. Raid Trigger

- [ ] Account A (vanished) enters a village with Bad Omen → **raid does NOT trigger**
- [ ] Account A unvanishes with Bad Omen in a village → raid triggers normally

---

## 20. Sleeping

- [ ] Account A (vanished) tries to sleep in a bed → **blocked/denied**
- [ ] `invisibility-features.prevent-sleeping: false` → A can sleep

---

## 21. Entity Interaction

- [ ] Account A (vanished) right-clicks a horse → **cannot mount**
- [ ] A right-clicks a villager → check if blocked (based on `prevent-entity-interact`)
- [ ] `prevent-entity-interact: false` → interaction works

---

## 22. Potion Effects

- [ ] Another player throws a Splash Poison at A (vanished) → **no effect on A**
- [ ] Lingering potion cloud on ground → A walks through → **no effect**

---

## 23. Plugin List Hiding

- [ ] Account B runs `/plugins` → **Vanishpp is NOT in the list**
- [ ] Account A (OP) runs `/plugins` → **Vanishpp IS visible** (with notification)
- [ ] OP gets a chat notification about plugin hiding
- [ ] `/vack disable_hiding` → sets `hide-from-plugin-list: false`, Vanishpp now visible to all

---

## 24. Action Bar

- [ ] Vanish → persistent action bar message shows (e.g., "You are currently VANISHED")
- [ ] Trigger a blocked rule → action bar shows `✖ Action Blocked: ...` for ~2s, then restores the normal vanish message
- [ ] `vanish-appearance.action-bar.enabled: false` via `/vconfig` → action bar stops

---

## 25. Tab List Appearance (requires ProtocolLib)

- [ ] Account A (vanished) appears in the tab list for **staff** as gray/italic
- [ ] Account A does **not appear** in the tab list for non-staff
- [ ] Tab prefix (`[VANISHED]`) shown for A in tab list (staff view)

---

## 26. Nametag (above head)

- [ ] Account C (OP, can see A) → A's nametag **visible above head** for C
- [ ] Account B (no perm) → **no nametag or floating name** visible for A

---

## 27. /vlist Command

- [ ] Vanish Account A, then `/vlist` (as OP) → A's name appears in list
- [ ] No vanished players → `/vlist` → empty state message
- [ ] Account B (non-OP) runs `/vlist` → **denied**

---

## 28. /vhelp Command

- [ ] `/vhelp` → interactive help menu appears with all commands listed
- [ ] `/vhelp vanish` → detailed info for the `/vanish` command shown
- [ ] `/vhelp invalidcmd` → error or fallback

---

## 29. /vconfig Command

- [ ] `/vconfig invisibility-features.god-mode false` → god mode disabled immediately
- [ ] `/vconfig invisibility-features.god-mode true` → god mode re-enabled
- [ ] `/vconfig nonexistent.path value` → "invalid config path" error
- [ ] `/vconfig invisibility-features.god-mode` (no value) → shows current value
- [ ] `/vconfig` without `vanishpp.config` perm → denied

---

## 30. /vreload Command

- [ ] Edit `config.yml` directly, then `/vreload` → changes applied without restart
- [ ] Action bar task restarts correctly after reload (action bar still shows)

---

## 31. /vperms Command

- [ ] `/vperms <B> vanishpp.vanish set` → B can now `/vanish`
- [ ] `/vperms <B> vanishpp.vanish remove` → B can no longer `/vanish`
- [ ] `/vperms <B> vanishpp.vanish get` → shows whether B has it
- [ ] `/vperms reload` → reloads the permissions file, sends confirmation
- [ ] `/vperms <B> vanishpp.nonexistent set` → error: invalid permission
- [ ] `/vperms` (no args) → usage message
- [ ] Account C (non-OP) runs `/vperms` → denied
- [ ] `/vperms <B> vanishpp.* set` → B gets all permissions (wildcard)
- [ ] `/vperms <B> vanishpp.abilities set` → B gets all ability-tier permissions
- [ ] `/vperms <B> vanishpp.management set` → B gets management-tier permissions
- [ ] `/vperms <B> vanishpp.core set` → B gets core vanish/see permissions

---

## 32. /vanishrules for Others

- [ ] `/vrules <AccountB> can_chat true` (OP) → B's chat rule updated
- [ ] Account C (non-OP) runs `/vrules <B> can_chat true` → **denied**
- [ ] `/vrules <AccountB>` (get view) → shows B's current rules

---

## 33. /vignore (ProtocolLib Warning)

- [ ] Remove ProtocolLib, restart → join as OP → big red warning + title shown
- [ ] `/vignore` → warning silenced, won't show again on next join
- [ ] `/vignore` again → warning re-enabled
- [ ] OP runs `/vignore <AccountB>` → B's warning state toggled
- [ ] Reinstall ProtocolLib → no more warning

---

## 34. /vack Command

- [ ] `/vack migration` → migration notification dismissed (won't show on next join)
- [ ] `/vack disable_hiding` → sets `hide-from-plugin-list: false` in config

---

## 35. Setup Advisor (Config Sanity Checker)

- [ ] `hooks.simple-voice-chat.enabled: true` (SimpleVoiceChat not installed) → **WARN in console** and **shown in chat to OP on join**
- [ ] Set `hooks.simple-voice-chat.enabled: false` → warning gone
- [ ] Add a PAPI placeholder (e.g., `%player_name%`) to action bar text, restart without PlaceholderAPI → **WARN shown**

---

## 36. Layered Permissions

- [ ] Enable `permissions.layered-permissions-enabled: true`
- [ ] Give Account A `vanishpp.vanish.level.3` → A vanishes at level 3
- [ ] Account B has `vanishpp.see` (default see level 1) → **B cannot see A**
- [ ] Give Account B `vanishpp.see.level.3` → **B can now see A**
- [ ] Two accounts both at level 1 → can see each other normally

---

## 37. Storage Persistence

- [ ] `/vanish` → disconnect → reconnect → **still vanished**
- [ ] `/vrules can_break_blocks true` → `/vreload` → rule still `true`
- [ ] `/vpickup` (enable) → `/vreload` → pickup still enabled
- [ ] `/vack migration` → reconnect → migration message **does not reappear**
- [ ] `/vperms <B> vanishpp.vanish set` → `/vreload` → B still has permission

---

## 38. PlaceholderAPI Placeholders

> Requires PlaceholderAPI installed. Use a scoreboard or TAB plugin to display.

- [ ] `%vanishpp_is_vanished%` → shows `Yes` when vanished, `No` when not
- [ ] `%vanishpp_is_vanished_bool%` → `true` / `false`
- [ ] `%vanishpp_vanished_count%` → correct count of online vanished players
- [ ] `%vanishpp_visible_online%` → total minus vanished
- [ ] `%vanishpp_prefix%` → shows `[VANISHED]` prefix when vanished, empty when not
- [ ] `%vanishpp_pickup%` → shows `Enabled` / `Disabled`
- [ ] `%vanishpp_vanished_list%` → comma-separated list of vanished player names

---

## 39. ProtocolLib — Full Packet Verification

- [ ] No `NAMED_ENTITY_SPAWN` warning in logs (fixed in 1.1.4) ✅
- [ ] No errors on startup related to ProtocolLib packet registration
- [ ] Tab scrubbing works (section 15 above)
- [ ] Spawn packet hidden (A not visible to B even when B is within render distance)
- [ ] Scoreboard team packet scrubbed (A's name not in team packets sent to B)
- [ ] Server list ping count correct (section 16 above)

---

## 40. Update Checker

- [ ] OP joins → if a newer version exists on Modrinth, clickable update notification in chat
- [ ] `update-checker.enabled: false` → no notification
- [ ] `update-checker.notify-mode: list` and `update-checker.notify-list: [YourName]` → only listed players see it

---

## Quick Regression Sweep

After all individual tests, do a final sanity pass:

- [ ] Log a fresh server startup → **zero ERROR lines** from Vanishpp
- [ ] `/vanish` → `/vanish` → `/vanish` (rapid toggle) → no desync or crash
- [ ] Vanish, walk around for 2 minutes → heartbeat sync tick fires repeatedly → no lag/error
- [ ] Two players vanish simultaneously → both invisible to non-staff, staff sees both
- [ ] One player unvanishes while another stays vanished → visibility updated correctly for everyone

---

*Generated for Vanish++ v1.1.4 on Paper 1.21.11*
