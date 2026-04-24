# Vanish++ Feature Wishlist

> Checkboxes reflect implementation status as of 1.1.8.  
> Existing features were audited before writing this list — nothing here is a duplicate.

---

## GUI & Usability

- [x] **Interactive Rules GUI** — clickable inventory UI for `/vrules`; each rule toggleable (green/red); supports temporary timers via anvil; works for self and target
- [x] **Admin Dashboard GUI** — `/vadmin` overview: vanished players, heads, rules, duration; click to open rule GUI or unvanish; TPS + online staff
- [x] **Vanish Toggle Item (Wand)** — configurable hotbar item; right-click toggles vanish; shift-right-click opens rules GUI; `/vwand [give|take] [player]`
- [x] **Rule Presets** — `/vrules preset save/load/list/delete`; stored per-player; built-in presets: stealth, spectator, builder
- [x] **Bossbar Status Indicator** — configurable bossbar title/color/style; runs alongside or replaces action bar; toggle via `/vsb bossbar`

---

## Quality of Life

- [x] **Timed Vanish** — `/vanish [player] <duration>` auto-unvanishes after e.g. `30s`, `5m`, `2h`; remaining time in action bar; `/vanish cancel [player]`
- [x] **Auto-Vanish on Join (Per-Player Toggle)** — `/vautovanish` + `/vautovanish <player> <on|off>`; persisted in storage
- [x] **Auto-Vanish on AFK** — configurable idle threshold; built-in movement tracker; private action bar notice on vanish; auto-unvanishes on first movement
- [x] **Anti-Combat Vanish** — cooldown blocks `/vanish` after dealing/receiving damage; separate PvP/PvE cooldowns; `vanishpp.combat.bypass` perm; warns player with remaining time
- [x] **Vanish History / Audit Log** — persistent log: player, timestamp, server, reason, duration; `/vhistory [player] [page]`; configurable retention; flat-file export option
- [x] **Vanish Reason** — `/vanish [reason...]`; shown in `/vlist`, admin dashboard, audit log; hover tooltip for `vanishpp.see` holders; `/vanish reason <text>` mid-session update

---

## Staff Workflow

- [x] **Quick Spectate Command** — `/vspec <player>`; saves previous location + gamemode; restores on `/vspec stop` or unvanish; `vanishpp.spectator` perm
- [x] **Player Follow / Track Mode** — `/vfollow <player>` locks camera while in spectator; follows through teleports + world changes; action bar HUD; `/vfollow stop`
- [x] **Bulk Vanish** — `/vanish all`, `/vanish world <world>`, `/vanish group <perm>`; unvanish equivalents; `vanishpp.vanish.bulk` perm
- [x] **Partial Visibility** — `/vanish --visible <player>[,...]`; semi-vanish mode visible only to listed players; shown as ◑ in `/vlist`; `/vanish --visible none` collapses to full vanish
- [x] **Incognito Mode** — `/vincognito [fakename]`; hides real name from chat, tab, nametag, death messages; staff with `vanishpp.see` see both names; `vanishpp.incognito` perm

---

## Notifications & Transparency

- [x] **Differentiated Join/Quit Sounds and Messages** — separate config keys for "joined silently" vs "left silently" sounds for staff; configurable sound, volume, pitch per event
- [x] **Sound Notifications for Staff** — play configurable sound to all `vanishpp.see` holders on vanish/unvanish; separate sounds for silent join/quit
- [x] **Webhook Support** — POST to HTTP endpoints on vanish events; JSON template with placeholders; optional Authorization header; retry with backoff
- [x] **LuckPerms Context Integration** — `vanishpp:vanished=true/false` context; auto-updated on state change

---

## Anti-Abuse & Region Support

- [x] **WorldGuard / RegionAPI Integration** — `deny-vanish`, `force-vanish`, `deny-unvanish` region flags
- [x] **No-Vanish Zone Beacons** — configurable block beacon with no-vanish radius; `/vzone add/remove`; stored in backend
- [x] **Vanish Toggle Rate Limit** — configurable cooldown between toggles; `vanishpp.ratelimit.bypass` perm; warning message when blocked

---

## Cross-Server & Proxy

- [ ] **Global Vanish Broadcast Channel** — notify admins on all connected servers on vanish/unvanish via proxy plugin-messaging; configurable opt-in, min vanish level, message format
- [ ] **Proxy-Side `/vlist`** — serve `/vlist` from the Velocity plugin; shows server name alongside each entry; works even when backend servers are offline
- [ ] **Proxy Config GUI** — in-game visual editor for velocity-config.yml; saves + pushes to all backends via `CONFIG_PUSH` message

---

## API & Developer Ecosystem

- [x] **Public Java API** — `VanishAPI`: `isVanished`, `vanish`, `unvanish`, `getVanishLevel`, `getRules`; cancellable `VanishEvent` / `UnvanishEvent`; `VanishStateChangeEvent`
- [x] **Additional PlaceholderAPI Placeholders** — `%vanishpp_vanish_duration%`, `%vanishpp_vanish_reason%`, `%vanishpp_vanish_level_<player>%`, `%vanishpp_is_vanished_<player>%`, `%vanishpp_rule_<rule>%`, `%vanishpp_can_see_<player>%`
- [ ] **Geyser / Floodgate Form UI** — native Bedrock form for `/vrules` (SimpleForm toggles) and vanish toggle (ModalForm); graceful fallback when Geyser absent

---

## Configuration & Maintenance

- [x] **Config Export / Import** — `/vconfig export [filename]` dumps to JSON; `/vconfig import <filename>` loads + validates live; schema version field
- [x] **Per-World Rule Defaults** — `world-rules:` block in config.yml; applied on world entry, restored on exit; useful for staff worlds, PvP worlds
- [x] **In-Game Changelog Viewer** — `/vchangelog [version]`; highlights new update on first join post-update (once per version, dismissible)
- [x] **Vanish Statistics Dashboard** — `/vstats [player]`: total vanish time, count, longest session, most-used rules; aggregate stats; opt-out via `vanishpp.stats.bypass`
