package net.thecommandcraft.vanishpp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.thecommandcraft.vanishpp.commands.*;
import net.thecommandcraft.vanishpp.config.*;
import net.thecommandcraft.vanishpp.listeners.*;
import net.thecommandcraft.vanishpp.hooks.*;
import net.thecommandcraft.vanishpp.utils.*;
import net.thecommandcraft.vanishpp.storage.*;
import net.thecommandcraft.vanishpp.zone.VanishZoneManager;
import net.thecommandcraft.vanishpp.scoreboard.VanishScoreboard;
import net.thecommandcraft.vanishpp.common.state.NetworkVanishState;
import net.thecommandcraft.vanishpp.proxy.ProxyBridge;
import net.thecommandcraft.vanishpp.proxy.ProxyConfigCache;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class Vanishpp extends JavaPlugin implements Listener {

    /** Rule-key prefix used to persist cross-server expiry notifications in the shared DB. */
    public static final String PENDING_NOTIFY_PREFIX = "__notify_expired__";

    private Set<UUID> vanishedPlayers;
    private Set<UUID> ignoredWarningPlayers; // concurrent — accessed from async join reconciliation

    private ConfigManager configManager;
    private StorageProvider storageProvider;
    private RedisStorage redisStorage;
    private PermissionManager permissionManager;
    private RuleManager ruleManager;
    private IntegrationManager integrationManager;
    private TabPluginHook tabPluginHook;
    private UpdateChecker updateChecker;
    private PluginHider pluginHider;
    private MessageManager messageManager;

    private Team vanishTeam;
    private VanishScheduler vanishScheduler;
    private VoiceChatHook voiceChatHook;
    private VanishScoreboard vanishScoreboard;
    private YamlConfiguration scoreboardConfig;

    public final Map<UUID, String> pendingChatMessages = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Long> actionBarPausedUntil = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Component> actionBarWarningComponent = new java.util.concurrent.ConcurrentHashMap<>();
    private boolean hasProtocolLib = false;
    private boolean hasPaperApi = false;
    private ProtocolLibManager protocolLibManager;
    private List<StartupChecker.Warning> startupWarnings = new ArrayList<>();
    /** Blocks currently being silently opened by a vanished player — suppress animation/sound packets for these.
     *  Key format: "x,y,z" */
    public final Set<String> silentlyOpenedBlocks = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    // ── Proxy support ─────────────────────────────────────────────────────────
    private ProxyBridge proxyBridge;
    private ProxyConfigCache proxyConfigCache;

    // ── New feature state ────────────────────────────────────────────────────
    /** Spectator follow: follower UUID → target UUID */
    public final Map<UUID, UUID> spectateFollowTargets = new ConcurrentHashMap<>();
    /** Spectator origin locations to teleport back to on stop */
    public final Map<UUID, Location> spectateOrigins = new ConcurrentHashMap<>();
    /** Gamemodes before entering spectator via /vspec or /vfollow */
    public final Map<UUID, GameMode> spectateOriginalGamemodes = new ConcurrentHashMap<>();
    /** Incognito mode: player UUID → fake name */
    public final Map<UUID, String> incognitoNames = new ConcurrentHashMap<>();

    private final Map<UUID, String> vanishReasons = new ConcurrentHashMap<>();
    public final Map<UUID, Long> vanishStartTimes = new ConcurrentHashMap<>();
    /** name (lowercase) → UUID cache populated on join; used for offline history lookups. */
    public final Map<String, UUID> playerNameCache = new ConcurrentHashMap<>();
    /** UUIDs of players currently viewing another player's inventory in read-only mode (no vanishpp.invsee.modify). */
    public final Set<UUID> invseeViewOnly = ConcurrentHashMap.newKeySet();
    /** Maps invsee viewer UUID → the target Player whose inventory is open. */
    public final Map<UUID, org.bukkit.entity.Player> invseeTargets = new ConcurrentHashMap<>();

    private VanishZoneManager vanishZoneManager;
    private LuckPermsHook luckPermsHook;
    private WebhookManager webhookManager;
    private WorldGuardHook worldGuardHook;
    private net.thecommandcraft.vanishpp.scoreboard.VanishBossbar vanishBossbar;
    private net.thecommandcraft.vanishpp.hooks.FloodgateHook floodgateHook;

    // ── Rate limit ───────────────────────────────────────────────────────────
    public final Map<UUID, Long> vanishToggleCooldowns = new ConcurrentHashMap<>();

    // ── Anti-combat vanish ───────────────────────────────────────────────────
    public final Map<UUID, Long> pvpCombatTimestamps = new ConcurrentHashMap<>();
    public final Map<UUID, Long> pveCombatTimestamps = new ConcurrentHashMap<>();

    // ── Timed vanish ─────────────────────────────────────────────────────────
    /** UUID → expiry epoch-ms. Absent means untimed. */
    public final Map<UUID, Long> timedVanishExpiry = new ConcurrentHashMap<>();

    // ── AFK auto-vanish ──────────────────────────────────────────────────────
    /** UUID → last-movement epoch-ms */
    public final Map<UUID, Long> lastMovementTime = new ConcurrentHashMap<>();
    /** Players that were auto-vanished by the AFK system */
    public final Set<UUID> afkVanishedPlayers = ConcurrentHashMap.newKeySet();

    // ── Partial visibility ───────────────────────────────────────────────────
    /** UUID of vanished player → set of UUIDs that can still see them */
    public final Map<UUID, Set<UUID>> partialVanishWhitelist = new ConcurrentHashMap<>();

    // ── World-rule override tracking ─────────────────────────────────────────
    /** UUID → map of rule→original-value that was saved before world-rule override */
    public final Map<UUID, Map<String, Boolean>> worldRuleOverrides = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        // 0. Folia Detection
        // Primary: server name — Paper 1.21+ added RegionScheduler to its API so class-presence
        // is no longer a reliable Folia indicator.
        boolean isFolia = "Folia".equalsIgnoreCase(Bukkit.getName());
        if (!isFolia) {
            // Fallback: ThreadedRegionizer is Folia's internal threading engine — not shipped by Paper.
            try {
                Class.forName("io.papermc.paper.threadedregions.ThreadedRegionizer");
                isFolia = true;
            } catch (ClassNotFoundException ignored) {
            }
        }

        if (isFolia) {
            this.vanishScheduler = new FoliaSchedulerBridge(this);
            getLogger().info("Folia environment detected. Using Regional Scheduler.");
        } else {
            this.vanishScheduler = new BukkitSchedulerBridge(this);
            getLogger().info("Standard Bukkit/Paper environment detected. Using Legacy Scheduler.");
        }

        // 0b. Platform & Version Compatibility Checks (console only)
        checkPlatformCompatibility(isFolia);

        // 1. Load Data/Config Managers
        this.configManager = new ConfigManager(this);
        configManager.load();

        this.messageManager = new MessageManager(this);

        initStorage();

        // Proxy bridge — detects VanishPP Velocity proxy via plugin messaging handshake.
        // Initialised here so it can start listening before players join.
        // Standalone mode (no proxy) is the fallback if no PONG arrives within 5 seconds.
        this.proxyConfigCache = new ProxyConfigCache(this);
        this.proxyBridge = new ProxyBridge(this);
        this.proxyBridge.init();

        this.permissionManager = new PermissionManager(this);
        permissionManager.load();

        this.ruleManager = new RuleManager(this);
        ruleManager.load();

        // 3. Load Hooks
        this.integrationManager = new IntegrationManager(this);
        this.integrationManager.load();

        this.tabPluginHook = new TabPluginHook(this);
        this.tabPluginHook.load();

        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") != null) {
            try {
                hookProtocolLib();
                this.hasProtocolLib = true;
                getLogger().info("ProtocolLib hooked successfully.");
            } catch (Throwable e) {
                getLogger().log(Level.WARNING, "ProtocolLib found but failed to hook", e);
                this.hasProtocolLib = false;
            }
        } else {
            this.hasProtocolLib = false;
            getLogger().warning("ProtocolLib NOT found! Advanced features (Tab scrubbing) disabled.");
        }

        // Run config sanity checks after all hooks are resolved
        this.startupWarnings = new StartupChecker(this).run();
        for (StartupChecker.Warning w : startupWarnings) {
            getLogger().warning("[Setup Check] " + w.message);
        }

        // Folia forbids scoreboard operations on the startup thread — defer to global region
        if (isFolia) {
            vanishScheduler.runGlobal(this::setupTeams);
        } else {
            setupTeams();
        }

        // ── Optional hooks ─────────────────────────────────────────────────
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") != null) {
            try {
                this.luckPermsHook = new LuckPermsHook(this);
                this.luckPermsHook.load();
                getLogger().info("LuckPerms context integration enabled.");
            } catch (Throwable e) {
                getLogger().warning("LuckPerms found but context hook failed: " + e.getMessage());
                this.luckPermsHook = null;
            }
        }
        if (Bukkit.getPluginManager().getPlugin("WorldGuard") != null) {
            try {
                this.worldGuardHook = new WorldGuardHook(this);
                this.worldGuardHook.load();
                getLogger().info("WorldGuard integration enabled.");
            } catch (Throwable e) {
                getLogger().warning("WorldGuard found but hook failed: " + e.getMessage());
                this.worldGuardHook = null;
            }
        }
        this.webhookManager = new WebhookManager(this);
        this.vanishZoneManager = new VanishZoneManager(this);
        if (Bukkit.getPluginManager().getPlugin("floodgate") != null) {
            try {
                this.floodgateHook = new net.thecommandcraft.vanishpp.hooks.FloodgateHook(this);
            } catch (Throwable e) {
                getLogger().warning("Floodgate found but hook failed: " + e.getMessage());
            }
        }
        this.vanishZoneManager.load();
        this.vanishBossbar = new net.thecommandcraft.vanishpp.scoreboard.VanishBossbar(this);

        // Init public API singleton
        net.thecommandcraft.vanishpp.api.VanishAPI.init(this);

        // 4. Register Commands
        registerCommand("vanish", new VanishCommand(this));
        registerCommand("vperms", new VpermsCommand(this));
        registerCommand("vanishrules", new VanishRulesCommand(this));
        registerCommand("vanishchat", new VanishChatCommand(this));
        registerCommand("vanishignore", new VanishIgnoreCommand(this));
        registerCommand("vanishlist", new VanishListCommand(this));
        registerCommand("vanishhelp", new VanishHelpCommand(this));
        registerCommand("vanishconfig", new VanishConfigCommand(this));
        registerCommand("vack", new VanishAckCommand(this));
        registerCommand("vanishreload", new VanishReloadCommand(this));
        registerCommand("vanishscoreboard", new VanishScoreboardCommand(this));
        // New feature commands
        VanishFollowCommand followCmd = new VanishFollowCommand(this); // self-registering listener
        registerCommand("vspec", new VanishSpectateCommand(this));
        registerCommand("vfollow", followCmd);
        registerCommand("vhistory", new VanishHistoryCommand(this));
        registerCommand("vautovanish", new VanishAutoCommand(this));
        registerCommand("vstats", new VanishStatsCommand(this));
        registerCommand("vadmin", new VanishAdminCommand(this));
        registerCommand("vwand", new VanishWandCommand(this));
        registerCommand("vchangelog", new VanishChangelogCommand(this));
        registerCommand("vzone", new VanishZoneCommand(this));
        registerCommand("vincognito", new IncognitoCommand(this));
        net.thecommandcraft.vanishpp.gui.ProxyConfigGUI proxyConfigGui =
                new net.thecommandcraft.vanishpp.gui.ProxyConfigGUI(this);
        registerCommand("vproxygui", new VProxyGuiCommand(this, proxyConfigGui));

        // Scoreboard
        saveResource("scoreboards.yml", false);
        scoreboardConfig = YamlConfiguration.loadConfiguration(
                new java.io.File(getDataFolder(), "scoreboards.yml"));
        this.vanishScoreboard = new VanishScoreboard(this);
        vanishScoreboard.reload();

        // 5. Register Listeners
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        getServer().getPluginManager().registerEvents(this, this); // Preprocess listener
        getServer().getPluginManager().registerEvents(new VanishWandListener(this), this);
        getServer().getPluginManager().registerEvents(new IncognitoListener(this), this);
        getServer().getPluginManager().registerEvents(new VanishZoneListener(this), this);
        new net.thecommandcraft.vanishpp.listeners.MobAiManager(this).register();
        if (worldGuardHook != null) {
            getServer().getPluginManager().registerEvents(new WorldGuardVanishListener(this), this);
        }


        boolean hasVoiceChat = Bukkit.getPluginManager().getPlugin("voicechat") != null
                || Bukkit.getPluginManager().getPlugin("SimpleVoiceChat") != null;
        if (hasVoiceChat && configManager.voiceChatEnabled) {
            this.voiceChatHook = new VoiceChatHook(this);
            getServer().getPluginManager().registerEvents(voiceChatHook, this);
            getLogger().info("Hooked into Simple Voice Chat.");
        }

        // 6. Init Utils
        this.updateChecker = new UpdateChecker(this);
        this.updateChecker.check();
        this.updateChecker.startPeriodicCheck();

        try {
            this.pluginHider = new PluginHider(this);
            this.pluginHider.register();
        } catch (Throwable e) {
            getLogger().log(Level.WARNING, "Failed to initialize Plugin Hider", e);
        }

        startActionBarTask();
        startSyncTask();
        startAfkTask();

        // 7. Restore Player State
        this.vanishedPlayers = ConcurrentHashMap.newKeySet();
        this.vanishedPlayers.addAll(storageProvider.getVanishedPlayers());
        this.ignoredWarningPlayers = ConcurrentHashMap.newKeySet();

        for (UUID uuid : vanishedPlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                applyVanishEffects(p);
                integrationManager.updateHooks(p, true);
                if (tabPluginHook != null)
                    tabPluginHook.update(p, true);
                updateVanishVisibility(p);
            }
        }

        getLogger().info("Vanish++ " + getDescription().getVersion() + " enabled.");
    }

    private void checkPlatformCompatibility(boolean isFolia) {
        // --- Platform check ---
        String serverName = Bukkit.getName(); // "Paper", "Purpur", "Folia", "CraftBukkit", "Spigot", etc.
        boolean isPaper = false;
        try {
            Class.forName("io.papermc.paper.event.player.AsyncChatEvent");
            isPaper = true;
        } catch (ClassNotFoundException ignored) {
        }
        // Folia also carries the full Paper API surface
        this.hasPaperApi = isPaper || isFolia;

        if (isFolia) {
            getLogger().info("Platform: Folia (natively supported).");
        } else if (isPaper) {
            // Paper and its forks (Purpur, etc.)
            getLogger().info("Platform: " + serverName + " (natively supported).");
        } else {
            // Spigot, CraftBukkit, or unknown
            getLogger().warning("Platform: " + serverName + " — this is NOT a natively supported platform.");
            getLogger().warning("Vanish++ is built for Paper, Purpur, and Folia. Running on " + serverName + " may cause");
            getLogger().warning("degraded functionality: projectile pass-through, mob AI goals, and some events may not work.");
            getLogger().warning("Consider switching to Paper for the full feature set: https://papermc.io/downloads");
        }

        // --- Minecraft version check ---
        String bukkitVersion = Bukkit.getBukkitVersion(); // e.g. "1.21.11-R0.1-SNAPSHOT"
        String mcVersion = bukkitVersion.split("-")[0];    // e.g. "1.21.11"
        String[] parts = mcVersion.split("\\.");

        boolean supported = false;
        if (parts.length >= 2) {
            try {
                int major = Integer.parseInt(parts[0]);
                int minor = Integer.parseInt(parts[1]);
                // Supported: 1.21.x (any subversion)
                if (major == 1 && minor == 21) {
                    supported = true;
                }
            } catch (NumberFormatException ignored) {
            }
        }

        if (supported) {
            getLogger().info("Minecraft version: " + mcVersion + " (supported).");
        } else {
            getLogger().warning("Minecraft version: " + mcVersion + " — this version has NOT been tested with Vanish++.");
            getLogger().warning("Vanish++ is built and tested for Minecraft 1.21 — 1.21.11. Running on " + mcVersion);
            getLogger().warning("may cause unexpected behavior or errors. Proceed at your own risk.");
        }
    }

    public void reloadPluginConfig() {
        if (proxyBridge != null && proxyBridge.isProxyDetected()) {
            // In proxy mode: ask the proxy to reload its config and push it back to us.
            // The CONFIG_PUSH response will call configManager.loadFromProxySnapshot().
            proxyBridge.sendReloadRequest("vanishreload");
            // Still reload local language files / scoreboard — only the main config defers to proxy
        }
        configManager.load();

        // Reinitialize storage if the backend type changed
        if (storageProvider != null) storageProvider.shutdown();
        if (redisStorage != null) { redisStorage.shutdown(); redisStorage = null; }
        if (luckPermsHook != null) { luckPermsHook.unload(); luckPermsHook = null; }
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") != null) {
            try {
                this.luckPermsHook = new LuckPermsHook(this);
                this.luckPermsHook.load();
            } catch (Throwable ignored) { this.luckPermsHook = null; }
        }
        initStorage();

        // Reload scoreboard config
        java.io.File sbFile = new java.io.File(getDataFolder(), "scoreboards.yml");
        if (sbFile.exists())
            scoreboardConfig = YamlConfiguration.loadConfiguration(sbFile);
        if (vanishScoreboard != null)
            vanishScoreboard.reload();

        // Refresh action bar state
        if (vanishScheduler != null) {
            vanishScheduler.cancelAllTasks();
            startActionBarTask();
            startSyncTask();
            startAfkTask();
        }

        // Refresh team prefix and resync all online vanished players
        refreshTeamPrefix();
        for (UUID uuid : vanishedPlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                resyncVanishEffects(p);
            }
        }
    }

    @Override
    public void onDisable() {
        if (proxyBridge != null) {
            proxyBridge.shutdown();
        }
        if (storageProvider != null) {
            storageProvider.shutdown();
        }
        if (redisStorage != null) {
            redisStorage.shutdown();
        }
        if (vanishScheduler != null) {
            vanishScheduler.cancelAllTasks();
        }
        if (vanishTeam != null) {
            vanishTeam.unregister();
        }
        if (integrationManager != null) {
            integrationManager.unregister();
        }
        if (luckPermsHook != null) {
            luckPermsHook.unload();
        }
        if (vanishScoreboard != null) {
            vanishScoreboard.shutdown();
        }
        // Record sessions for all currently vanished online players
        for (UUID uuid : new java.util.HashSet<>(vanishStartTimes.keySet())) {
            long start = vanishStartTimes.remove(uuid);
            long duration = System.currentTimeMillis() - start;
            if (duration > 0 && storageProvider != null) {
                try { storageProvider.recordVanishSession(uuid, duration); } catch (Exception ignored) {}
            }
        }
    }

    private void initStorage() {
        String type = configManager.getConfig().getString("storage.type", "YAML").toUpperCase();
        StorageProvider newProvider;
        if (type.equals("MYSQL") || type.equals("POSTGRESQL")) {
            newProvider = new SqlStorage(this, type);
        } else {
            newProvider = new YamlStorage(this);
        }

        try {
            newProvider.init();
        } catch (Exception e) {
            getLogger().severe("FAILED TO INITIALIZE STORAGE: " + e.getMessage());
            getLogger().severe("Falling back to YAML storage.");
            newProvider = new YamlStorage(this);
            try {
                newProvider.init();
            } catch (Exception ignored) {}
        }

        // Migrate data from old storage if the new storage is empty and another source has data
        migrateIfNeeded(newProvider, type);

        this.storageProvider = newProvider;

        if (configManager.getConfig().getBoolean("storage.redis.enabled", false)) {
            this.redisStorage = new RedisStorage(this);
            this.redisStorage.init();
        }
    }

    private void migrateIfNeeded(StorageProvider target, String targetType) {
        if (!target.getAllKnownPlayers().isEmpty()) return; // target already has data, nothing to migrate

        // Determine the other storage to migrate FROM
        StorageProvider source = null;
        boolean sourceOwned = false;
        if (!targetType.equals("YAML")) {
            // Migrating TO SQL — check if YAML has data
            YamlStorage yaml = new YamlStorage(this);
            try { yaml.init(); } catch (Exception ignored) { return; }
            if (!yaml.getAllKnownPlayers().isEmpty()) {
                source = yaml;
                sourceOwned = true;
            }
        } else {
            // Migrating TO YAML — check if SQL has data (use current config connection details)
            String prev = configManager.getConfig().getString("storage.type", "YAML").toUpperCase();
            if (prev.equals("MYSQL") || prev.equals("POSTGRESQL")) {
                SqlStorage sql = new SqlStorage(this, prev);
                try {
                    sql.init();
                    if (!sql.getAllKnownPlayers().isEmpty()) {
                        source = sql;
                        sourceOwned = true;
                    } else {
                        sql.shutdown();
                    }
                } catch (Exception ignored) {}
            }
        }

        if (source == null) return;

        getLogger().info("Migrating storage data from " + source.getClass().getSimpleName() + " → " + target.getClass().getSimpleName() + "...");
        int count = 0;
        for (UUID uuid : source.getAllKnownPlayers()) {
            // Vanish state
            if (source.isVanished(uuid)) target.setVanished(uuid, true);
            // Rules
            source.getRules(uuid).forEach((rule, val) -> target.setRule(uuid, rule, val));
            // Vanish level
            int level = source.getVanishLevel(uuid);
            if (level != 1) target.setVanishLevel(uuid, level);
            // Acknowledgements
            source.getAcknowledgements(uuid).forEach(id -> target.addAcknowledgement(uuid, id));
            count++;
        }
        getLogger().info("Storage migration complete: " + count + " player(s) migrated.");
        if (sourceOwned) source.shutdown();
    }

    public void handleNetworkVanishSync(UUID uuid, boolean vanish) {
        // Idempotency check: skip if state already matches network message
        boolean isCurrentlyVanished = vanishedPlayers.contains(uuid);
        if (isCurrentlyVanished == vanish) {
            // Already in desired state — this is a duplicate message, skip it
            getLogger().fine("Ignoring duplicate network vanish sync for " + uuid + " (already " + (vanish ? "vanished" : "unvanished") + ")");
            return;
        }

        Player p = Bukkit.getPlayer(uuid);
        if (vanish) {
            vanishedPlayers.add(uuid);
            if (p != null && p.isOnline()) {
                applyVanishEffects(p);
                integrationManager.updateHooks(p, true);
                if (tabPluginHook != null)
                    tabPluginHook.update(p, true);
                updateVanishVisibility(p);
            }
        } else {
            vanishedPlayers.remove(uuid);
            if (p != null && p.isOnline()) {
                removeVanishEffects(p);
            }
        }
    }

    /**
     * Reconciles this server's in-memory vanish state against the DB-authoritative value
     * for a player who just joined. Must run on the main/global thread.
     *
     * <p>Three outcomes:
     * <ul>
     *   <li>DB == memory: already in sync, no-op.</li>
     *   <li>DB vanished, not in memory: player was vanished on another server — apply locally.</li>
     *   <li>DB not vanished, but in memory: player was unvanished on another server — clear locally.</li>
     * </ul>
     *
     * <p>Both corrective branches call the normal apply/remove methods, which re-persist to DB
     * (idempotent INSERT IGNORE / DELETE on an already-correct row) and re-broadcast via Redis
     * (idempotent — other servers' handleNetworkVanishSync sees no state change and skips).
     */
    public void reconcileVanishState(Player player, boolean dbVanished) {
        boolean memVanished = vanishedPlayers.contains(player.getUniqueId());
        if (dbVanished == memVanished) return;

        if (dbVanished) {
            // Vanished on another server — apply vanish on this server
            getLogger().fine("Cross-server vanish detected for " + player.getName() + " on join — applying locally");
            applyVanishEffects(player);
            updateVanishVisibility(player);
            String joinMsg = configManager.getLanguageManager().getMessage("staff.silent-join")
                    .replace("%player%", player.getName());
            Component joinComp = messageManager.parse(joinMsg, player);
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (!staff.equals(player) && permissionManager.hasPermission(staff, "vanishpp.see"))
                    staff.sendMessage(joinComp);
            }
            Bukkit.getConsoleSender().sendMessage(joinComp);
        } else {
            // Unvanished on another server — clear stale local state
            getLogger().fine("Cross-server unvanish detected for " + player.getName() + " on join — clearing locally");
            removeVanishEffects(player);
        }
    }

    private void hookProtocolLib() {
        ProtocolLibManager manager = new ProtocolLibManager(this);
        manager.load();
        this.protocolLibManager = manager;
    }

    public ProtocolLibManager getProtocolLibManager() {
        return protocolLibManager;
    }

    // --- PUBLIC API GETTERS ---
    public boolean isVanished(Player player) {
        return vanishedPlayers.contains(player.getUniqueId());
    }

    public boolean isVanished(UUID uuid) {
        return vanishedPlayers.contains(uuid);
    }

    public UpdateChecker getUpdateChecker() {
        return updateChecker;
    }

    public LanguageManager getLanguageManager() {
        return configManager.getLanguageManager();
    }

    public boolean hasProtocolLib() {
        return hasProtocolLib;
    }

    /** True when running on Paper, Purpur, or Folia — platforms that expose the Paper API (MobGoals, etc.). */
    public boolean hasPaperApi() {
        return hasPaperApi;
    }

    public Set<UUID> getIgnoredWarningPlayers() {
        return ignoredWarningPlayers;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public StorageProvider getStorageProvider() {
        return storageProvider;
    }

    public IntegrationManager getIntegrationManager() {
        return integrationManager;
    }

    public PermissionManager getPermissionManager() {
        return permissionManager;
    }

    public RuleManager getRuleManager() {
        return ruleManager;
    }

    public Set<UUID> getRawVanishedPlayers() {
        return Collections.unmodifiableSet(vanishedPlayers);
    }

    public ProxyBridge getProxyBridge() {
        return proxyBridge;
    }

    public ProxyConfigCache getProxyConfigCache() {
        return proxyConfigCache;
    }

    /**
     * Applies a full vanish state snapshot received from the proxy (STATE_RESPONSE).
     * Players listed as vanished are added to the local set; all others are removed.
     * Must run on the global/main thread.
     */
    public void applyNetworkVanishState(java.util.List<NetworkVanishState> states) {
        Set<UUID> networkVanished = new HashSet<>();
        for (NetworkVanishState state : states) networkVanished.add(state.uuid());

        // Add any that aren't already tracked
        for (UUID uuid : networkVanished) {
            if (!vanishedPlayers.contains(uuid)) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && p.isOnline()) {
                    applyVanishEffects(p);
                } else {
                    vanishedPlayers.add(uuid);
                }
            }
        }
        // Remove stale entries that the proxy says are no longer vanished
        Set<UUID> toRemove = new HashSet<>(vanishedPlayers);
        toRemove.removeAll(networkVanished);
        for (UUID uuid : toRemove) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) removeVanishEffects(p);
            else vanishedPlayers.remove(uuid);
        }
    }

    public VanishScheduler getVanishScheduler() {
        return vanishScheduler;
    }

    public MessageManager getMessageManager() {
        return messageManager;
    }

    public TabPluginHook getTabPluginHook() {
        return tabPluginHook;
    }

    public List<StartupChecker.Warning> getStartupWarnings() {
        return startupWarnings;
    }

    public VanishScoreboard getVanishScoreboard() {
        return vanishScoreboard;
    }

    public YamlConfiguration getScoreboardConfig() {
        return scoreboardConfig;
    }

    private void registerCommand(String name, org.bukkit.command.CommandExecutor executor) {
        org.bukkit.command.PluginCommand cmd = getCommand(name);
        if (cmd == null) {
            getLogger().warning("Command '" + name + "' is not defined in plugin.yml — skipping registration.");
            return;
        }
        cmd.setExecutor(executor);
    }

    /** Cleans up per-player cached state. Call on player quit. */
    public void cleanupPlayerCache(UUID uuid) {
        actionBarPausedUntil.remove(uuid);
        actionBarWarningComponent.remove(uuid);
        ignoredWarningPlayers.remove(uuid);
        playerNameCache.values().remove(uuid);
        spectateFollowTargets.remove(uuid);
        spectateOrigins.remove(uuid);
        spectateOriginalGamemodes.remove(uuid);
        incognitoNames.remove(uuid);
        vanishReasons.remove(uuid);
        vanishToggleCooldowns.remove(uuid);
        pvpCombatTimestamps.remove(uuid);
        pveCombatTimestamps.remove(uuid);
        timedVanishExpiry.remove(uuid);
        lastMovementTime.remove(uuid);
        afkVanishedPlayers.remove(uuid);
        partialVanishWhitelist.remove(uuid);
        worldRuleOverrides.remove(uuid);
        // Record session on quit if still vanished
        Long start = vanishStartTimes.remove(uuid);
        if (start != null) {
            long duration = System.currentTimeMillis() - start;
            if (duration > 0) {
                vanishScheduler.runAsync(() -> {
                    try { storageProvider.recordVanishSession(uuid, duration); } catch (Exception ignored) {}
                });
            }
        }
        if (vanishScoreboard != null)
            vanishScoreboard.cleanup(uuid);
        if (vanishBossbar != null)
            vanishBossbar.cleanup(uuid);
    }

    public GameMode getPreVanishGamemodePublic(Player player) {
        return getPreVanishGamemode(player);
    }

    private GameMode getPreVanishGamemode(Player player) {
        List<org.bukkit.metadata.MetadataValue> meta = player.getMetadata("vanishpp_pre_vanish_gamemode");
        if (!meta.isEmpty()) {
            Object val = meta.get(0).value();
            if (val instanceof GameMode gm) return gm;
        }
        return GameMode.SURVIVAL;
    }

    // --- CORE LOGIC ---
    private void setupTeams() {
        try {
            org.bukkit.scoreboard.ScoreboardManager sm = Bukkit.getScoreboardManager();
            if (sm == null) {
                getLogger().severe("ScoreboardManager is null — cannot set up vanish team. Nametag features disabled.");
                return;
            }
            Scoreboard mainScoreboard = sm.getMainScoreboard();
            this.vanishTeam = mainScoreboard.getTeam("Vanishpp_Vanished");
            if (this.vanishTeam == null)
                this.vanishTeam = mainScoreboard.registerNewTeam("Vanishpp_Vanished");

            refreshTeamPrefix();
            vanishTeam.setCanSeeFriendlyInvisibles(true);
            vanishTeam.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        } catch (UnsupportedOperationException e) {
            getLogger().warning("Scoreboard team setup not supported on this platform. Nametag features disabled.");
            this.vanishTeam = null;
        }
    }

    public void refreshTeamPrefix() {
        if (vanishTeam == null) return;
        String raw = configManager.vanishNametagPrefix;
        if (raw != null && !raw.isEmpty()) {
            vanishTeam.prefix(messageManager.parse(raw, null));
        } else {
            vanishTeam.prefix(Component.empty());
        }
    }

    /** Remove and re-add a player to the vanish team to force a scoreboard update packet to all observers. */
    public void reapplyTeamEntry(Player player) {
        if (vanishTeam == null) return;
        String name = player.getName();
        if (vanishTeam.hasEntry(name))
            vanishTeam.removeEntry(name);
        vanishTeam.addEntry(name);
        refreshTeamPrefix();
    }

    private void startActionBarTask() {
        vanishScheduler.runTimerGlobal(() -> {
            if (!configManager.actionBarEnabled) return;
            long now = System.currentTimeMillis();
            for (UUID uuid : vanishedPlayers) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && p.isOnline()) {
                    long pausedUntil = actionBarPausedUntil.getOrDefault(uuid, 0L);
                    if (now > pausedUntil) {
                        actionBarWarningComponent.remove(uuid);
                        // If a timed vanish is active, append countdown to the action bar
                        long remaining = getTimedRemaining(uuid);
                        Component bar = messageManager.parse(configManager.actionBarText, p);
                        if (remaining > 0) {
                            long secs = remaining / 1000L;
                            bar = bar.append(messageManager.parse(
                                    " &7(" + formatTimedRemaining(secs) + ")", p));
                        }
                        p.sendActionBar(bar);
                    } else {
                        Component warning = actionBarWarningComponent.get(uuid);
                        if (warning != null) p.sendActionBar(warning);
                    }
                }
            }
        }, 1L, 20L);
    }

    private static String formatTimedRemaining(long totalSeconds) {
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }

    public void triggerActionBarWarning(Player p, Component warning) {
        triggerActionBarWarning(p, warning, 2000);
    }

    public void triggerActionBarWarning(Player p, Component warning, long durationMs) {
        UUID uuid = p.getUniqueId();
        actionBarPausedUntil.put(uuid, System.currentTimeMillis() + durationMs);
        actionBarWarningComponent.put(uuid, warning);
        p.sendActionBar(warning);
    }

    private void startSyncTask() {
        // Fast visibility/glow/prefix sync — runs every 10 ticks (0.5s).
        // Lightweight: just checks and fixes show/hide state without forced entity respawn.
        // Catches permission changes, op/deop, and any external plugins overriding visibility.
        vanishScheduler.runTimerGlobal(() -> {
            Set<UUID> vanishedCopy = Set.copyOf(vanishedPlayers);
            List<Player> onlinePlayers = new ArrayList<>(Bukkit.getOnlinePlayers());

            for (UUID uuid : vanishedCopy) {
                Player vanished = Bukkit.getPlayer(uuid);
                if (vanished == null || !vanished.isOnline()) continue;

                // Reapply team entry + tab prefix (catches TAB plugin or scoreboard overrides)
                if (vanishTeam != null && !vanishTeam.hasEntry(vanished.getName()))
                    vanishTeam.addEntry(vanished.getName());
                if (configManager.vanishTabPrefix != null && !configManager.vanishTabPrefix.isEmpty()) {
                    vanished.playerListName(messageManager.parse(
                            configManager.vanishTabPrefix + vanished.getName(), vanished));
                }

                // Fix visibility: ensure non-seers can't see, seers can see
                for (Player observer : onlinePlayers) {
                    if (observer.equals(vanished)) continue;
                    boolean canSee = permissionManager.canSee(observer, vanished);
                    boolean currentlySees = observer.canSee(vanished);
                    if (!canSee && currentlySees) {
                        observer.hidePlayer(this, vanished);
                    } else if (canSee && !currentlySees) {
                        observer.showPlayer(this, vanished);
                    }
                }

                // Send glow metadata to staff (ensures glow is always visible)
                if (hasProtocolLib && protocolLibManager != null) {
                    protocolLibManager.sendGlowMetadata(vanished);
                }

                // Mob targeting is handled by EntityTargetEvent (PlayerListener) — no polling needed.
            }
        }, 10L, 10L);

        // Pending rule-expiry notification delivery — runs every 100 ticks (5 seconds).
        // Handles the case where a timed rule expired on another server while the player was
        // already connected here (so no PlayerJoinEvent fired to trigger the join-time check).
        // DB lookup is async; message delivery is marshalled back to the main thread.
        vanishScheduler.runTimerGlobal(() -> {
            Collection<? extends Player> online = List.copyOf(Bukkit.getOnlinePlayers());
            if (online.isEmpty()) return;
            vanishScheduler.runAsync(() -> {
                for (Player p : online) {
                    if (!p.isOnline()) continue;
                    java.util.Map<String, Object> rules = storageProvider.getRules(p.getUniqueId());
                    boolean hasPending = rules.entrySet().stream().anyMatch(e ->
                            e.getKey().startsWith(PENDING_NOTIFY_PREFIX) && Boolean.parseBoolean(String.valueOf(e.getValue())));
                    if (!hasPending) continue;
                    vanishScheduler.runGlobal(() -> {
                        if (!p.isOnline()) return;
                        for (java.util.Map.Entry<String, Object> entry : rules.entrySet()) {
                            if (!entry.getKey().startsWith(PENDING_NOTIFY_PREFIX)) continue;
                            if (!Boolean.parseBoolean(String.valueOf(entry.getValue()))) continue;
                            String expiredRule = entry.getKey().substring(PENDING_NOTIFY_PREFIX.length());
                            String msg = configManager.getLanguageManager().getMessage("rules.expired")
                                    .replace("%rule%", expiredRule).replace("%player%", p.getName());
                            messageManager.sendMessage(p, msg);
                            storageProvider.setRule(p.getUniqueId(), entry.getKey(), false);
                        }
                    });
                }
            });
        }, 100L, 100L);

        // Periodic DB reconciliation for offline vanished players — runs every 60 seconds.
        // Catches state changes made on other servers sharing the same database (without Redis).
        // Only queries the DB when there are actually offline vanished UUIDs to check, so
        // servers with no shared-DB setup pay near-zero cost.
        vanishScheduler.runTimerGlobal(() -> {
            // Collect offline UUIDs from the in-memory set (main thread — safe Bukkit API call)
            Set<UUID> offlineVanished = new HashSet<>();
            for (UUID uuid : vanishedPlayers) {
                if (Bukkit.getPlayer(uuid) == null) offlineVanished.add(uuid);
            }
            if (offlineVanished.isEmpty()) return;

            vanishScheduler.runAsync(() -> {
                Set<UUID> dbVanished = storageProvider.getVanishedPlayers();
                for (UUID uuid : offlineVanished) {
                    if (!dbVanished.contains(uuid)) {
                        // Player was unvanished on another server while offline here — remove stale entry.
                        // ConcurrentHashMap.newKeySet() remove is safe from any thread.
                        vanishedPlayers.remove(uuid);
                        getLogger().fine("Removed stale offline vanish entry for " + uuid
                                + " — unvanished on another server");
                    }
                }
            });
        }, 1200L, 1200L); // 60 seconds
    }

    private void startAfkTask() {
        if (!configManager.afkAutoVanishEnabled) return;
        // Check every 10 seconds for idle players
        vanishScheduler.runTimerGlobal(() -> {
            if (!configManager.afkAutoVanishEnabled) return;
            long now = System.currentTimeMillis();
            long thresholdMs = configManager.afkAutoVanishSeconds * 1000L;
            for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) {
                if (!p.isOnline()) continue;
                if (p.hasPermission("vanishpp.afk.bypass")) continue;
                if (!permissionManager.hasPermission(p, "vanishpp.vanish")) continue;
                UUID uuid = p.getUniqueId();
                if (isVanished(p)) {
                    // Check if we should unvanish on return (handled via PlayerMoveEvent)
                    continue;
                }
                Long lastMove = lastMovementTime.get(uuid);
                if (lastMove == null) {
                    lastMovementTime.put(uuid, now);
                    continue;
                }
                if ((now - lastMove) >= thresholdMs) {
                    afkVanishedPlayers.add(uuid);
                    lastMovementTime.put(uuid, now); // reset so we don't re-trigger immediately
                    vanishPlayer(p, p);
                    messageManager.sendMessage(p,
                            configManager.getLanguageManager().getMessage("afk.auto-vanished"));
                }
            }
        }, 200L, 200L); // every 10 seconds
    }

    public void applyVanishEffects(Player player) {
        vanishedPlayers.add(player.getUniqueId());
        if (vanishTeam != null) vanishTeam.addEntry(player.getName());
        player.setMetadata("vanished", new FixedMetadataValue(this, true));

        if (configManager.vanishTabPrefix != null && !configManager.vanishTabPrefix.isEmpty()) {
            player.playerListName(messageManager.parse(configManager.vanishTabPrefix + player.getName(), player));
        }

        if (configManager.preventSleeping)
            try {
                player.setSleepingIgnored(true);
            } catch (Throwable ignored) {
            }

        if (configManager.enableNightVision && permissionManager.hasPermission(player, "vanishpp.nightvision")) {
            player.addPotionEffect(
                    new PotionEffect(PotionEffectType.NIGHT_VISION, PotionEffect.INFINITE_DURATION, 0, false, false));
        }

        // Invisibility so mobs cannot see the player at all
        player.addPotionEffect(
                new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false));

        player.setInvisible(true);

        // Spectator mode is the only vanilla mechanism that removes a player from
        // LookAtPlayerGoal's getNearestPlayer() scan (EntitySelector.NO_SPECTATORS).
        // setInvisible alone does NOT prevent look goals from finding the player.
        if (ruleManager.getRule(player, RuleManager.SPECTATOR_GAMEMODE)
                && player.getGameMode() != GameMode.SPECTATOR) {
            if (!player.hasMetadata("vanishpp_pre_vanish_gamemode")) {
                GameMode gm = player.getGameMode();
                player.setMetadata("vanishpp_pre_vanish_gamemode", new FixedMetadataValue(this, gm));
            }
            player.setGameMode(GameMode.SPECTATOR);
        }

        player.setCollidable(false);

        // ALWAYS clear existing mob targets when vanishing (regardless of mob_targeting rule)
        // The rule only controls whether new targets can be acquired AFTER vanishing
        for (Entity entity : player.getWorld().getEntities()) {
            if (entity instanceof Mob mob && player.equals(mob.getTarget())) {
                mob.setTarget(null);
                mob.getPathfinder().stopPathfinding();
            }
        }

        if (configManager.enableFly && player.getGameMode() != GameMode.SPECTATOR) {
            // Store pre-vanish fly state so we can restore it exactly on unvanish
            player.setMetadata("vanishpp_pre_vanish_fly",
                    new FixedMetadataValue(this, player.getAllowFlight()));
            player.setAllowFlight(true);
            player.setFlying(true);
        }

        if (voiceChatHook != null)
            voiceChatHook.updateVanishState(player, true);
        integrationManager.updateHooks(player, true);
        if (tabPluginHook != null)
            tabPluginHook.update(player, true);
        refreshVisibilityWithGlow(player);

        // Instant action bar feedback — don't wait for the scheduler's next cycle
        if (configManager.actionBarEnabled) {
            player.sendActionBar(messageManager.parse(configManager.actionBarText, player));
        }

        // Track vanish start time for stats
        vanishStartTimes.put(player.getUniqueId(), System.currentTimeMillis());
        // Show bossbar
        if (vanishBossbar != null) vanishBossbar.show(player);

        UUID persistUuid = player.getUniqueId();
        String persistName = player.getName();
        int persistLevel = storageProvider.getVanishLevel(persistUuid);
        String reason = vanishReasons.getOrDefault(persistUuid, null);
        vanishScheduler.runAsync(() -> {
            storageProvider.setVanished(persistUuid, true);
            // Add history entry
            try {
                String serverName = Bukkit.getServer().getName();
                storageProvider.addHistoryEntry(
                    net.thecommandcraft.vanishpp.storage.VanishHistoryEntry.vanish(persistUuid, persistName, serverName, reason));
            } catch (Exception ignored) {}
            if (proxyBridge != null && proxyBridge.isProxyDetected()) {
                // Proxy handles cross-server broadcast
                proxyBridge.sendVanishEvent(persistUuid, persistName, true, persistLevel);
            } else if (redisStorage != null) {
                redisStorage.broadcastVanish(persistUuid, true);
            }
        });
        // Update LuckPerms context
        if (luckPermsHook != null) luckPermsHook.setVanished(player, true);
        // Fire webhooks
        if (webhookManager != null) {
            webhookManager.send(player, "VANISH", reason != null ? reason : "");
        }

        if (vanishScoreboard != null) {
            try {
                vanishScoreboard.onVanish(player);
            } catch (Exception e) {
                getLogger().fine("Scoreboard update failed (may be test environment): " + e.getClass().getSimpleName());
            }
        }
    }

    public void removeVanishEffects(Player player) {
        vanishedPlayers.remove(player.getUniqueId());
        try {
            player.setSleepingIgnored(false);
        } catch (Throwable ignored) {
        }
        player.removeMetadata("vanished", this);
        player.playerListName(null);

        if (vanishTeam != null && vanishTeam.hasEntry(player.getName()))
            vanishTeam.removeEntry(player.getName());

        player.setCollidable(true);

        // If the player is in spectator (from double-shift toggle), restore their pre-vanish gamemode.
        // Players with vanishpp.spectator.bypass are allowed to stay in spectator after unvanish.
        if (player.getGameMode() == GameMode.SPECTATOR
                && !permissionManager.hasPermission(player, "vanishpp.spectator.bypass")) {
            GameMode prevGm = getPreVanishGamemode(player);
            player.setGameMode(prevGm);
            String msg = configManager.getLanguageManager().getMessage("spectator.forced-unvanish")
                    .replace("%gamemode%", prevGm.name().toLowerCase());
            messageManager.sendMessage(player, msg);
        }
        player.removeMetadata("vanishpp_pre_vanish_gamemode", this);

        if (player.hasPotionEffect(PotionEffectType.NIGHT_VISION))
            player.removePotionEffect(PotionEffectType.NIGHT_VISION);
        if (player.hasPotionEffect(PotionEffectType.INVISIBILITY))
            player.removePotionEffect(PotionEffectType.INVISIBILITY);
        player.setInvisible(false);
        if (configManager.disableFlyOnUnvanish && player.getGameMode() != GameMode.CREATIVE
                && player.getGameMode() != GameMode.SPECTATOR) {
            // Restore exactly the fly state that existed before vanish
            boolean hadFly = player.hasMetadata("vanishpp_pre_vanish_fly")
                    && (boolean) player.getMetadata("vanishpp_pre_vanish_fly").get(0).value();
            player.setAllowFlight(hadFly);
            if (!hadFly) player.setFlying(false);
        }
        player.removeMetadata("vanishpp_pre_vanish_fly", this);

        if (voiceChatHook != null)
            voiceChatHook.updateVanishState(player, false);
        integrationManager.updateHooks(player, false);
        if (tabPluginHook != null)
            tabPluginHook.update(player, false);
        // Use hide-then-show to force fresh metadata packets — removes stale glow from staff clients
        refreshVisibilityWithGlow(player);

        // Instantly clear the action bar — don't leave it showing until the next scheduler tick
        player.sendActionBar(Component.empty());

        // Hide bossbar
        if (vanishBossbar != null) vanishBossbar.hide(player);
        // Record session duration
        Long vanishStart = vanishStartTimes.remove(player.getUniqueId());
        long sessionDuration = (vanishStart != null) ? (System.currentTimeMillis() - vanishStart) : 0L;

        UUID persistUuid = player.getUniqueId();
        String persistNameUv = player.getName();
        vanishScheduler.runAsync(() -> {
            storageProvider.setVanished(persistUuid, false);
            // Add history entry
            try {
                String serverName = Bukkit.getServer().getName();
                storageProvider.addHistoryEntry(
                    net.thecommandcraft.vanishpp.storage.VanishHistoryEntry.unvanish(persistUuid, persistNameUv, serverName, null, sessionDuration));
                if (sessionDuration > 0) {
                    storageProvider.recordVanishSession(persistUuid, sessionDuration);
                }
            } catch (Exception ignored) {}
            if (proxyBridge != null && proxyBridge.isProxyDetected()) {
                proxyBridge.sendVanishEvent(persistUuid, persistNameUv, false, 1);
            } else if (redisStorage != null) {
                redisStorage.broadcastVanish(persistUuid, false);
            }
        });
        // Update LuckPerms context
        if (luckPermsHook != null) luckPermsHook.setVanished(player, false);
        // Fire webhooks
        if (webhookManager != null) {
            webhookManager.send(player, "UNVANISH", "");
        }
        // Clear vanish reason
        vanishReasons.remove(player.getUniqueId());

        if (vanishScoreboard != null) {
            try {
                vanishScoreboard.onUnvanish(player);
            } catch (Exception e) {
                getLogger().fine("Scoreboard update failed (may be test environment): " + e.getClass().getSimpleName());
            }
        }
    }

    public void vanishPlayer(Player player, CommandSender executor) {
        // Store the gamemode from before vanish so we can restore it on unvanish.
        // Only set on an explicit vanish — not on join restore (applyVanishEffects).
        // Guard against overwrite if already set (e.g., re-vanish without unvanish).
        if (!player.hasMetadata("vanishpp_pre_vanish_gamemode")) {
            GameMode gmToStore = player.getGameMode() == GameMode.SPECTATOR
                    ? GameMode.SURVIVAL : player.getGameMode();
            player.setMetadata("vanishpp_pre_vanish_gamemode", new FixedMetadataValue(this, gmToStore));
        }
        applyVanishEffects(player);
        if (isValidMessage(configManager.vanishMessage)) {
            player.sendMessage(messageManager.parse(configManager.vanishMessage, player));
        }
        if (configManager.broadcastFakeQuit) {
            String fakeMsg = configManager.fakeQuitMessage;
            if (isValidMessage(fakeMsg)) {
                String finalMsg = fakeMsg.replace("%player%", player.getName())
                        .replace("%displayname%", player.getDisplayName());
                broadcastToUnaware(messageManager.parse(finalMsg, player), player);
            } else {
                broadcastToUnaware(
                        Component.translatable("multiplayer.player.left", NamedTextColor.YELLOW, player.displayName()),
                        player);
            }
            // Send to Discord
            if (integrationManager.getDiscordSRV() != null) {
                integrationManager.getDiscordSRV().sendFakeQuit(player);
            }
        }
        notifyStaff(player, executor, true);
    }

    public void unvanishPlayer(Player player, CommandSender executor) {
        if (configManager.broadcastFakeJoin) {
            String fakeMsg = configManager.fakeJoinMessage;
            if (isValidMessage(fakeMsg)) {
                String finalMsg = fakeMsg.replace("%player%", player.getName())
                        .replace("%displayname%", player.getDisplayName());
                broadcastToUnaware(messageManager.parse(finalMsg, player), player);
            } else {
                broadcastToUnaware(Component.translatable("multiplayer.player.joined", NamedTextColor.YELLOW,
                        player.displayName()), player);
            }
            // Send to Discord
            if (integrationManager.getDiscordSRV() != null) {
                integrationManager.getDiscordSRV().sendFakeJoin(player);
            }
        }
        removeVanishEffects(player);
        if (isValidMessage(configManager.unvanishMessage)) {
            player.sendMessage(messageManager.parse(configManager.unvanishMessage, player));
        }
        notifyStaff(player, executor, false);
    }

    /** Vanish a player with an optional reason string. */
    public void vanishPlayer(Player player, CommandSender executor, String reason) {
        if (reason != null && !reason.isEmpty()) {
            vanishReasons.put(player.getUniqueId(), reason);
        }
        vanishPlayer(player, executor);
    }

    private boolean isValidMessage(String msg) {
        return msg != null && !msg.isEmpty() && !msg.equalsIgnoreCase("false") && !msg.equalsIgnoreCase("none");
    }

    private void broadcastToUnaware(Component message, Player vanishedPlayer) {
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (!permissionManager.canSee(onlinePlayer, vanishedPlayer) && !onlinePlayer.equals(vanishedPlayer))
                onlinePlayer.sendMessage(message);
        }
    }

    private void notifyStaff(Player subject, CommandSender executor, boolean isVanishing) {
        if (!configManager.staffNotifyEnabled)
            return;
        String template = isVanishing ? configManager.staffVanishMessage : configManager.staffUnvanishMessage;
        String notification = template.replace("%player%", subject.getName()).replace("%staff%", executor.getName());
        Component comp = messageManager.parse(notification, subject);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (permissionManager.hasPermission(p, "vanishpp.see")) {
                p.sendMessage(comp);
                if (configManager.staffSoundsEnabled) {
                    playStaffSound(p, isVanishing
                            ? configManager.staffSoundsVanishSound
                            : configManager.staffSoundsUnvanishSound,
                            isVanishing ? configManager.staffSoundsVanishVolume : configManager.staffSoundsUnvanishVolume,
                            isVanishing ? configManager.staffSoundsVanishPitch  : configManager.staffSoundsUnvanishPitch);
                }
            }
        }
        Bukkit.getConsoleSender().sendMessage(comp);
    }

    /** Safely plays a configurable sound to a player, ignoring invalid sound names. */
    public void playStaffSound(Player player, String soundName, float volume, float pitch) {
        if (soundName == null || soundName.isBlank()) return;
        try {
            org.bukkit.Sound sound = org.bukkit.Sound.valueOf(soundName.toUpperCase());
            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (IllegalArgumentException ignored) {
            // Invalid sound name in config — silently skip
        }
    }

    public void updateVanishVisibility(Player subject) {
        boolean isVanished = isVanished(subject);
        for (Player observer : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            if (observer.equals(subject))
                continue;
            boolean canSee = permissionManager.canSee(observer, subject);
            if (isVanished && !canSee) {
                observer.hidePlayer(this, subject);
            } else {
                observer.showPlayer(this, subject);
            }
        }
    }

    /** Returns true if {@code observer} can see {@code subject} accounting for partial-vanish whitelist. */
    public boolean canSeePartial(Player observer, Player subject) {
        Set<UUID> whitelist = partialVanishWhitelist.get(subject.getUniqueId());
        if (whitelist != null) {
            return whitelist.contains(observer.getUniqueId())
                    || permissionManager.hasPermission(observer, "vanishpp.see");
        }
        return permissionManager.canSee(observer, subject);
    }

    /**
     * Folia-safe visibility update with region-aware scheduling.
     * For Folia, ensures visibility packets are sent from the correct region thread.
     */
    public void updateVanishVisibilityFolia(Player subject) {
        // For Folia: schedule the visibility update on the subject's region to avoid thread safety issues
        // For other servers: runs immediately
        boolean isVanished = isVanished(subject);
        for (Player observer : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            if (observer.equals(subject))
                continue;
            boolean canSee = permissionManager.canSee(observer, subject);
            if (isVanished && !canSee) {
                observer.hidePlayer(this, subject);
            } else {
                observer.showPlayer(this, subject);
            }
        }
    }

    /**
     * Hide-then-show for observers to force entity respawn + metadata packets.
     * After showing, sends an explicit glow metadata packet to staff so the glow
     * appears immediately (not on next sneak/pose change).
     */
    public void refreshVisibilityWithGlow(Player subject) {
        boolean subjectVanished = isVanished(subject);
        for (Player observer : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            if (observer.equals(subject))
                continue;
            boolean canSee = permissionManager.canSee(observer, subject);
            if (subjectVanished && !canSee) {
                observer.hidePlayer(this, subject);
            } else if (canSee) {
                // Force entity respawn only for seers — needed to apply/clear glow metadata.
                // Non-seers get a plain showPlayer below without the extra destroy packet.
                observer.hidePlayer(this, subject);
                observer.showPlayer(this, subject);
            } else {
                // Non-seer, subject not vanished: just show (hide was already a no-op)
                observer.showPlayer(this, subject);
            }
        }
        // Send explicit glow metadata after entity respawn
        if (subjectVanished && hasProtocolLib && protocolLibManager != null) {
            vanishScheduler.runLaterGlobal(() -> {
                if (subject.isOnline())
                    protocolLibManager.sendGlowMetadata(subject);
            }, 2L);
        }
    }

    /**
     * Lightweight resync that reapplies ALL vanish state without touching storage/Redis.
     * Use after respawn, world change, gamemode change, or reload.
     */
    public void resyncVanishEffects(Player player) {
        if (!isVanished(player)) return;

        // Team + prefix
        if (vanishTeam != null) vanishTeam.addEntry(player.getName());
        refreshTeamPrefix();
        if (configManager.vanishTabPrefix != null && !configManager.vanishTabPrefix.isEmpty()) {
            player.playerListName(messageManager.parse(configManager.vanishTabPrefix + player.getName(), player));
        }

        // Metadata
        player.setMetadata("vanished", new FixedMetadataValue(this, true));
        player.setCollidable(false);

        // Fly
        if (configManager.enableFly && player.getGameMode() != GameMode.SPECTATOR) {
            player.setAllowFlight(true);
            player.setFlying(true);
        }

        // Night vision
        if (configManager.enableNightVision && permissionManager.hasPermission(player, "vanishpp.nightvision")) {
            player.addPotionEffect(
                    new PotionEffect(PotionEffectType.NIGHT_VISION, PotionEffect.INFINITE_DURATION, 0, false, false));
        }

        // Spawning / sleeping
        if (configManager.preventSleeping)
            try { player.setSleepingIgnored(true); } catch (Throwable ignored) {}

        // ALWAYS clear mob targets (regardless of mob_targeting rule)
        // The rule only controls whether new targets can be acquired AFTER vanishing
        for (Entity entity : player.getWorld().getEntities()) {
            if (entity instanceof Mob mob && player.equals(mob.getTarget())) {
                mob.setTarget(null);
                mob.getPathfinder().stopPathfinding();
            }
        }

        // Hooks
        integrationManager.updateHooks(player, true);
        if (tabPluginHook != null)
            tabPluginHook.update(player, true);

        // Visibility + glow
        refreshVisibilityWithGlow(player);
    }

    public void scheduleRuleRevert(Player player, String rule, boolean originalValue, int seconds) {
        UUID playerUuid = player.getUniqueId();
        String playerName = player.getName();
        vanishScheduler.runLaterGlobal(() -> {
            // Apply rule revert to storage
            storageProvider.setRule(playerUuid, rule, originalValue);

            String msg = configManager.getLanguageManager().getMessage("rules.expired")
                    .replace("%rule%", rule).replace("%player%", playerName);

            // Try to send to target player first
            Player onlinePlayer = Bukkit.getPlayer(playerUuid);
            if (onlinePlayer != null && onlinePlayer.isOnline()) {
                messageManager.sendMessage(onlinePlayer, msg);
            } else if (proxyBridge != null && proxyBridge.isProxyDetected()
                    && !Bukkit.getOnlinePlayers().isEmpty()) {
                // Proxy is active and there is a carrier player on this server — send now.
                proxyBridge.sendPlayerMessage(playerUuid, msg);
                getLogger().info("[Vanishpp] Rule '" + rule + "' expired for " + playerName
                        + " — delivering expiry message via proxy.");
            } else {
                // No local carrier (player switched servers and this server is now empty).
                // Persist a pending notification in the shared DB; it will be delivered
                // the next time the player connects to any server in the network.
                storageProvider.setRule(playerUuid, PENDING_NOTIFY_PREFIX + rule, "true");
                getLogger().info("[Vanishpp] Rule '" + rule + "' expired for " + playerName
                        + " — no carrier available, queued delivery for next login.");
            }
        }, seconds * 20L);
    }

    @EventHandler
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        String msg = event.getMessage().toLowerCase();
        if (msg.startsWith("/op ") || msg.startsWith("/deop ") || msg.startsWith("/lp user ")
                || msg.startsWith("/luckperms user ")) {
            vanishScheduler.runLaterGlobal(() -> {
                for (UUID uuid : vanishedPlayers) {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null)
                        refreshVisibilityWithGlow(p);
                }
            }, 10L);
        }
    }

    public boolean isWarningIgnored(Player player) {
        return ignoredWarningPlayers.contains(player.getUniqueId());
    }

    // ── New feature getters ──────────────────────────────────────────────────

    public WorldGuardHook getWorldGuardHook() {
        return worldGuardHook;
    }

    public VanishZoneManager getVanishZoneManager() {
        return vanishZoneManager;
    }

    public LuckPermsHook getLuckPermsHook() {
        return luckPermsHook;
    }

    public WebhookManager getWebhookManager() {
        return webhookManager;
    }

    public net.thecommandcraft.vanishpp.hooks.FloodgateHook getFloodgateHook() {
        return floodgateHook;
    }

    public boolean isIncognito(Player player) {
        return incognitoNames.containsKey(player.getUniqueId());
    }

    public String getIncognitoName(UUID uuid) {
        return incognitoNames.get(uuid);
    }

    public String getVanishReason(UUID uuid) {
        return vanishReasons.get(uuid);
    }

    /** Returns the epoch-ms timestamp when this player entered vanish, or -1 if not vanished. */
    public long getVanishStartTime(UUID uuid) {
        return vanishStartTimes.getOrDefault(uuid, -1L);
    }

    /** Updates the vanish reason for an already-vanished player (API support). */
    public void setVanishReason(UUID uuid, String reason) {
        if (reason == null || reason.isEmpty()) {
            vanishReasons.remove(uuid);
        } else {
            vanishReasons.put(uuid, reason);
        }
    }

    /**
     * Returns ms remaining on a timed vanish, or -1 if the vanish is not timed or expired.
     */
    public long getTimedRemaining(UUID uuid) {
        Long expiry = timedVanishExpiry.get(uuid);
        if (expiry == null) return -1L;
        long remaining = expiry - System.currentTimeMillis();
        return remaining > 0 ? remaining : -1L;
    }

    /**
     * Schedules an automatic unvanish after {@code seconds}. Stores the expiry time
     * so action bars and scoreboards can show the countdown.
     */
    public void scheduleTimedVanish(Player player, int seconds) {
        UUID uuid = player.getUniqueId();
        long expiry = System.currentTimeMillis() + seconds * 1000L;
        timedVanishExpiry.put(uuid, expiry);
        String msg = configManager.getLanguageManager().getMessage("timed-vanish.set")
                .replace("%seconds%", String.valueOf(seconds));
        messageManager.sendMessage(player, msg);
        vanishScheduler.runLaterGlobal(() -> {
            if (!isVanished(uuid)) {
                timedVanishExpiry.remove(uuid);
                return;
            }
            timedVanishExpiry.remove(uuid);
            Player online = Bukkit.getPlayer(uuid);
            if (online != null && online.isOnline()) {
                unvanishPlayer(online, online);
                messageManager.sendMessage(online,
                        configManager.getLanguageManager().getMessage("timed-vanish.expired"));
            }
        }, seconds * 20L);
    }

    /** Cancels any pending timed vanish for {@code uuid}. */
    public void cancelTimedVanish(UUID uuid) {
        timedVanishExpiry.remove(uuid);
    }

    // ── Rate limit helpers ───────────────────────────────────────────────────

    /** Returns true if the player is rate-limited and should be denied a toggle. Sends the message if blocked. */
    public boolean checkAndRecordToggleRateLimit(Player player) {
        if (!configManager.rateLimitEnabled) return false;
        if (player.hasPermission("vanishpp.ratelimit.bypass")) return false;
        Long last = vanishToggleCooldowns.get(player.getUniqueId());
        long now = System.currentTimeMillis();
        if (last != null && (now - last) < configManager.rateLimitSeconds * 1000L) {
            messageManager.sendMessage(player,
                    configManager.getLanguageManager().getMessage("rate-limit.blocked"));
            return true;
        }
        vanishToggleCooldowns.put(player.getUniqueId(), now);
        return false;
    }

    // ── Anti-combat vanish helpers ────────────────────────────────────────────

    /** Records that {@code player} was involved in PvP combat right now. */
    public void recordPvpCombat(UUID uuid) {
        pvpCombatTimestamps.put(uuid, System.currentTimeMillis());
    }

    /** Records that {@code player} was involved in PvE combat right now. */
    public void recordPveCombat(UUID uuid) {
        pveCombatTimestamps.put(uuid, System.currentTimeMillis());
    }

    /**
     * Returns the number of seconds remaining on a combat cooldown, or 0 if none.
     * Returns the longer of PvP and PvE remaining cooldowns.
     */
    public int getCombatCooldownRemaining(Player player) {
        if (!configManager.combatVanishEnabled) return 0;
        if (player.hasPermission("vanishpp.combat.bypass")) return 0;
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long pvpRemaining = 0, pveRemaining = 0;
        Long pvp = pvpCombatTimestamps.get(uuid);
        if (pvp != null) {
            long elapsed = (now - pvp) / 1000L;
            pvpRemaining = Math.max(0, configManager.combatPvpCooldown - elapsed);
        }
        Long pve = pveCombatTimestamps.get(uuid);
        if (pve != null) {
            long elapsed = (now - pve) / 1000L;
            pveRemaining = Math.max(0, configManager.combatPveCooldown - elapsed);
        }
        return (int) Math.max(pvpRemaining, pveRemaining);
    }

    /** Returns "pvp", "pve", or null indicating which cooldown type is primary. */
    public String getCombatCooldownType(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long pvpRemaining = 0, pveRemaining = 0;
        Long pvp = pvpCombatTimestamps.get(uuid);
        if (pvp != null) pvpRemaining = Math.max(0, configManager.combatPvpCooldown - (now - pvp) / 1000L);
        Long pve = pveCombatTimestamps.get(uuid);
        if (pve != null) pveRemaining = Math.max(0, configManager.combatPveCooldown - (now - pve) / 1000L);
        if (pvpRemaining == 0 && pveRemaining == 0) return null;
        return pvpRemaining >= pveRemaining ? "pvp" : "pve";
    }

    // ── Partial visibility helpers ────────────────────────────────────────────

    public boolean isSemiVanished(UUID uuid) {
        return partialVanishWhitelist.containsKey(uuid);
    }

    public Set<UUID> getPartialVisibilityWhitelist(UUID uuid) {
        return partialVanishWhitelist.getOrDefault(uuid, Set.of());
    }

    /** Enter partial-vanish mode: visible to {@code whitelist}, hidden from everyone else. */
    public void setPartialVisibility(Player player, Set<UUID> whitelist) {
        if (whitelist.isEmpty()) {
            partialVanishWhitelist.remove(player.getUniqueId());
        } else {
            partialVanishWhitelist.put(player.getUniqueId(), new java.util.HashSet<>(whitelist));
        }
        updateVanishVisibility(player);
    }

    /** Removes any partial-visibility whitelist for this player (returns to full vanish). */
    public void clearPartialVisibility(UUID uuid) {
        partialVanishWhitelist.remove(uuid);
    }

    public void setWarningIgnored(Player player, boolean ignored) {
        if (ignored) {
            ignoredWarningPlayers.add(player.getUniqueId());
            storageProvider.addAcknowledgement(player.getUniqueId(), "protocol-lib-warning");
        } else {
            ignoredWarningPlayers.remove(player.getUniqueId());
        }
    }
}