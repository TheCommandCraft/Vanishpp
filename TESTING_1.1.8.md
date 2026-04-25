# Vanish++ 1.1.8 — Full Manual Test Checklist

> **Status:** Untested  
> **Branch:** `feature/wishlist-impl`  
> **Test servers:** `docker/` — Paper :25565, Purpur :25566, Folia :25567  
> **RCON password:** `vanishtest`  
> **Start:** `bash start.sh paper` / `bash update.sh --restart`  
>
> Run at least Paper and one second server for cross-server tests.  
> Mark each item `[x]` as you verify it. Note failures below each item.

---

## Setup / Prerequisites

Before testing, confirm:

- [ ] JAR builds cleanly: `mvn clean verify` → 347 tests, BUILD SUCCESS
- [ ] `update.sh --restart` deploys to Paper (primary test server)
- [ ] Server log shows `Vanish++ 1.1.8 enabled`
- [ ] `config.yml` and `messages.yml` are auto-generated (no stale files from old version)
- [ ] Join as OP player — confirm no startup warnings you don't expect in console

---

## 1. Core Vanish / Unvanish (Regression)

**Two players required: OP (`admin`) and non-OP (`user`)**

- [ ] `/vanish` — admin disappears from user's tab + view
- [ ] `/vanish` again — admin reappears
- [ ] `/vanish admin` (run as user) — user has no permission → unknown-command message
- [ ] `/vanish user` (run as admin) — vanishes user, message sent to both
- [ ] Vanish state survives `/vanishreload`
- [ ] Vanish state survives server restart (player rejoins vanished)
- [ ] Non-vanished player cannot see vanished player in tab list
- [ ] Vanished player IS visible to other vanished players (both `vanishpp.see`)

---

## 2. Vanish Rules & /vrules

- [ ] `/vrules` (no args) — shows usage
- [ ] `/vrules can_break_blocks true` — break a block while vanished → allowed
- [ ] `/vrules can_break_blocks false` — block attempt → blocked, notification shown
- [ ] `/vrules can_chat true` → chat while vanished works directly
- [ ] `/vrules can_chat false` → vanish chat confirmation required
- [ ] `/vrules mob_targeting true` — zombie attacks vanished player
- [ ] `/vrules mob_targeting false` — zombie ignores vanished player
- [ ] `/vrules show_notifications false` — block messages silenced
- [ ] `/vrules spectator_gamemode true` — double-shift enters spectator while vanished
- [ ] `/vrules all true` — all rules enabled at once
- [ ] `/vrules all false` — all rules disabled at once
- [ ] `/vrules <player> can_break_blocks true` (admin targeting user) — requires `vanishpp.rules.others`
- [ ] Temporary rule: `/vrules can_break_blocks true 30` — expires after 30s, "expired" message shown
- [ ] Rules persist across relog

### Rules GUI

- [ ] `/vrules` with no args when holding right-click ← actually this is the wand; run `/vrules` as a player with 0 args to see usage, then test GUI via wand shift-click
- [ ] Shift-right-click wand → Rules GUI opens
- [ ] Left-click a rule pane → toggles on/off, glass color updates
- [ ] GUI closes cleanly (no inventory leak)

### Rule Presets

- [ ] `/vrules preset save stealth` — saves current rules as "stealth"
- [ ] `/vrules preset list` — shows "stealth" in list
- [ ] `/vrules preset load stealth` — restores saved rules
- [ ] `/vrules preset delete stealth` — removes preset
- [ ] `/vrules preset load nosuchpreset` — "not found" message
- [ ] Built-in presets: `/vrules preset load stealth|spectator|builder` — each loads distinct rule set

---

## 3. Vanish Wand (/vwand)

- [ ] `/vwand` gives blaze rod with correct display name in hotbar
- [ ] Right-click wand (not sneaking) → toggles vanish
- [ ] Shift-right-click wand → opens Rules GUI
- [ ] Wand item doesn't trigger block/entity interactions (event cancelled)
- [ ] Wand with non-OP player (no `vanishpp.vanish`) → no effect
- [ ] Config: change `vanish-wand.material` to `STICK`, reload → stick becomes wand
- [ ] Config: `vanish-wand.enabled: false`, reload → wand does nothing

---

## 4. Bossbar Status Indicator

- [ ] `bossbar.enabled: true` in config, vanish → bossbar appears with configured title/color/style
- [ ] Unvanish → bossbar disappears
- [ ] `/vsb bossbar` — toggle bossbar independently
- [ ] Change `bossbar.color: RED` and `bossbar.style: SEGMENTED_20`, reload → bossbar reflects changes
- [ ] `bossbar.enabled: false` in config → no bossbar on vanish

---

## 5. Admin Dashboard GUI (/vadmin)

- [ ] `/vadmin` opens 54-slot inventory
- [ ] Each vanished player shown as skull with name, level, elapsed time, reason
- [ ] Left-click skull → opens that player's Rules GUI
- [ ] Right-click skull → unvanishes that player
- [ ] Info panel (slot 49) shows count
- [ ] Dashboard closes when player rejoins — reopen shows updated list
- [ ] No vanished players → dashboard is empty (skulls absent)

---

## 6. Vanish Scoreboard (/vsb)

- [ ] Auto-shows on vanish (default config)
- [ ] `/vsb` — toggles scoreboard off
- [ ] `/vsb` again — toggles back on
- [ ] `/vsb scoreboard` (alias test — check it's same as `/vanishscoreboard`)
- [ ] Scoreboard shows expected lines from `scoreboards.yml`
- [ ] Scoreboard disappears on unvanish (when auto-show is enabled)
- [ ] `scoreboard.auto-show-on-vanish: false` in config → no auto-show; `/vsb` still works

---

## 7. Action Bar

- [ ] Vanish → `&bYou are currently VANISHED` shows in action bar
- [ ] `action-bar.enabled: false` → no action bar on vanish
- [ ] Action bar text customizable via `messages.yml` `appearance.action-bar` key

---

## 8. Staff Notifications

- [ ] Vanish (self) → other staff see `[Staff] X has vanished (by X).`
- [ ] Vanish (target) → other staff see `[Staff] X has vanished (by admin).`
- [ ] Unvanish → corresponding unvanish notification
- [ ] `messages.staff-notify.enabled: false` → notifications suppressed

---

## 9. Silent Join / Quit

- [ ] Admin joins already vanished (`vanishpp.join-vanished` perm) → user sees nothing, staff see `[+] admin has silently joined.`
- [ ] Admin quits while vanished → user sees nothing, staff see `[-] admin has silently left.`
- [ ] Fake join/quit messages configurable (`messages.fake-join`, `messages.fake-quit`)
- [ ] `vanish-effects.hide-real-join-messages: true` → no vanilla join message visible to non-staff

---

## 10. Sound Notifications for Staff (NEW 1.1.8)

Requires `staff-sounds.enabled: true` in config.

- [ ] Player vanishes → all online staff (with `vanishpp.see`) hear configured `vanish.sound`
- [ ] Player unvanishes → staff hear `unvanish.sound`
- [ ] Staff-only silent join → all staff hear `silent-join.sound`
- [ ] Staff-only silent quit → all staff hear `silent-quit.sound`
- [ ] Change volume/pitch in config, reload → new values apply
- [ ] `staff-sounds.enabled: false` → no sounds at all
- [ ] Staff player themselves does NOT hear the sound (sound only to _other_ staff)

---

## 11. Vanish Reason

- [ ] `/vanish My reason here` — vanishes with reason attached
- [ ] `/vlist` as staff → hover over name shows reason
- [ ] `/vadmin` skull lore shows reason
- [ ] `/vhistory` entry includes reason
- [ ] `/vanish reason Updated reason` mid-session → updates reason, confirmation message
- [ ] `/vanish reason` (no text) → clears reason or shows usage

---

## 12. Timed Vanish (NEW 1.1.8)

- [ ] `/vanish 30s` — vanish for 30 seconds; action bar shows countdown (`29s`, `28s`…)
- [ ] Auto-unvanish fires exactly at expiry; "timed vanish expired" message shown
- [ ] `/vanish 5m` — 5-minute timer works
- [ ] `/vanish 1h` — 1-hour timer schedules correctly
- [ ] `/vanish 2d` — 2-day timer (confirm it doesn't crash)
- [ ] `/vanish cancel` — cancels own timer; "cancelled" message
- [ ] `/vanish cancel <player>` — admin cancels another player's timer
- [ ] `timed-vanish.enabled: false` → duration arg silently ignored (or blocked with message)
- [ ] Timer survives `/vanishreload` *(regression — reload restarts task)*
- [ ] Manually unvanishing a timed player before expiry cancels the timer (no ghost unvanish later)
- [ ] `/vanish player 30s reason text` — timed + reason combined

---

## 13. Auto-Vanish on AFK (NEW 1.1.8)

Requires `afk-auto-vanish.enabled: true`, `seconds: 10` (set low for testing).

- [ ] Stand still for 10 seconds → auto-vanished; action bar notice `"auto-vanished due to inactivity"`
- [ ] Move (mouse or walk) → auto-unvanished; `"Welcome back!"` message
- [ ] Player with `vanishpp.afk.bypass` perm → never auto-vanished
- [ ] Already vanished (manually) → AFK timer doesn't double-vanish
- [ ] `afk-auto-vanish.enabled: false` → no auto-vanish regardless of idle time
- [ ] After auto-unvanish, standing still again → auto-vanishes again (repeatable)

---

## 14. Anti-Combat Vanish (NEW 1.1.8)

Requires `anti-combat-vanish.enabled: true`.

- [ ] Take PvP damage → attempt `/vanish` → blocked with `"PvP cooldown (Xs remaining)"` message
- [ ] Take PvE damage (mob hit) → attempt `/vanish` → blocked with PvE cooldown message
- [ ] Wait out cooldown → `/vanish` works normally
- [ ] Player with `vanishpp.combat.bypass` → can vanish immediately after combat
- [ ] `pvp-cooldown-seconds: 0` → PvP cooldown disabled effectively
- [ ] `pve-cooldown-seconds: 0` → PvE cooldown disabled

---

## 15. Vanish Toggle Rate Limit (NEW 1.1.8)

Requires `vanish-rate-limit.enabled: true`, `seconds: 5`.

- [ ] Vanish → immediately try to unvanish → blocked: `"toggling too quickly"` message
- [ ] Wait 5 seconds → unvanish works
- [ ] Player with `vanishpp.ratelimit.bypass` → can toggle immediately
- [ ] `vanish-rate-limit.enabled: false` → no cooldown

---

## 16. Partial Visibility / Semi-Vanish (NEW 1.1.8)

**Three players required: admin, staffB (with see perm), user**

- [ ] `/vanish --visible staffB` — admin is semi-vanished; staffB can see them, user cannot
- [ ] `/vlist` as admin → admin shows as `◑ (gold)` in list
- [ ] `/vlist` hover shows `"Partial vanish — visible to 1 player(s)"`
- [ ] `/vanish --visible staffB,staffC` — visible to multiple players
- [ ] `/vanish --visible none` — collapses to full vanish (staffB can no longer see)
- [ ] Admin is still hidden from tab, server list count as full-vanish
- [ ] Full unvanish (`/vanish`) removes partial state entirely
- [ ] `/vanish player --visible staffB` — admin applies partial vanish to another player

---

## 17. Quick Spectate (/vspec)

- [ ] `/vspec <player>` while vanished → teleports to player, enters spectator; HUD message shown
- [ ] `/vspec stop` → returns to previous location and gamemode
- [ ] `/vspec <player>` while NOT vanished → blocked (must be vanished)
- [ ] Target logs off during spectate → graceful return / message shown
- [ ] Unvanishing while spectating → forced back to correct gamemode
- [ ] Player with `vanishpp.spectator.bypass` → stays in spectator after unvanish
- [ ] `/vspec stop` when not spectating → "not spectating" message

---

## 18. Player Follow / Track Mode (/vfollow)

- [ ] `/vfollow <player>` while in spectator (via /vspec) → camera locks onto target
- [ ] Action bar HUD shows `"Following <player>"`
- [ ] Move around → camera snaps back to target each tick
- [ ] Target teleports to another location → follow persists
- [ ] `/vfollow stop` → releases camera lock; "released" message
- [ ] Target logs off → follow ends automatically; "disconnected" message
- [ ] `/vfollow` while not vanished → blocked

---

## 19. Incognito Mode (/vincognito)

**Requires a second player**

- [ ] `/vincognito` — enables; assigned fake name (from config pool or custom)
- [ ] `/vincognito Notch` — specific fake name
- [ ] Chat as incognito player → all players see fake name
- [ ] Tab list shows fake name for non-staff
- [ ] Staff with `vanishpp.see` see both `Notch (realName)` or real name in chat
- [ ] Death message shows fake name to non-staff
- [ ] `/vincognito off` — disables; real name restored everywhere
- [ ] `/vincognito <player>` — admin toggles for another player
- [ ] `vanishpp.incognito.others` required for targeting others
- [ ] Invalid fake name (>16 chars or blank) → error message

---

## 20. Bulk Vanish

- [ ] `/vanish all` — all online players vanished; count message shown
- [ ] `/vanish world <world>` — all players in specified world vanished
- [ ] `/vanish group <permission>` — all players with that permission vanished
- [ ] Corresponding unvanish: `/unvanish all` (or `/vanish all` when all are vanished?) — test the toggle behaviour
- [ ] `vanishpp.vanish.bulk` required; non-holders get denied
- [ ] Empty world/group → sensible message (0 players)

---

## 21. Vanish History / Audit Log (/vhistory)

- [ ] Vanish and unvanish several times with different reasons
- [ ] `/vhistory` → shows own history, newest first; each entry has timestamp + action + reason
- [ ] `/vhistory <player>` (admin) → shows that player's history
- [ ] `/vhistory <player> 2` → page 2 if enough entries
- [ ] `/vhistory <offline-player>` → works via UUID lookup (uses name cache)
- [ ] `vanish-history.retention-days: 1` → entries older than 1 day pruned on next load
- [ ] History entries survive `/vanishreload` and server restart

---

## 22. Auto-Vanish on Join (/vautovanish)

- [ ] `/vautovanish` — toggles own auto-vanish-on-join on; confirmation message
- [ ] Log out and back in → rejoins vanished
- [ ] `/vautovanish off` — disables; log out and back in → rejoins visible
- [ ] `/vautovanish <player> on` — admin enables for another player
- [ ] Setting persists in storage
- [ ] Player without `vanishpp.vanish` cannot enable (or is auto-vanish meaningless for them)

---

## 23. Vanish Statistics (/vstats)

- [ ] Vanish/unvanish several times with varying durations
- [ ] `/vstats` — shows own stats: total time, session count, longest session
- [ ] `/vstats <player>` (admin) — shows another player's stats
- [ ] Stats accumulate across sessions
- [ ] Opt-out: give player `vanishpp.stats.bypass` → `/vstats <player>` returns no-data or access-denied message
- [ ] `/vstats` for player with no history → shows zeroes

---

## 24. Vanish Zones (/vzone)

- [ ] `/vzone create myzone 10` — creates no-vanish zone at current location, radius 10
- [ ] Walk into zone while vanished → forced unvanish; "myzone is a no-vanish zone" message
- [ ] Attempt to `/vanish` inside zone → blocked with "cannot vanish in this region"
- [ ] `/vzone list` — shows zone name, world, coordinates, radius
- [ ] `/vzone delete myzone` — removes zone; can now vanish there
- [ ] Player with `vanishpp.zone.bypass` → can vanish inside zone
- [ ] `/vzone reload` — reloads zones from storage
- [ ] Zones persist across `/vanishreload` and server restart

---

## 25. WorldGuard Integration

**Requires WorldGuard installed**

- [ ] Create WG region; set flag `deny-vanish allow` → vanishing inside blocked
- [ ] Set flag `force-vanish allow` → entering region while visible auto-vanishes you
- [ ] Set flag `deny-unvanish allow` → cannot unvanish inside region
- [ ] `worldguard.enabled: false` in config → WG flags ignored
- [ ] Non-WG server → plugin loads cleanly with no errors

---

## 26. LuckPerms Context Integration

**Requires LuckPerms installed**

- [ ] Vanish → check LP context: `vanishpp:vanished=true` present
- [ ] Unvanish → context flips to `vanishpp:vanished=false`
- [ ] LP permission node checks that depend on vanish context work
- [ ] No LuckPerms installed → loads cleanly, context features silently disabled

---

## 27. Webhook Support

**Requires a local HTTP server (e.g. `python3 -m http.server` or `ngrok`)**

- [ ] `webhook.enabled: true`, configure a test URL
- [ ] Vanish → POST received with correct `{player, action, reason, server, timestamp}` JSON
- [ ] Unvanish → second POST with `action: unvanish`
- [ ] `auth-header: "Bearer mytoken"` → Authorization header present in request
- [ ] Invalid URL → server logs warning, does not crash
- [ ] Retry on failure: kill the HTTP server, vanish, restart HTTP server → eventually receives the event

---

## 28. Inventory Inspection (Invsee)

**Requires either OpenInv or InvSee++ installed for full support**

- [ ] Shift-right-click on a player while vanished → opens their inventory view
- [ ] Without `vanishpp.invsee.modify` → inventory is view-only (items cannot be moved)
- [ ] With `vanishpp.invsee.modify` → items can be moved/dropped
- [ ] InvSee hint shown once on first use (without OpenInv/InvSee++ installed)
- [ ] `/vack invsee` → dismisses hint permanently
- [ ] Target closes inventory before viewer → graceful close

---

## 29. Chat Detection Blocking (/msg, /tell, /r, /me)

- [ ] Non-staff uses `/msg <vanished-player> hello` → gets "No player was found" (fake error)
- [ ] Non-staff uses `/tell <vanished-player> hi` → same fake error
- [ ] Non-staff uses `/r` (reply to vanished) → same fake error
- [ ] Staff with `vanishpp.see` can `/msg <vanished-player>` successfully
- [ ] Configurable fake error via `commands.msg-player-not-found` in messages.yml

---

## 30. Plugin Hiding

- [ ] Non-staff runs `/plugins` → Vanish++ not in list
- [ ] Staff (with `vanishpp.see` or op) runs `/plugins` → Vanish++ visible
- [ ] `hide-announcements.hide-from-plugin-list: false` → visible to everyone
- [ ] Tab-complete for `/vanish` hidden from non-staff (`invisibility-features.hide-from-tab-complete: true`)

---

## 31. Vanish Effects (Regression)

- [ ] God mode: vanished player takes no damage
- [ ] Night vision: auto-applied on vanish, removed on unvanish
- [ ] Flight: auto-granted on vanish, removed on unvanish (`flight-control.unvanish-disable-fly: true`)
- [ ] No hunger: food bar frozen while vanished
- [ ] No mob targeting: mobs do not attack or look at vanished player
- [ ] Projectiles pass through vanished player
- [ ] Silent chests: open chest while vanished → no lid animation / sound for nearby players
- [ ] Pressure plates/buttons: vanished player does not trigger them
- [ ] No raid trigger: walk near village while vanished → no raid starts
- [ ] No sculk activation: walk over sculk sensor while vanished → not triggered
- [ ] No crop trample: jump on farmland while vanished → not broken

---

## 32. Staff Glow (Requires ProtocolLib)

- [ ] Vanish → other staff with `vanishpp.see` see glowing outline
- [ ] Non-staff cannot see glow
- [ ] `vanish-appearance.staff-glow: false` → no glow
- [ ] Glow survives world change

---

## 33. Tab Prefix / Nametag Prefix (Requires ProtocolLib for nametag)

- [ ] Vanished player shows `[VANISHED]` prefix in tab for other staff
- [ ] Non-staff see nothing in tab (player not listed)
- [ ] Nametag above head shows `[V]` for staff (requires ProtocolLib + scoreboard team support)
- [ ] On Folia → scoreboard team warning logged, nametag feature disabled gracefully

---

## 34. Config Live Edit (/vconfig)

- [ ] `/vconfig vanish-appearance.tab-prefix "[VAN] "` → reloads, prefix changes immediately
- [ ] `/vconfig invisibility-features.god-mode false` → god mode off immediately
- [ ] Sensitive key (e.g. `storage.type`) → warns first, requires `--confirm`
- [ ] `/vconfig export test.json` → file created in plugin folder
- [ ] `/vconfig import test.json` → loads and validates; active config matches file
- [ ] `/vconfig` (no args) → shows usage
- [ ] Invalid key → "invalid config path" message

---

## 35. Proxy Config GUI (/vproxygui) (NEW 1.1.8)

**Standalone (no proxy connected):**

- [ ] `/vproxygui` opens 54-slot GUI titled "Proxy Config — Vanish++"
- [ ] Slot 49 shows gray dye: "Proxy: Not Connected"
- [ ] Panes show current config values (green = true, red = false) with correct keys in lore
- [ ] Click a pane → toggles value, pane color updates
- [ ] Change is saved to `config.yml` (check file after clicking)
- [ ] Close button (slot 53 / barrier) closes GUI cleanly
- [ ] Non-`vanishpp.config` player → permission denied, GUI doesn't open

**With proxy connected (cross-server test):**

- [ ] Click a pane → CONFIG_SYNC sent to proxy (check Velocity logs)
- [ ] Slot 49 shows lime dye: "Proxy: Connected"
- [ ] Other Paper server receives updated config (check config value on Server B)

---

## 36. In-Game Changelog (/vchangelog)

- [ ] `/vchangelog` → shows styled 1.1.8 changelog
- [ ] New-version join notification shown once on first join after update (dismissible)
- [ ] `/vack changelog` or clicking dismiss → suppresses for that version
- [ ] Second join after dismiss → no repeat notification

---

## 37. messages.yml Auto-Migration

- [ ] Install old version config (missing some keys), upgrade JAR → new keys auto-added with defaults
- [ ] Existing custom keys preserved
- [ ] `config-version` bumped correctly
- [ ] Migration report shown to OP on first join after migration (dismissible with `/vack migration`)

---

## 38. Per-World Rule Defaults (NEW 1.1.8)

Set in config:
```yaml
world-rules:
  world_nether:
    can_break_blocks: true
    mob_targeting: true
```

- [ ] Enter nether → `can_break_blocks` overridden to true; can break blocks without manual `/vrules`
- [ ] Leave nether → previous `can_break_blocks` value restored
- [ ] Personal `/vrules` override takes precedence while in that world
- [ ] Multiple worlds each with different override sets work independently
- [ ] `world-rules: {}` → no overrides, no errors

---

## 39. Additional PAPI Placeholders (NEW 1.1.8)

**Requires PlaceholderAPI installed**

- [ ] `%vanishpp_is_vanished%` → `true` / `false` for self
- [ ] `%vanishpp_vanish_duration%` → `2m 30s` style string while vanished
- [ ] `%vanishpp_vanish_reason%` → current reason or empty string
- [ ] `%vanishpp_vanish_level%` → numeric level
- [ ] `%vanishpp_vanish_level_<player>%` → level for named player
- [ ] `%vanishpp_is_vanished_<player>%` → vanish state for named player
- [ ] `%vanishpp_rule_can_break_blocks%` → `true`/`false` for own rule
- [ ] `%vanishpp_can_see_<player>%` → `true` if you can see them, `false` otherwise
- [ ] `%vanishpp_visible_player_list%` → comma-separated list of non-vanished players
- [ ] All placeholders return empty string (not error) when player is not vanished

---

## 40. Cross-Server Global Vanish Broadcast (NEW 1.1.8)

**Requires two Paper servers + Velocity proxy**

- [ ] `proxy.broadcast-vanish-events: true`, `broadcast-min-level: 1`
- [ ] Player vanishes on Server A → staff on Server B (with `vanishpp.see`) see `[Network] player has vanished on ServerA`
- [ ] Player unvanishes on Server A → staff on Server B see unvanish message
- [ ] `broadcast-min-level: 5` → player with level 1 vanishes → no cross-server broadcast
- [ ] `proxy.broadcast-vanish-events: false` → no broadcast even if proxy connected
- [ ] Staff who are on the ORIGIN server do not get the proxy broadcast (they already got the local notification)

---

## 41. Proxy-Side /vlist (Velocity) (NEW 1.1.8)

**Run from Velocity proxy console or as a player on proxy**

- [ ] `/vlist` on Velocity proxy console → lists all vanished players across all servers with `[server]` tags
- [ ] Works even when a backend server has no players online (state from ProxyStateManager)
- [ ] Player vanishes on Server A, admin runs `/vlist` on Server B → Server A player appears with `[serverA]` tag
- [ ] No vanished players → "No players are currently vanished."
- [ ] Results sorted by server then player name
- [ ] `vanishpp.list` permission enforced on proxy

---

## 42. Geyser / Floodgate Form UI (NEW 1.1.8)

**Requires Bedrock client + Floodgate + Geyser**

- [ ] Floodgate not installed → Java behavior unchanged (no errors, no form attempts)
- [ ] Bedrock player right-clicks vanish wand → ModalForm appears with "Vanish"/"Cancel" buttons
- [ ] Confirm → vanished; cancel → nothing happens
- [ ] Bedrock player runs `/vrules` (no args) → SimpleForm shows all rule buttons with ON/OFF state
- [ ] Tap a rule button → that rule toggles; form closes
- [ ] Bedrock shift-right-click wand → SimpleForm rules list (same as `/vrules` no-args)
- [ ] Forms use main-thread callbacks (no concurrent modification errors)

---

## 43. Public VanishAPI

**Best tested via a small test plugin or the existing API tests**

- [ ] `VanishAPI.isVanished(player)` → correct boolean
- [ ] `VanishAPI.vanish(player)` → vanishes player programmatically
- [ ] `VanishAPI.unvanish(player)` → unvanishes player
- [ ] `VanishAPI.getVanishLevel(player)` → returns level
- [ ] `VanishAPI.getRules(player)` → returns rule map
- [ ] `VanishEvent` is cancellable: cancel it → vanish does not apply
- [ ] `UnvanishEvent` is cancellable: cancel it → unvanish does not apply
- [ ] `VanishStateChangeEvent` fires on both vanish and unvanish

---

## 44. Permission System (/vperms + Layered Levels)

- [ ] `/vperms <player> vanishpp.see set` → grants permission to player
- [ ] `/vperms <player> vanishpp.see get` → confirms player has it
- [ ] `/vperms <player> vanishpp.see remove` → removes it; player can no longer see vanished
- [ ] Layered levels: admin (level 5) vanishes → staff (see level 3) cannot see them; staff (see level 6) can see them
- [ ] `permissions.layered-permissions-enabled: false` → all staff with `vanishpp.see` see all vanished players regardless of level

---

## 45. Folia Compatibility

**Use Folia server (localhost:25567)**

- [ ] Plugin enables cleanly: "Folia environment detected. Using Regional Scheduler."
- [ ] Basic vanish/unvanish works
- [ ] Action bar / scoreboard / bossbar function
- [ ] Log shows: `"Scoreboard team setup not supported on this platform. Nametag features disabled."` (expected, not a bug)
- [ ] No `IllegalStateException` or scheduler crash under normal use
- [ ] AFK task, timed vanish expiry, sync task — all schedule without errors

---

## 46. Spigot / Bukkit / Purpur Compatibility

- [ ] Plugin enables and loads cleanly on Spigot (:25568)
- [ ] Plugin enables and loads cleanly on Purpur (:25566)
- [ ] Basic vanish/unvanish functional on each
- [ ] No platform-specific crashes

---

## 47. Storage Backends

**Default YAML:**
- [ ] Vanish state, rules, levels all persist across reload and restart

**MySQL (if available):**
- [ ] `storage.type: MYSQL`, configure credentials, reload → migration runs, data persists
- [ ] Cross-server: two Paper servers pointing at same MySQL → vanish on one, relog on other → still vanished

**PostgreSQL (Docker `vpp_postgres`):**
- [ ] `storage.type: POSTGRESQL` → loads cleanly, operations work
- [ ] Cross-server sync via shared DB

---

## 48. Update Checker

- [ ] `update-checker.enabled: true` → on startup, checks Modrinth and logs result
- [ ] Running latest → "Vanish++ is up to date" in console
- [ ] `show-beta: false` → beta release notifications suppressed
- [ ] `update-checker.enabled: false` → no check, no log line

---

## 49. Vanish Reload (/vanishreload)

- [ ] `/vanishreload` → config, messages, rules reloaded; "reloaded successfully" message
- [ ] Config changes (e.g. `staff-sounds.enabled: true`) take effect without restart
- [ ] Storage reinitializes if `storage.type` is changed
- [ ] Vanished player states preserved across reload
- [ ] New AFK task / timed vanish task restarts correctly after reload (no duplicate tasks)

---

## 50. Edge Cases & Stress

- [ ] Vanish then immediately die → on respawn, still vanished; effects reapplied
- [ ] Vanish then change world → still vanished in new world; per-world rules applied
- [ ] Two admins simultaneously `/vanish` the same player → no duplicate entries
- [ ] `/vanishreload` while timed vanish is counting down → timer continues (or is rescheduled correctly)
- [ ] Very long player name (16 chars) in partial vanish whitelist → no overflow
- [ ] Console runs `/vanish` with no player arg → "Console must specify a player."

---

## Notes

Record failures here during testing:

```
[ ] FAIL — Feature: ___________
    Repro: ___________
    Actual: ___________
    Expected: ___________
```
