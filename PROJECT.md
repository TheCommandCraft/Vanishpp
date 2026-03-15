# Vanish++ — Project Vision & Goals

## What is Vanish++?
Vanish++ is a premium-quality, open-source Minecraft vanish plugin for Paper/Spigot 1.21+ (with Folia support). It aims to be the **most undetectable and feature-complete vanish solution** available — not just hiding players, but making their presence completely invisible at every level: packets, events, commands, tab completion, server list, and third-party integrations.

## Core Philosophy

### 1. Total Anti-Detection
The plugin must leave **zero trace** of a vanished player's existence. This means:
- Packet-level hiding via ProtocolLib (metadata, movement, spawn, scoreboard teams, tab completion, sound effects, block animations)
- No vanilla join/quit messages leak — replaced with configurable fake messages
- Hidden from `/plugins`, server list player count, tab completion, and command suggestions
- Even the plugin itself should be hideable from `/plugins` for non-staff

### 2. User-Friendly Feedback
Every blocked action must **explain itself**. When something is blocked:
- Show an action bar warning immediately
- Send a chat notification with **clickable buttons** to fix it (`[Allow 1m]`, `[Allow permanently]`, `[Hide notifications]`)
- For config-level blocks, offer a `[Disable in config]` button
- Never leave the player confused about why something didn't work

### 3. Everything Configurable
Every single user-facing string in the plugin must be configurable through `messages.yml` with full MiniMessage + legacy color code support. Config values should be editable in-game via `/vconfig` with live reload. Sensitive settings (storage, permissions) require `--confirm`.

### 4. Smart Defaults, Zero Setup
The plugin should work perfectly out of the box with sensible defaults. All features enabled, all protections active. Server admins should only need to configure if they want to customize — never because something is broken by default.

## Feature Set

### Vanish Mechanics
- Full invisibility with ProtocolLib packet interception
- Scoreboard team-based nametag prefix for staff (`[V]` above head)
- Tab list prefix for staff (`[VANISHED]`)
- God mode, no hunger, night vision, flight (all toggleable)
- Silent chest/container opening (suppressed animations + sounds)
- Projectiles pass through vanished players
- Block spawn egg usage, prevent bed sleeping, prevent entity mounting
- Prevent raid triggers, sculk sensor activation, crop trampling

### Rule System (`/vrules`)
Personal per-player toggles that override global config:
- `can_break_blocks`, `can_place_blocks`, `can_hit_entities`, `can_pickup_items`, `can_drop_items`
- `can_trigger_physical`, `can_interact`, `can_chat`, `mob_targeting`, `show_notifications`
- Support for **temporary rules** with auto-revert (`/vrules can_break_blocks true 60`)
- Bulk set: `/vrules all true`, `/vrules none`

### Integrations / Hooks
- **ProtocolLib** — packet-level hiding (critical for full stealth)
- **PlaceholderAPI** — `%vanishpp_vanished%`, `%vanishpp_count%`, etc.
- **EssentialsX** — simulate join/leave messages through Essentials format
- **DiscordSRV** — fake join/quit forwarded to Discord
- **TAB (NEZNAMY)** — hide vanished from TAB's formatting
- **Simple Voice Chat** — isolate vanished players from voice
- **Dynmap** — hide vanished from the web map

### Storage & Sync
- **YAML** (default, zero config)
- **MySQL / PostgreSQL** (for networks)
- **Redis** (cross-server vanish state sync)
- Acknowledgement system for persistent dismissal of notifications

### Permission System
- Layered vanish/see levels (level 1-100) — higher level = can see lower levels but not vice versa
- Per-player permission overrides via `/vperms`
- Fine-grained permissions for every feature (`vanishpp.fly`, `vanishpp.silentchest`, `vanishpp.notarget`, etc.)

## Quality Standards
- **No hardcoded strings** — everything through LanguageManager with fallback defaults
- **Thread safety** — ConcurrentHashMap for async contexts (chat events, ProtocolLib packet threads)
- **Graceful degradation** — works without ProtocolLib (with warnings), works without any optional hook
- **Startup diagnostics** — warn admins about misconfigurations with clickable fix buttons
- **Update checker** — Modrinth-based, configurable notification mode

## Target Audience
Server administrators who need reliable, professional-grade staff vanish functionality — from small SMPs to large networks. The plugin should feel polished enough to be a paid resource while remaining open source.

## Distribution
- Published on **Modrinth** as the primary platform
- Source on **GitHub**
- Supports Paper 1.21+ and Folia
