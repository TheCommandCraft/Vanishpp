# Vanish++ — Claude Code Context

## Project
Minecraft server plugin. Java 21, Maven, targets **Paper 1.21.x**.
Current version: **1.1.6** (in active development — not yet stable release).
Distribution: [Modrinth](https://modrinth.com/plugin/vanish++) — current build is published as **beta**.

## Build
```bash
# Full build + tests
JAVA_HOME="C:/Users/Thies/.jdks/ms-21.0.8" \
  "C:/Program Files/JetBrains/IntelliJ IDEA Community Edition 2025.2.1/plugins/maven/lib/maven3/bin/mvn.cmd" \
  clean verify

# Package only (skip tests)
... mvn.cmd package -DskipTests -q
```
Output JAR: `target/vanishpp-1.1.6.jar`

## Test Environment (Docker)
Five Minecraft servers in `docker/`:
```
Paper   localhost:25565  RCON:25575
Purpur  localhost:25566  RCON:25576
Folia   localhost:25567  RCON:25577
Spigot  localhost:25568  RCON:25578
Bukkit  localhost:25569  RCON:25579
RCON password: vanishtest
```
```bash
cd docker
bash start.sh [paper|folia|...]   # start server(s), auto-copies JAR
bash update.sh                    # push new JAR + /vanishreload to all running servers
bash logs.sh folia                # tail logs
bash stop.sh                      # stop all
```
**Windows/Docker Desktop note:** The `PLUGINS` env var is set to `" "` for Folia to suppress ProtocolLib auto-download (AccessDeniedException in WSL2 volume). The `./plugins/vanishpp.jar` host bind-mount was replaced with `./plugins:/plugins:ro` so the image's init script copies it cleanly.

## Platform Support & Scheduler Architecture
All server types are supported via a **VanishScheduler abstraction**:
- `FoliaSchedulerBridge` — uses `Bukkit.getGlobalRegionScheduler()` / `Bukkit.getAsyncScheduler()`
- `BukkitSchedulerBridge` — uses `Bukkit.getScheduler()` (Paper/Spigot/Bukkit/Purpur)

**CRITICAL RULE:** Never call `Bukkit.getScheduler()` or `plugin.getServer().getScheduler()` directly anywhere outside of `BukkitSchedulerBridge.java`. Always use `plugin.getVanishScheduler().runLaterGlobal()` / `runGlobal()` / `runAsync()` / `runTimerGlobal()`. Breaking this crashes Folia.

**Folia-specific constraints:**
- `runTimerGlobal(runnable, delay, period)` — delay must be `>= 1` (Folia rejects `<= 0`)
- `Scoreboard.registerNewTeam()` throws `UnsupportedOperationException` — wrapped in try/catch in `setupTeams()`, gracefully degrades (nametag features disabled on Folia)
- Team setup is deferred to `vanishScheduler.runGlobal()` on Folia (not allowed on startup thread)

## Key Architecture
- `Vanishpp.java` — main plugin class, holds all state
- `vanishedPlayers` — `ConcurrentHashMap.newKeySet()` (thread-safe, critical for Folia multi-region)
- `VanishScheduler` interface → `FoliaSchedulerBridge` or `BukkitSchedulerBridge` (selected at startup via `Bukkit.getName()` check)
- `StorageProvider` interface → `YamlStorage` (default) or `SqlStorage` (MySQL/PostgreSQL)
- `RedisStorage` — optional cross-server vanish sync layer
- `RuleManager` — per-player VRules (can_break_blocks, mob_targeting, etc.)
- `PermissionManager` — handles custom per-player overrides stored in storage
- `IntegrationManager` — TAB, DiscordSRV, Dynmap, EssentialsX, SimpleVoiceChat, PAPI hooks
- `UpdateChecker` — async Modrinth API check, periodic re-check via `runTimerGlobal`

## Test Suite
194 tests, 1 skipped (pre-existing). All must pass before release.
Test classes: `CommandTest`, `EventListenerTest`, `FeatureTest`, `MigrationTest`,
`PermissionManagerTest`, `RuleManagerTest`, `LocalizationTest`,
`RedisStorageSyncTest`, `SqlStorageIntegrationTest`, `SqlStorageMigrationTest`, `StorageTest`

## Known Folia Limitations (intentional, documented)
- Scoreboard team (`Vanishpp_Vanished`) cannot be registered — nametag prefix and collision rules disabled
- Warning logged: `"Scoreboard team setup not supported on this platform. Nametag features disabled."`

## Coding Conventions
- No raw `Bukkit.getScheduler()` calls outside `BukkitSchedulerBridge`
- Use `resyncVanishEffects(player)` to reapply state on already-vanished players, not `applyVanishEffects()` (the latter adds to the vanished set and is for initial vanish)
- `applyVanishEffects()` = first-time vanish (adds to set, full setup)
- `resyncVanishEffects()` = re-apply to already-vanished player (respawn, world change, rule change)
- All shared mutable collections must be thread-safe (`ConcurrentHashMap`, `ConcurrentHashMap.newKeySet()`)

## Owner / Git
- Git user: TCC
- Branch: master
- Remote: push to origin/master for releases
