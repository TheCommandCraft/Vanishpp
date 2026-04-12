# Vanish++ Feature Wishlist

> Features that do not yet exist in the plugin, organized by category.  
> Existing features were audited before writing this list — nothing here is a duplicate.

---

## GUI & Usability

### Interactive Rules GUI
- Clickable inventory UI for `/vrules` instead of typing commands
- Each rule is a toggleable item (green = enabled, red = disabled)
- Supports temporary rule timers set via an anvil rename prompt
- Works for self and for target players (`/vrules <player>` opens their GUI)

### Admin Dashboard GUI
- `/vadmin` opens a full overview screen
- One slot per vanished player showing head, name, vanish level, duration, and active rules
- Click a player's head to open their rule GUI or unvanish them
- Shows total vanished count, server TPS, and online staff at a glance

### Vanish Toggle Item (Wand)
- Configurable hotbar item (material, display name, lore) given to staff on join
- Right-click toggles vanish; shift-right-click opens the rule GUI
- Item is hidden from other players (not dropped, not visible in hand)
- Command: `/vwand [give|take] [player]`

### Rule Presets
- Save and load named rule configurations
- `/vrules save <name>` — saves current rule state as a named profile
- `/vrules load <name>` — applies a saved profile
- `/vrules list` — shows all saved profiles
- Profiles stored per-player in the storage backend
- Ship with built-in presets: `stealth` (all blocks off), `spectator` (no interaction), `builder` (blocks enabled)

### Bossbar Status Indicator
- Alternative vanish status display shown as a bossbar at the top of the screen
- Configurable title, color, and style
- Can run alongside or replace the action bar indicator
- Auto-hides on unvanish
- Toggle via `/vsb bossbar`

---

## Quality of Life

### Timed Vanish
- `/vanish [player] <duration>` — auto-unvanishes after the given time (e.g. `30s`, `5m`, `2h`)
- Remaining time shown in the scoreboard and action bar
- `/vanish cancel [player]` cancels a scheduled unvanish
- Staff with `vanishpp.see` see a timer badge in `/vlist`

### Auto-Vanish on Join (Per-Player Toggle)
- Distinct from the existing `join-vanished` permission — this is a saved preference
- `/vautovanish` toggles the setting for yourself
- `/vautovanish <player> <on|off>` sets it for another player
- Persisted in storage; survives restarts and server switches

### Auto-Vanish on AFK
- Configurable idle threshold (seconds) after which the player is silently vanished
- Hooks into EssentialsX AFK events when available; falls back to a built-in movement tracker
- Sends a private action bar message to the player when auto-vanished
- Auto-unvanishes on first movement/chat after returning

### Anti-Combat Vanish
- Configurable cooldown (seconds) that blocks `/vanish` after dealing or receiving damage
- Separate cooldowns for PvP and PvE
- Bypass permission: `vanishpp.combat.bypass`
- Warns the player with remaining cooldown time when they try to vanish too early

### Vanish History / Audit Log
- Persistent log of all vanish/unvanish events: player, timestamp, server, reason, duration
- Stored in the configured storage backend (YAML file, MySQL, or PostgreSQL table)
- `/vhistory [player] [page]` — paginated in-game viewer with clickable entries
- Optional flat-file export alongside database storage
- Configurable retention period (days); old entries pruned automatically

### Vanish Reason
- `/vanish [reason...]` attaches a free-text reason to the vanish session
- Reason shown in `/vlist`, the admin dashboard, and the audit log
- Staff with `vanishpp.see` see the reason in a hover tooltip on the player's name
- Stored alongside the vanish state in the storage backend
- `/vanish reason <text>` updates the reason mid-session

---

## Staff Workflow

### Quick Spectate Command
- `/vspec <player>` — teleports you to the target and puts you in spectator mode while vanished
- Returns you to your previous location and gamemode on `/vspec stop` or on unvanish
- Requires `vanishpp.spectator` and `vanishpp.vanish`
- Works cross-server via the proxy bridge (switches server then teleports)

### Player Follow / Track Mode
- While in spectator mode, `/vfollow <player>` locks the camera to that player
- Camera auto-follows through teleports, world changes, and server switches (via proxy)
- Overlay shows the target's coordinates, health, and gamemode in the action bar
- `/vfollow stop` or unvanishing releases the lock

### Bulk Vanish
- `/vanish all` — vanishes all online players who have `vanishpp.vanish`
- `/vanish group <permission>` — vanishes all players with a given permission node
- `/vanish world <world>` — vanishes all players in a specific world
- Unvanish equivalents for each
- Requires `vanishpp.vanish.bulk` permission

### Partial Visibility
- Be visible to a specific player or list of players, hidden from everyone else
- `/vanish --visible <player>[,<player>...]` — enter partial-vanish mode
- Shown as "semi-vanished" in `/vlist` with a distinct indicator
- `/vanish --visible none` collapses back to full vanish
- Useful for covert co-op observation or trusted player interactions

### Incognito Mode
- Remain fully visible and interactable but with a configurable fake display name
- `/vincognito [fakename]` — applies the fake name (defaults to a random one from a configurable list)
- Hides real name from chat, tab list, nametag, and death messages
- Vanish++ still tracks the real player; staff with `vanishpp.see` see both names
- `/vincognito off` restores the real display name
- Requires `vanishpp.incognito` permission

---

## Notifications & Transparency

### Differentiated Join/Quit Sounds and Messages
- Separate config keys for "joined silently" vs "left silently" sounds and staff messages
- Configurable per-event: sound type, volume, pitch, and message format
- Lets staff immediately distinguish new arrivals from departures without reading carefully

### Sound Notifications for Staff
- Play a configurable sound to all staff with `vanishpp.see` when any player vanishes or unvanishes
- Sound, volume, and pitch configurable per event type (vanish / unvanish / auto-vanish / timed-expire)
- Can be disabled per-player with `/vrules show_sounds false`

### Webhook Support
- Send vanish/unvanish events to one or more HTTP endpoints (Slack, custom HTTPS webhook, etc.)
- Configurable payload template (JSON) with placeholders: `{player}`, `{action}`, `{reason}`, `{server}`, `{timestamp}`
- Supports POST with optional Authorization header
- Retry on failure with exponential backoff
- Separate from DiscordSRV integration

### LuckPerms Context Integration
- Register `vanishpp:vanished=true/false` as a LuckPerms context
- Allows permissions to be conditionally granted only while vanished (e.g., auto-grant spectator perms, restrict commands)
- Automatically updated on vanish/unvanish
- Requires `vanishpp.luckperms` in plugin.yml soft-dependency

---

## Anti-Abuse & Region Support

### WorldGuard / RegionAPI Integration
- `deny-vanish` region flag — prevents any player from vanishing inside the region
- `force-vanish` region flag — automatically vanishes players who enter the region
- `deny-unvanish` region flag — prevents unvanishing inside the region (e.g. staff-only observation booths)
- Flags registered via WorldGuard's flag registry; no WorldGuard core changes needed

### No-Vanish Zone Beacons (No WorldGuard Required)
- Place a configurable block type as a "beacon" to create a no-vanish radius without WorldGuard
- Radius, block type, and particle effect configurable in config.yml
- Beacons stored in the storage backend; survive restarts
- `/vzone add <radius>` while looking at a block registers it
- `/vzone remove` while looking at a registered block removes it
- Requires `vanishpp.zone` permission

### Vanish Toggle Rate Limit
- Prevent rapidly toggling vanish to spam fake join/quit messages
- Configurable cooldown (seconds) between toggles
- Bypass permission: `vanishpp.ratelimit.bypass`
- Warning message shown when rate-limited; cooldown shown in action bar

---

## Cross-Server & Proxy

### Global Vanish Broadcast Channel
- Notify admins on *any* connected server when a staff member vanishes or unvanishes
- Delivered via the existing proxy plugin-messaging channel
- Configurable: opt-in per-server, minimum vanish level to broadcast, message format
- Distinct from per-server staff notifications already in place

### Proxy-Side `/vlist`
- Serve `/vlist` directly from the Velocity plugin when the player is on the proxy (e.g. lobby)
- Works even when no backend servers are online
- Shows server name alongside each vanished player's entry

### Proxy Config GUI
- Visual in-game editor for the `velocity-config.yml` (or equivalent proxy config)
- Each setting is a clickable/editable item in an inventory screen
- Saves and syncs to all connected backend servers immediately via the existing `CONFIG_PUSH` message

---

## API & Developer Ecosystem

### Public Java API
- Dedicated `VanishAPI` class exposed via a service or static accessor
- Methods: `isVanished(UUID)`, `vanish(Player)`, `unvanish(Player)`, `getVanishLevel(UUID)`, `getRules(UUID)`
- Cancellable `VanishEvent` and `UnvanishEvent` fired before state changes
- `VanishStateChangeEvent` fired after state changes (for integrations that only need to react)
- Full Javadoc; packaged in a separate `-api` artifact for shading

### Additional PlaceholderAPI Placeholders
- `%vanishpp_vanish_duration%` — formatted time the player has been vanished
- `%vanishpp_vanish_reason%` — current vanish reason or empty string
- `%vanishpp_vanish_level_<player>%` — vanish level of a named player
- `%vanishpp_is_vanished_<player>%` — true/false for a named player
- `%vanishpp_rule_<rule>%` — current value of a rule for the requesting player
- `%vanishpp_can_see_<player>%` — whether the requesting player can see the named player

### Geyser / Floodgate Form UI
- Bedrock players (connected via Geyser + Floodgate) get a native form-based rules editor
- Opening `/vrules` triggers a SimpleForm with toggles for each rule
- Vanish toggle item triggers a ModalForm ("Vanish on/off?") instead of silently toggling
- Falls back gracefully when Geyser is not present

---

## Configuration & Maintenance

### Config Export / Import
- `/vconfig export [filename]` — dumps the full config to a portable JSON file in the plugin folder
- `/vconfig import <filename>` — loads and validates a JSON config, applies it live
- Useful for migrating settings between servers or keeping versioned backups
- Exported files include a schema version field for forward-compatibility checks

### Per-World Rule Defaults
- In `config.yml`, define rule overrides per world name under a `world-rules:` block
- Players entering a world automatically have those rules applied (on top of their personal overrides)
- Exiting the world restores the previous rule set
- Useful for: allowing block-breaking in a staff world, disabling interaction in a PvP world

### In-Game Changelog Viewer
- `/vchangelog [version]` shows a formatted changelog panel in chat using MiniMessage
- Tied to the update checker: highlights what's new in the available update
- Most-recent entry shown automatically on first join after an update (once per version, dismissible)
- Full history browsable by version number

### Vanish Statistics Dashboard
- Optional local stats tracked per player: total vanish time, vanish count, longest session, most-used rules
- `/vstats [player]` shows a formatted summary in chat or opens a GUI
- Aggregate server stats: peak concurrent vanished count, busiest vanish hour
- Stats stored in the configured storage backend; opt-out via `vanishpp.stats.bypass`
