# Vanish++ State Inventory

Everything the plugin must track, save, or restore — organized by scope and storage layer.

---

## 1. Per-Player Persistent State

State that belongs to a player and must survive restarts and server switches.

### 1.1 Vanish Status
| Field | Type | Storage |
|-------|------|---------|
| Is vanished | `boolean` | DB (`vpp_vanished`) / YAML (`vanished-players`) |
| Vanish level | `int` (default: 1) | DB (`vpp_levels`) / YAML (`levels.<uuid>`) |

### 1.2 Rules (12 per player)
Stored as per-player overrides; fallback chain: player override → config default → hardcoded default.

| Rule Key | Default | Description |
|----------|---------|-------------|
| `can_break_blocks` | `false` | Allow block breaking while vanished |
| `can_place_blocks` | `false` | Allow block placing while vanished |
| `can_hit_entities` | `false` | Allow hitting entities while vanished |
| `can_pickup_items` | `false` | Allow picking up items while vanished |
| `can_drop_items` | `false` | Allow dropping items while vanished |
| `can_trigger_physical` | `false` | Allow triggering pressure plates etc. |
| `can_interact` | `true` | Allow interacting with blocks/entities |
| `can_throw` | `false` | Allow throwing projectiles |
| `can_chat` | `false` | Allow chatting without confirmation |
| `mob_targeting` | `false` | Allow mobs to target the player |
| `show_notifications` | `true` | Show rule-violation action bar tips |
| `spectator_gamemode` | `true` | Switch to spectator on vanish |

Storage: DB (`vpp_rules`) / YAML (`rules.<uuid>.<rule>`)

### 1.3 Custom Permissions
Per-player permission overrides (alternative to a permissions plugin).

| Field | Type | Storage |
|-------|------|---------|
| Permission list | `List<String>` | `permissions.yml` (`permissions.<uuid>`) |

Supported permissions: `vanishpp.*`, `vanishpp.vanish`, `vanishpp.vanish.others`, `vanishpp.see`, `vanishpp.manageperms`, `vanishpp.rules`, `vanishpp.rules.others`, `vanishpp.silentchest`, `vanishpp.chat`, `vanishpp.notarget`, `vanishpp.nohunger`, `vanishpp.nightvision`, `vanishpp.fly`, `vanishpp.no-raid`, `vanishpp.no-sculk`, `vanishpp.no-trample`, `vanishpp.join-vanished`, `vanishpp.pickup`, `vanishpp.pickup.others`, `vanishpp.update`, `vanishpp.ignorewarning`, and layered variants `vanishpp.vanish.level.<N>` / `vanishpp.see.level.<N>`.

Permission group shorthands: `vanishpp.abilities`, `vanishpp.core`, `vanishpp.management`.

### 1.4 Notifications / Acknowledgements
| Field | Type | Storage |
|-------|------|---------|
| Dismissed notification IDs | `Set<String>` | DB (`vpp_acknowledgements`) / YAML (`acknowledged-notifications.<uuid>`) |

Known notification ID patterns stored per-player:
- `hiding_v{VERSION}` — player acknowledged the plugin-hiding feature notice for a given config version
- `migration_v{VERSION}` — player acknowledged the config migration notice for a given config version

---

## 2. Per-Player Runtime State (In-Memory Only)

Lives only for the duration of a session. Lost on restart/disconnect. Not persisted.

| Field | Type | Description |
|-------|------|-------------|
| Is vanished (cache) | `Set<UUID>` | In-memory mirror of persisted vanish set |
| Pre-fetched vanish state | `Map<UUID, Boolean>` | DB result fetched async before join, consumed on `PlayerJoinEvent` |
| Pending chat message | `Map<UUID, String>` | Message awaiting `/chatconfirm` from vanished player |
| Action bar suppressed until | `Map<UUID, Long>` | Timestamp until action bar is hidden (e.g. after rule block) |
| Action bar warning component | `Map<UUID, Component>` | Current warning text shown in action bar |
| Saved gamemode (silent chest) | `Map<UUID, GameMode>` | Gamemode before switching to spectator for silent chest view |
| Silent chest block coords | `Map<UUID, String>` | Coords (`x,y,z`) of the block being silently opened |
| Rule notification cooldowns | `Map<UUID, Map<String, Long>>` | Per-rule cooldown to throttle repeated tip messages |
| Has seen "disable tips" hint | `Set<UUID>` | Whether the player has seen the notification-disable tip |
| Last sneak timestamp | `Map<UUID, Long>` | Used to detect double-tap sneak (spectator toggle) |
| Scoreboard object | `Map<UUID, Scoreboard>` | Active vanish scoreboard instance |
| Scoreboard manually hidden | `Set<UUID>` | Players who toggled the scoreboard off manually |
| ProtocolLib warning dismissed | `Set<UUID>` | Players who acknowledged the missing ProtocolLib warning |
| Scoreboard movement cooldown | `Map<UUID, Long>` | Throttle timestamp for movement-triggered scoreboard updates |

---

## 3. Global Persistent State

Server-wide settings that survive restarts. Stored in config files.

### 3.1 Feature Flags (`config.yml`)

**Vanish Effects**
| Setting | Type | Default |
|---------|------|---------|
| Hide real quit message | `boolean` | `true` |
| Hide real join message | `boolean` | `true` |
| Broadcast fake quit message | `boolean` | `true` |
| Broadcast fake join message | `boolean` | `true` |
| Simulate EssentialsX-style messages | `boolean` | `false` |
| Hide death messages | `boolean` | `true` |
| Hide advancements | `boolean` | `true` |
| Hide from tab-complete | `boolean` | `true` |
| Adjust server list player count | `boolean` | `true` |
| Hide from server list | `boolean` | `true` |
| Hide from plugin list | `boolean` | `true` |
| Staff glow effect enabled | `boolean` | `true` |
| Staff notify on vanish/unvanish | `boolean` | `true` |

**Gameplay Restrictions**
| Setting | Type | Default |
|---------|------|---------|
| Disable mob targeting | `boolean` | `true` |
| Disable hunger loss | `boolean` | `true` |
| Enable night vision | `boolean` | `true` |
| Enable flight on vanish | `boolean` | `true` |
| Disable fly on unvanish | `boolean` | `true` |
| God mode | `boolean` | `true` |
| Prevent potions | `boolean` | `false` |
| Silent chests | `boolean` | `true` |
| Ignore projectiles | `boolean` | `true` |
| Prevent raid trigger | `boolean` | `true` |
| Prevent sculk sensor trigger | `boolean` | `true` |
| Prevent crop/turtle egg trampling | `boolean` | `true` |
| Prevent sleeping | `boolean` | `true` |
| Prevent entity interact | `boolean` | `true` |
| Prevent accidental chat | `boolean` | `true` |
| Disable block physical triggering | `boolean` | `true` |

**Spectator Mode**
| Setting | Type | Default |
|---------|------|---------|
| Spectator gamemode swap enabled | `boolean` | `true` |

### 3.2 Appearance (`config.yml`)
| Setting | Type | Default |
|---------|------|---------|
| Tab list prefix for vanished | `string` | `"&7[VANISHED] "` |
| Nametag prefix for vanished | `string` | `"&7[V] "` |
| Action bar text | `string` | `"&bYou are currently VANISHED"` |
| Action bar enabled | `boolean` | `true` |
| Vanished player chat format | `string` | `"%prefix%&7%player%: %message%"` |

### 3.3 Layered Vanish Permissions (`config.yml`)
| Setting | Type | Default |
|---------|------|---------|
| Layered permissions enabled | `boolean` | `true` |
| Default vanish level | `int` | `1` |
| Default see level | `int` | `1` |
| Max level | `int` | `100` |

### 3.4 Default Rules (`config.yml`)
Global defaults for all 12 rules (see §1.2). Applied when no per-player override exists.

### 3.5 Update Checker (`config.yml`)
| Setting | Type | Default |
|---------|------|---------|
| Update checker enabled | `boolean` | `true` |
| Update checker mode | `string` | `"PERMISSION"` |
| Modrinth project ID | `string` | `"vanishpp"` |
| Notify list (UUIDs or names) | `List<String>` | `[]` |

### 3.6 Schema Version (`config.yml`)
| Setting | Type | Description |
|---------|------|-------------|
| `config-version` | `int` | Internal config schema version; used to trigger and track migrations (currently `8`) |

### 3.7 Language (`config.yml`)
| Setting | Type | Default |
|---------|------|---------|
| Language code | `string` | `"en"` |

### 3.7 Storage Backend (`config.yml`)
| Setting | Type |
|---------|------|
| Storage type | `enum: YAML \| MYSQL \| POSTGRESQL` |
| MySQL/PostgreSQL host | `string` |
| MySQL/PostgreSQL port | `int` |
| MySQL/PostgreSQL database | `string` |
| MySQL/PostgreSQL username | `string` |
| MySQL/PostgreSQL password | `string` |
| MySQL/PostgreSQL use-ssl | `boolean` |
| MySQL/PostgreSQL pool size | `int` |

### 3.8 Redis / Cross-Server Sync (`config.yml`)
| Setting | Type |
|---------|------|
| Redis enabled | `boolean` |
| Redis host | `string` |
| Redis port | `int` |
| Redis password | `string` |

### 3.9 Voice Chat Integration (`config.yml`)
| Setting | Type | Default |
|---------|------|---------|
| Simple Voice Chat hook enabled | `boolean` | `true` |
| Isolate vanished players in voice | `boolean` | `true` |

### 3.10 Scoreboard (`scoreboards.yml`)
| Setting | Type | Default |
|---------|------|---------|
| Scoreboard enabled | `boolean` | `true` |
| Auto-show on vanish | `boolean` | `true` |
| Title | `string` | — |
| Lines (up to 16) | `List<String>` | — |
| Update interval | `int` (ticks) | `1` |
| Movement-triggered update | `boolean` | `true` |
| Movement update cooldown | `int` (ms) | `100` |
| Timezone override | `string` | `"default"` |

---

## 4. Cross-Server Sync State

Shared state across a proxy network via Redis pub/sub and a shared SQL database.

| Channel / Table | Payload | Purpose |
|----------------|---------|---------|
| Redis `vanishpp:sync` | `VANISH:<uuid>` / `UNVANISH:<uuid>` | Broadcast vanish changes in real time |
| SQL `vpp_vanished` | `uuid`, `created_at` | Source of truth for vanish status |
| SQL `vpp_rules` | `uuid`, `rule_key`, `rule_value`, `updated_at` | Source of truth for per-player rules |
| SQL `vpp_levels` | `uuid`, `level`, `updated_at` | Source of truth for vanish levels |
| SQL `vpp_acknowledgements` | `uuid`, `notification_id` | Source of truth for dismissed notifications |
| SQL `vpp_schema_version` | `version` | Tracks DB migration version (currently v2) |

---

## 5. Localization State

All user-facing text. Stored in lang files; selected by the `language` config key.

**File:** `/plugins/Vanishpp/lang/messages.yml` (plus per-language variants)

Categories of strings tracked:
- General (vanish/unvanish confirm, no permission, player not found)
- Vanish-others feedback
- Chat lock / confirmation prompts
- Rule-violation notifications (one per rule)
- Staff notifications (vanish/unvanish events)
- Fake join/quit messages
- Permission management command feedback
- Config command feedback
- Scoreboard title and lines
- Update checker notifications
- Warning and alert messages

---

## 6. Plugin Startup State (Runtime Only)

Ephemeral state valid only during the current server session.

| Field | Type | Description |
|-------|------|-------------|
| Startup warnings | `List<Warning>` | Config validation issues detected at boot |
| ProtocolLib available | `boolean` | Whether ProtocolLib is loaded |
| Scoreboard task running | `boolean` | Whether the periodic scoreboard updater is active |
| Scoreboard task epoch | `int` | Counter for invalidating stale update tasks |
| Currently open silent blocks | `Set<String>` | Block coords currently suppressing open animations |

---

## Summary

| Category | Storage Layer | Scope |
|----------|--------------|-------|
| Vanish status | DB / YAML / Memory | Per-player |
| Vanish level | DB / YAML | Per-player |
| Rules (×12) | DB / YAML | Per-player |
| Custom permissions | `permissions.yml` | Per-player |
| Dismissed notifications | DB / YAML | Per-player |
| Session runtime state (chat, action bar, chest, etc.) | Memory only | Per-player |
| Feature flags (40+) | `config.yml` | Global |
| Appearance settings | `config.yml` | Global |
| Layered perm settings | `config.yml` | Global |
| Default rules | `config.yml` | Global |
| Storage + Redis config | `config.yml` | Global |
| Scoreboard layout | `scoreboards.yml` | Global |
| Localization strings | `lang/messages.yml` | Global |
| Startup diagnostics | Memory only | Global (ephemeral) |
| Cross-server sync | Redis pub/sub + SQL | Network-wide |
