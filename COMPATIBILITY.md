# Compatibility Matrix - Vanishpp

## Supported Versions
Vanishpp is built and tested against the **1.21** API, and supports the **1.20.6 — 26.2** version range.

| Minecraft Version | Status | Notes |
| :--- | :--- | :--- |
| **26.x** | ✅ Supported | 2026 year-based versioning. Tested on 26.2. |
| **1.21.x** | ✅ Supported | Native support. Recommended. |
| **1.20.6** | ✅ Supported | Minimum supported version. 1.21+ features (like new items) won't work. |
| **1.20.5 and older** | ❌ Unsupported | API changes in 1.20.5+ (ItemStacks/NMS) make this version incompatible. Use Vanishpp 1.0.x. |

## Supported Platforms
| Platform | Status | Notes |
| :--- | :--- | :--- |
| **Paper** | ✅ Recommended | Best performance. Required for full API support (Projectile events). |
| **Spigot** | ⚠️ Compatible | Works, but some advanced physics features (arrow passthrough) may degrade. |
| **Purpur** | ✅ Supported | Fully compatible (Fork of Paper). |
| **Folia** | ❓ Untested | Threading model *may* cause issues with current schedulers. Not officially supported yet. |

## Java Requirement
- **Java 21** is required for this version.

## Velocity Companion
Upgrade the Paper and Velocity Vanish++ jars together. Native proxy messaging is disabled by default; enable `proxy.enabled` and restart Paper only on secured backends with the companion installed. Use Velocity modern forwarding and prevent direct client connections. Standalone servers need no proxy configuration. See the [proxy integration guide](PROXY_INTEGRATION_GUIDE.md).

## Visibility integrations

Visibility levels apply to entity and tab packets, command suggestions, lists, placeholders, staff notices, and command targets. Console access remains administrative. Anonymous server-list counts include only online, non-vanished players, on Paper and Velocity.

Without ProtocolLib, Vanish++ keeps hidden names off the shared scoreboard and gives authorized viewers private teams. Original scoreboards remain untouched. Their objectives, scores, teams and memberships are reconciled into private overlays, and restored on unvanish, quit or shutdown. Read-only objectives such as health use writable display mirrors that retain their appearance and follow the original values. Discovery is shared across viewers of the same board; each synchronization pass advances at most 256 cursor steps and acquires at most one Bukkit snapshot. Bukkit returns whole sets, so that snapshot call itself cannot be pre-empted. Large boards require several passes before all unrelated scoreboard updates appear. ProtocolLib is still required to filter packets produced by other plugins and to suppress live container animations, sounds, and barrel block updates.

EssentialsX private messages, including replies, nicknames and wildcard recipients, are checked after EssentialsX resolves their recipients. Vanilla selectors use Bukkit's selector resolution. Other messaging plugins use observed conversations. An unresolved reply is allowed; a messaging plugin with private recipient state may therefore still reveal a hidden recipient. Use its own vanish protection when no recipient integration exists.

Incognito changes display and tab text, including TAB's name override; it does not replace the authenticated profile, UUID or skin. Simple Voice Chat isolation filters outgoing sound events instead of changing the disabled icon.

Voice callbacks use immutable audience facts published on player-owned server tasks. Built-in permission changes and LuckPerms recalculation invalidate facts immediately; unknown facts deny hidden-player audio until refreshed. Other permission providers are sampled by the regular visibility synchronization task.

State synchronization includes proxy session/revision fields and explicit visible-player decisions. Upgrade both jars together. Back up Velocity `state.yml` alongside `config.yml`; it preserves decisions when SQL writes are still queued or the proxy uses YAML. Older peers do not provide the revision and bootstrap guarantees.
