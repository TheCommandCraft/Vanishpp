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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class Vanishpp extends JavaPlugin implements Listener {

    /** Rule-key prefix used to persist cross-server expiry notifications in the shared DB. */
    public static final String PENDING_NOTIFY_PREFIX = "__notify_expired__";

    private Set<UUID> vanishedPlayers;
    private Set<UUID> ignoredWarningPlayers; // concurrent — accessed from async join reconciliation

    private ConfigManager configManager;
    private volatile StorageProvider storageProvider;
    private final net.thecommandcraft.vanishpp.common.state.OrderedWrites stateWrites =
            new net.thecommandcraft.vanishpp.common.state.OrderedWrites(
                    task -> getVanishScheduler().runAsync(task), e -> getLogger().log(Level.SEVERE, "State write failed", e));
    private final Map<UUID, Long> stateVersions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> pendingStateWrites = new ConcurrentHashMap<>();
    private final Object stateDecisionLock = new Object();
    private record StateRetry(long version, Runnable work, Runnable cancel) {}
    private final Map<UUID, StateRetry> stateRetries = new ConcurrentHashMap<>();
    private final org.bukkit.NamespacedKey savedGamemodeKey = new org.bukkit.NamespacedKey(this, "saved_gamemode");
    private final RuleExpiries ruleExpiries = new RuleExpiries(this);
    private record RuleKey(UUID uuid, String rule) {}
    private record RuleDecision(boolean value, UUID token) {}
    private final Map<RuleKey, RuleDecision> pendingRules = new ConcurrentHashMap<>();
    private volatile boolean stopping;
    private record ProxyJoin(boolean previouslyHidden, long version, boolean invisible, boolean collidable) {}
    private final Map<UUID, ProxyJoin> proxyJoins = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> proxyJoinAnswers = new ConcurrentHashMap<>();
    private RedisStorage redisStorage;
    private PermissionManager permissionManager;
    private RuleManager ruleManager;
    private IntegrationManager integrationManager;
    private TabPluginHook tabPluginHook;
    private UpdateChecker updateChecker;
    private PluginHider pluginHider;
    private MessageManager messageManager;

    public final net.thecommandcraft.vanishpp.utils.MessageRecipients messageRecipients = new net.thecommandcraft.vanishpp.utils.MessageRecipients();
    private final Map<UUID, Integer> publishedVisibilityLevels = new ConcurrentHashMap<>();
    public final net.thecommandcraft.vanishpp.utils.VoiceAudience voiceAudience = new net.thecommandcraft.vanishpp.utils.VoiceAudience(this);
    private Team vanishTeam;
    private final net.thecommandcraft.vanishpp.utils.ViewerTeams viewerTeams = new net.thecommandcraft.vanishpp.utils.ViewerTeams(this);
    private VanishScheduler vanishScheduler;
    private VanishScoreboard vanishScoreboard;
    private YamlConfiguration scoreboardConfig;

    public final Map<UUID, String> pendingChatMessages = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Long> actionBarPausedUntil = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Component> actionBarWarningComponent = new java.util.concurrent.ConcurrentHashMap<>();
    private boolean hasProtocolLib = false;
    private boolean hasPaperApi = false;
    private ProtocolLibManager protocolLibManager;
    private List<StartupChecker.Warning> startupWarnings = new ArrayList<>();
    public volatile boolean downgradeDetected = false;
    public String downgradeFromVersion = null;
    /** Blocks currently being silently opened by a vanished player — suppress animation/sound packets for these.
     *  Key format: "x,y,z" */
    public final net.thecommandcraft.vanishpp.utils.SilentContainerSessions silentContainers = new net.thecommandcraft.vanishpp.utils.SilentContainerSessions();
    public final Set<String> silentlyOpenedBlocks = silentContainers.blocks();

    // ── Proxy support ─────────────────────────────────────────────────────────
    private ProxyBridge proxyBridge;
    private ProxyConfigCache proxyConfigCache;

    // ── New feature state ────────────────────────────────────────────────────
    /** Spectator follow: follower UUID → target UUID */
    public final Map<UUID, UUID> spectateFollowTargets = new ConcurrentHashMap<>();
    /** Spectator origin locations to teleport back to on stop */
    public final Map<UUID, Location> spectateOrigins = new ConcurrentHashMap<>();
    /**
     * Single source of truth for "gamemode to restore" across every mechanism that can force
     * a player into an override gamemode while vanished: /vspec, /vfollow, the double-shift
     * spectator toggle, and the pre-vanish gamemode snapshot. Previously these were 3
     * independent, uncoordinated stores (this map for /vspec+/vfollow only, plus two separate
     * player-metadata keys) that could clobber each other's saved value when more than one
     * was active for the same player - see saveGamemodeForRestore()/restoreSavedGamemode().
     */
    public final Map<UUID, GameMode> pendingGamemodeRestore = new ConcurrentHashMap<>();
    /** Incognito mode: player UUID → fake name */
    public final Map<UUID, String> incognitoNames = new ConcurrentHashMap<>();
    private record OriginalIdentity(Component display, Component tab) {}
    private final Map<UUID, OriginalIdentity> originalIdentities = new ConcurrentHashMap<>();

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

    @Override
    public void onLoad() {
        // WorldGuard 7.0.12+ locks the flag registry before onEnable() runs.
        // Flags must be registered here, during the load phase.
        if (getServer().getPluginManager().getPlugin("WorldGuard") != null) {
            try {
                net.thecommandcraft.vanishpp.hooks.WorldGuardHook.registerFlags();
            } catch (Throwable e) {
                getLogger().warning("WorldGuard flag pre-registration failed: " + e.getMessage());
            }
        }
    }

    @Override
    public void onEnable() {
        // 0. Extract language files (ensure external language files exist before loading config)
        extractLanguageFiles();

        // 0b. Folia Detection
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

        // 0c. Platform & Version Compatibility Checks (console only)
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
                getLogger().info("WorldGuard integration enabled.");
            } catch (Throwable e) {
                getLogger().warning("WorldGuard found but hook failed: " + e.getMessage());
                this.worldGuardHook = null;
            }
        }
        this.webhookManager = new WebhookManager(this);
        this.vanishZoneManager = new VanishZoneManager(this);
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
        registerCommand("vinfo", (sender, command, label, args) ->
                getCommand("vanish").execute(sender, "vanish", new String[]{"info"}));
        registerCommand("vzone", new VanishZoneCommand(this));
        registerCommand("vincognito", new IncognitoCommand(this));
        registerCommand("vdowngrade", new net.thecommandcraft.vanishpp.commands.DowngradeCommand(this));

        // Scoreboard
        java.io.File scoreboardFile = new java.io.File(getDataFolder(), "scoreboards.yml");
        if (!scoreboardFile.exists())
            saveResource("scoreboards.yml", false);
        scoreboardConfig = YamlConfiguration.loadConfiguration(scoreboardFile);
        this.vanishScoreboard = new VanishScoreboard(this);
        vanishScoreboard.reload();

        // 5. Register Listeners
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        getServer().getPluginManager().registerEvents(this, this); // Preprocess listener
        getServer().getPluginManager().registerEvents(new VanishWandListener(this), this);
        getServer().getPluginManager().registerEvents(new IncognitoListener(this), this);
        getServer().getPluginManager().registerEvents(new VanishZoneListener(this), this);
        net.thecommandcraft.vanishpp.listeners.MobAiManager mobAiManager =
                new net.thecommandcraft.vanishpp.listeners.MobAiManager(this);
        getServer().getPluginManager().registerEvents(mobAiManager, this);
        mobAiManager.register();
        if (worldGuardHook != null) {
            getServer().getPluginManager().registerEvents(new WorldGuardVanishListener(this), this);
        }


        boolean hasVoiceChat = Bukkit.getPluginManager().getPlugin("voicechat") != null
                || Bukkit.getPluginManager().getPlugin("SimpleVoiceChat") != null;
        if (hasVoiceChat && configManager.voiceChatEnabled) {
            new VoiceChatHook(this);
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

        // 7. Restore Player State
        this.vanishedPlayers = ConcurrentHashMap.newKeySet();
        this.ignoredWarningPlayers = ConcurrentHashMap.newKeySet();
        try { this.vanishedPlayers.addAll(storageProvider.getVanishedPlayers()); }
        catch (RuntimeException e) { getLogger().warning("Initial state read failed; logins will retry storage: " + e.getMessage()); }
        try {
            for (UUID uuid : storageProvider.getAllKnownPlayers()) {
                if (storageProvider.hasAcknowledged(uuid, "protocol-lib-warning")) ignoredWarningPlayers.add(uuid);
            }
            restoreRuleExpiries();
        } catch (RuntimeException e) { getLogger().warning("Stored preferences and deadlines will load when SQL recovers: " + e.getMessage()); }

        for (UUID uuid : vanishedPlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                restoreVanishEffects(p);
                integrationManager.updateHooks(p, true);
                if (tabPluginHook != null)
                    tabPluginHook.update(p, true);
                updateVanishVisibility(p);
            }
        }

        if (configManager.getConfig().getBoolean("proxy.enabled", false)) {
            for (Player player : Bukkit.getOnlinePlayers()) vanishScheduler.runEntity(player, () -> {
                holdProxyJoin(player);
                proxyBridge.requestJoinState(player, hidden -> vanishScheduler.runEntity(player, () -> {
                    if (player.isOnline() && isEnabled()) completeProxyJoin(player, hidden);
                }, () -> abortProxyJoin(player.getUniqueId())));
            }, null);
        }

        getLogger().info("Vanish++ " + getDescription().getVersion() + " enabled.");
    }

    /**
     * Copies language files from inside the JAR to the languages/ folder in the
     * plugin data directory. Existing files are not overwritten, preserving any
     * user customizations.
     */
    private void extractLanguageFiles() {
        File langDir = new File(getDataFolder(), "languages");
        if (!langDir.exists()) {
            if (!langDir.mkdirs()) {
                getLogger().warning("Failed to create languages directory.");
                return;
            }
        }

        // GUI text is integrated into the messages files; only extract messages
        // and the separate scoreboards files.
        String[] langFiles = {
            "messages_en-us.yml", "messages_zh-cn.yml",
            "scoreboards_en-us.yml", "scoreboards_zh-cn.yml"
        };

        for (String fileName : langFiles) {
            File target = new File(langDir, fileName);
            if (target.exists()) {
                continue; // do not overwrite existing files, preserving user customizations
            }
            try (InputStream in = getResource("languages/" + fileName)) {
                if (in == null) {
                    getLogger().warning("Missing language file in JAR: " + fileName);
                    continue;
                }
                Files.copy(in, target.toPath());
                getLogger().info("Extracted language file: " + fileName);
            } catch (IOException e) {
                getLogger().warning("Failed to extract language file " + fileName + ": " + e.getMessage());
            }
        }
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

        if (isVersionSupported(mcVersion)) {
            getLogger().info("Minecraft version: " + mcVersion + " (supported).");
        } else {
            getLogger().warning("Minecraft version: " + mcVersion + " — this version has NOT been tested with Vanish++.");
            getLogger().warning("Vanish++ is built and tested for Minecraft 1.20.6+, 1.21.x, and 26.x.x. Running on " + mcVersion);
            getLogger().warning("may cause unexpected behavior or errors. Proceed at your own risk.");
        }
    }

    /** Supported: 1.20.6+, 1.21.x, or 26.x.x (Minecraft 2026 year-based naming scheme). */
    static boolean isVersionSupported(String mcVersion) {
        String[] parts = mcVersion.split("\\.");
        if (parts.length < 2) {
            return false;
        }
        int major;
        int minor;
        try {
            major = Integer.parseInt(parts[0]);
            minor = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return false;
        }
        if (major == 26) {
            return true;
        }
        if (major == 1 && minor == 21) {
            return true;
        }
        if (major == 1 && minor == 20) {
            // Only this branch actually needs the patch component, and only here do we
            // parse it — Bukkit.getBukkitVersion() sometimes reports a non-numeric third
            // segment (e.g. Paper 26.2 reports "26.2.build.112", with no patch number at
            // all since 26.2 has no patch release). Parsing patch unconditionally for
            // every version, as this used to do, threw NumberFormatException on "build"
            // and fell through to "unsupported" even for a major==26 build that didn't
            // need patch at all. Now patch is only parsed when the 1.20.x branch needs it.
            if (parts.length < 3) {
                return false;
            }
            try {
                int patch = Integer.parseInt(parts[2]);
                return patch >= 6;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    public void reloadPluginConfig() {
        if (proxyBridge != null && proxyBridge.isProxyDetected()) {
            // In proxy mode: ask the proxy to reload its config and push it back to us.
            // The CONFIG_PUSH response will call configManager.loadFromProxySnapshot().
            proxyBridge.sendReloadRequest("vanishreload");
            // Still reload local language files / scoreboard — only the main config defers to proxy
        }
        configManager.load();

        // Keep accepted writes on their original provider until migration is ready.
        if (luckPermsHook != null) { luckPermsHook.unload(); luckPermsHook = null; }
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") != null) {
            try {
                this.luckPermsHook = new LuckPermsHook(this);
                this.luckPermsHook.load();
            } catch (Throwable ignored) { this.luckPermsHook = null; }
        }
        YamlConfiguration settings = new YamlConfiguration();
        try { settings.loadFromString(configManager.getConfig().saveToString()); }
        catch (org.bukkit.configuration.InvalidConfigurationException e) { throw new IllegalStateException(e); }
        stateWrites.submit(() -> {
            if (stopping) return;
            if (redisStorage != null) { redisStorage.shutdown(); redisStorage = null; }
            initStorage(settings);
            restoreRuleExpiries();
        });

        // Reload scoreboard config
        java.io.File sbFile = new java.io.File(getDataFolder(), "scoreboards.yml");
        if (sbFile.exists())
            scoreboardConfig = YamlConfiguration.loadConfiguration(sbFile);
        if (vanishScoreboard != null)
            vanishScoreboard.reload();

        // Refresh team prefix and resync all online vanished players
        refreshTeamPrefix();
        for (UUID uuid : vanishedPlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                resyncVanishEffects(p);
            }
        }

        // Language may have changed on reload — rebuild live bossbars so
        // already-vanished players see the new title without re-vanishing.
        if (vanishBossbar != null) vanishBossbar.refreshAll();
    }

    @Override
    public void onDisable() {
        stopping = true;
        ruleExpiries.close();
        for (StateRetry retry : stateRetries.values()) {
            retry.cancel().run();
            stateWrites.submit(retry.work());
        }
        stateRetries.clear();
        shutdownStep("pending state writes", stateWrites::close);
        for (UUID uuid : new HashSet<>(vanishStartTimes.keySet())) {
            Long start = vanishStartTimes.get(uuid);
            if (start != null && storageProvider != null) {
                shutdownStep("final session for " + uuid, () -> {
                    long duration = System.currentTimeMillis() - start;
                    if (duration > 0) storageProvider.recordVanishSession(uuid, duration);
                    vanishStartTimes.remove(uuid, start);
                });
            }
        }
        if (proxyBridge != null) shutdownStep("proxy bridge", proxyBridge::shutdown);
        if (redisStorage != null) shutdownStep("Redis subscriber", redisStorage::shutdown);
        if (storageProvider != null) shutdownStep("storage", storageProvider::shutdown);
        if (vanishScheduler != null) shutdownStep("scheduler", vanishScheduler::cancelAllTasks);
        for (Player player : Bukkit.getOnlinePlayers()) {
            shutdownStep("identity for " + player.getUniqueId(), () -> {
                if (isIncognito(player)) setIncognitoName(player, null);
            });
        }
        shutdownStep("viewer teams", viewerTeams::close);
        shutdownStep("voice audience", voiceAudience::close);
        if (vanishTeam != null) shutdownStep("vanish team", vanishTeam::unregister);
        if (integrationManager != null) shutdownStep("integrations", integrationManager::unregister);
        if (luckPermsHook != null) shutdownStep("LuckPerms", luckPermsHook::unload);
        if (vanishScoreboard != null) shutdownStep("scoreboards", vanishScoreboard::shutdown);
    }

    private void shutdownStep(String name, Runnable action) {
        try { action.run(); }
        catch (RuntimeException e) { getLogger().log(java.util.logging.Level.SEVERE, "Failed to shut down " + name, e); }
    }

    private void initStorage() { initStorage(configManager.getConfig()); }

    private void initStorage(org.bukkit.configuration.file.FileConfiguration settings) {
        String type = settings.getString("storage.type", "YAML").toUpperCase();
        if (type.equals("YAML") && storageProvider instanceof YamlStorage) {
            configureRedis(settings);
            return; // One live owner of data.yml; reload does not replace its in-memory snapshot.
        }
        StorageProvider newProvider;
        if (type.equals("MYSQL") || type.equals("POSTGRESQL")) {
            newProvider = new SqlStorage(this, type, settings);
        } else {
            newProvider = new YamlStorage(this);
        }

        boolean ready = false;
        try {
            newProvider.init();
            ready = true;
        } catch (Exception e) {
            getLogger().severe("FAILED TO INITIALIZE STORAGE: " + e.getMessage());
            if (storageProvider != null) {
                newProvider.shutdown();
                getLogger().warning("Keeping the previous storage provider.");
                return;
            }
            if (newProvider instanceof SqlStorage) {
                getLogger().warning("Keeping the configured SQL provider; state reads will retry initialization.");
            } else throw new IllegalStateException("Storage initialization failed", e);
        }

        if (ready && newProvider instanceof SqlStorage sql && sql.writeSuspended) {
            downgradeDetected = true;
            downgradeFromVersion = sql.dbVersion;
            getLogger().severe("╔═══════════════════════════════════════════════════╗");
            getLogger().severe("║         VANISH++ — DOWNGRADE DETECTED             ║");
            getLogger().severe("║                                                   ║");
            getLogger().severe("║  DB was last written by Vanish++ " + sql.dbVersion + "         ║");
            getLogger().severe("║  You are running Vanish++ " + getDescription().getVersion() + "              ║");
            getLogger().severe("║                                                   ║");
            getLogger().severe("║  All DB WRITES are SUSPENDED to protect data.     ║");
            getLogger().severe("║  Reads still work — vanish state loads on join.  ║");
            getLogger().severe("║                                                   ║");
            getLogger().severe("║  In-game: /vdowngrade info|allow|reset            ║");
            getLogger().severe("╚═══════════════════════════════════════════════════╝");
            startDowngradeAlertTask();
        }

        // Migrate data from old storage if the new storage is empty and another source has data
        try { if (ready) migrateIfNeeded(newProvider, type); }
        catch (RuntimeException e) {
            newProvider.shutdown();
            throw e;
        }

        StorageProvider previous = this.storageProvider;
        this.storageProvider = newProvider;
        if (previous != null && previous != newProvider) previous.shutdown();

        configureRedis(settings);
    }

    private void configureRedis(org.bukkit.configuration.file.FileConfiguration settings) {
        if (settings.getBoolean("storage.redis.enabled", false)) {
            this.redisStorage = new RedisStorage(this, settings);
            this.redisStorage.init();
        }
    }

    public void storageRecovered(StorageProvider provider) {
        if (provider != storageProvider || stopping) return;
        submitStorageWork(() -> {
            if (provider != storageProvider || stopping) return;
            if (provider instanceof SqlStorage sql && sql.isRecovering()) {
                sql.finishRecovery(() -> restoreStorage(provider));
            } else {
                try { restoreStorage(provider); }
                catch (RuntimeException e) {
                    getLogger().warning("Storage recovery remains pending: " + e.getMessage());
                    restoreRuleExpiries();
                }
            }
        });
    }

    private void restoreStorage(StorageProvider provider) {
        migrateIfNeeded(provider, "SQL");
        ruleExpiries.restoreNow(provider);
        Set<UUID> acknowledged = new HashSet<>();
        for (UUID uuid : provider.getAllKnownPlayers())
            if (provider.getRecoveryAcknowledgements(uuid).contains("protocol-lib-warning")) acknowledged.add(uuid);
        if (provider == storageProvider && !stopping) ignoredWarningPlayers.addAll(acknowledged);
    }

    public void submitStorageWork(Runnable work) { if (!stopping) stateWrites.submit(work); }

    private void migrateIfNeeded(StorageProvider target, String targetType) {
        if (!target.getAllKnownPlayers().isEmpty()) return;
        Runnable copy = () -> migrateEmptyTarget(target, targetType);
        if (target instanceof SqlStorage sql) sql.migrateAtomically(copy);
        else if (target instanceof YamlStorage yaml) yaml.migrateAtomically(copy);
        else copy.run();
    }

    private void migrateEmptyTarget(StorageProvider target, String targetType) {
        if (!target.getAllKnownPlayers().isEmpty()) return;
        StorageProvider previous = storageProvider == target ? null : storageProvider;
        boolean owned = false;
        if (previous == null && !targetType.equals("YAML")) {
            YamlStorage yaml = new YamlStorage(this);
            yaml.init();
            previous = yaml;
            owned = true;
        }
        if (previous == null) return;
        StorageProvider source = previous;
        try {
            Runnable copy = () -> copyStoredData(source, target);
            if (source instanceof SqlStorage sql) sql.readForMigration(copy);
            else copy.run();
        } finally { if (owned) source.shutdown(); }
    }

    private void copyStoredData(StorageProvider source, StorageProvider target) {
        getLogger().info("Migrating storage data from " + source.getClass().getSimpleName() + " → " + target.getClass().getSimpleName() + "...");
        int count = 0;
        for (UUID uuid : source.getAllKnownPlayers()) {
            // Vanish state
            if (source.isVanished(uuid)) target.setVanished(uuid, true);
            // Rules
            source.getRecoveryRules(uuid).entrySet().stream()
                    .sorted(java.util.Comparator.comparing(e -> e.getKey().startsWith(StorageProvider.EXPIRY_PREFIX)))
                    .forEach(e -> target.setRule(uuid, e.getKey(), e.getValue()));
            // Vanish level
            int level = source.getVanishLevel(uuid);
            if (level != 1) target.setVanishLevel(uuid, level);
            // Acknowledgements
            source.getRecoveryAcknowledgements(uuid).forEach(id -> target.addAcknowledgement(uuid, id));
            target.setAutoVanishOnJoin(uuid, source.getAutoVanishOnJoin(uuid));
            for (String preset : source.listRulePresets(uuid))
                target.saveRulePreset(uuid, preset, source.loadRulePreset(uuid, preset));
            target.setStats(uuid, source.getStats(uuid));
            count++;
        }
        source.copyHistoryTo(target);
        getLogger().info("Storage migration complete: " + count + " player(s) migrated.");
    }

    public void handleNetworkVanishSync(UUID uuid, boolean vanish) {
        if (stopping) return;
        // An answered join still waiting on storage keeps its provisional hide; the newest decision applies when it lifts.
        if (proxyJoins.containsKey(uuid) && proxyJoinAnswers.computeIfPresent(uuid, (key, old) -> vanish) != null) return;
        long decisionVersion = beginStateDecision(uuid);
        stateWrites.submit(() -> persistState(uuid, vanish, decisionVersion));
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
                applyVanishEffects(p, false);
                integrationManager.updateHooks(p, true);
                if (tabPluginHook != null)
                    tabPluginHook.update(p, true);
                updateVanishVisibility(p);
            }
        } else {
            vanishedPlayers.remove(uuid);
            if (p != null && p.isOnline()) {
                removeVanishEffects(p, false);
            }
        }
    }

    /** Reconciliation restores effects without publishing a new user decision. */
    public long stateVersion(UUID uuid) { return stateVersions.getOrDefault(uuid, 0L); }

    private long beginStateDecision(UUID uuid) {
        synchronized (stateDecisionLock) {
            long version = stateVersions.merge(uuid, 1L, Long::sum);
            pendingStateWrites.put(uuid, version);
            StateRetry retry = stateRetries.remove(uuid);
            if (retry != null) retry.cancel().run();
            return version;
        }
    }

    public boolean hasPendingStateWrite(UUID uuid) { return pendingStateWrites.containsKey(uuid); }

    private void persistState(UUID uuid, boolean hidden, long version) {
        persistState(uuid, hidden, version, () -> {}, 5L, false);
    }

    private void persistState(UUID uuid, boolean hidden, long version, Runnable completed, long delay, boolean retry) {
        if (retry && !Objects.equals(pendingStateWrites.get(uuid), version)) return;
        StorageProvider provider = storageProvider;
        try {
            provider.setVanished(uuid, hidden);
        } catch (RuntimeException failure) {
            getLogger().warning("State write for " + uuid + " will retry: " + failure.getMessage());
            if (!stopping && Objects.equals(pendingStateWrites.get(uuid), version)) {
                Runnable work = () -> persistState(uuid, hidden, version, completed, Math.min(600L, delay * 2L), true);
                Runnable cancel = vanishScheduler.startLater(() -> submitStorageWork(work), delay);
                stateRetries.compute(uuid, (key, old) -> {
                    if (!Objects.equals(pendingStateWrites.get(uuid), version)) { cancel.run(); return old; }
                    if (old != null) old.cancel().run();
                    return new StateRetry(version, work, cancel);
                });
            }
            return;
        }
        pendingStateWrites.remove(uuid, version);
        StateRetry pending = stateRetries.get(uuid);
        if (pending != null && pending.version() == version && stateRetries.remove(uuid, pending)) pending.cancel().run();
        completed.run();
    }

    public void reconcileSharedState(StorageProvider provider, java.util.function.BooleanSupplier active) {
        Map<UUID, Long> versions;
        Set<UUID> unsettled;
        synchronized (stateDecisionLock) {
            versions = stateVersionsSnapshot();
            unsettled = Set.copyOf(pendingStateWrites.keySet());
        }
        submitStorageWork(() -> {
            if (provider != storageProvider || !active.getAsBoolean() || isProxyAuthoritative()) return;
            try {
                Set<UUID> hidden = provider.getVanishedPlayers();
                Set<UUID> candidates = new HashSet<>(versions.keySet());
                candidates.addAll(hidden);
                candidates.removeAll(unsettled);
                vanishScheduler.runGlobal(() -> {
                    for (UUID uuid : candidates) {
                        Runnable apply = () -> {
                            if (stopping || provider != storageProvider || !active.getAsBoolean() || isProxyAuthoritative()
                                    || pendingStateWrites.containsKey(uuid) || stateVersion(uuid) != versions.getOrDefault(uuid, 0L)) return;
                            handleNetworkVanishSync(uuid, hidden.contains(uuid));
                        };
                        Player player = Bukkit.getPlayer(uuid);
                        if (player == null) apply.run();
                        else vanishScheduler.runEntity(player, apply, null);
                    }
                });
            } catch (RuntimeException e) { getLogger().warning("Shared state recovery will retry: " + e.getMessage()); }
        });
    }

    public Map<UUID, Long> stateVersionsSnapshot() {
        Map<UUID, Long> versions = new HashMap<>(stateVersions);
        for (UUID uuid : vanishedPlayers) versions.putIfAbsent(uuid, 0L);
        return versions;
    }

    public boolean isProxyJoinPending(UUID uuid) { return proxyJoins.containsKey(uuid); }

    public void holdProxyJoin(Player player) {
        UUID uuid = player.getUniqueId();
        proxyJoins.computeIfAbsent(uuid, key -> new ProxyJoin(isVanished(uuid), stateVersions.merge(uuid, 1L, Long::sum), player.isInvisible(), player.isCollidable()));
        vanishedPlayers.add(uuid);
        voiceAudience.invalidate(uuid);
        player.setMetadata("vanished", new FixedMetadataValue(this, true));
        player.setInvisible(true);
        player.setCollidable(false);
        updateVanishVisibility(player);
        for (UUID hidden : vanishedPlayers) {
            Player subject = Bukkit.getPlayer(hidden);
            if (subject != null && !permissionManager.canSee(player, subject)) player.hidePlayer(this, subject);
        }
    }

    /** Records the proxy's answer for a held join; network decisions arriving later replace it until the hold lifts. */
    public void answerProxyJoin(UUID uuid, boolean hidden) {
        if (proxyJoins.containsKey(uuid)) proxyJoinAnswers.put(uuid, hidden);
    }

    /** The decision a held join resolves to, or null once a local decision has superseded the hold. */
    public Boolean heldJoinDecision(UUID uuid, boolean answer) {
        ProxyJoin pending = proxyJoins.get(uuid);
        if (pending == null || pending.version() != stateVersion(uuid)) return null;
        return proxyJoinAnswers.getOrDefault(uuid, answer);
    }

    public void completeProxyJoin(Player player, boolean hidden) { completeProxyJoin(player, hidden, true); }

    /** Lifts a join hold; {@code persist} is false when {@code hidden} was just read from storage. */
    public void completeProxyJoin(Player player, boolean hidden, boolean persist) {
        UUID uuid = player.getUniqueId();
        Boolean answered = proxyJoinAnswers.remove(uuid);
        if (answered != null) { hidden = answered; persist = true; }
        ProxyJoin pending = proxyJoins.remove(uuid);
        if (pending == null || pending.version() != stateVersion(uuid)) return;
        boolean decision = hidden;
        if (persist) {
            long decisionVersion = beginStateDecision(uuid);
            stateWrites.submit(() -> persistState(uuid, decision, decisionVersion));
        }
        if (decision) applyVanishEffects(player, false);
        else if (pending.previouslyHidden()) removeVanishEffects(player, false);
        else {
            vanishedPlayers.remove(uuid);
            voiceAudience.invalidate(uuid);
            player.removeMetadata("vanished", this);
            player.setInvisible(pending.invisible());
            player.setCollidable(pending.collidable());
            updateVanishVisibility(player);
        }
    }

    public void abortProxyJoin(UUID uuid) {
        proxyJoinAnswers.remove(uuid);
        ProxyJoin pending = proxyJoins.remove(uuid);
        if (pending != null && !pending.previouslyHidden() && pending.version() == stateVersion(uuid)) vanishedPlayers.remove(uuid);
    }

    public Set<UUID> getBootstrapVanishedPlayers() {
        Set<UUID> hidden = new HashSet<>(vanishedPlayers);
        proxyJoins.forEach((uuid, pending) -> {
            if (!pending.previouslyHidden() && pending.version() == stateVersion(uuid)) hidden.remove(uuid);
        });
        return hidden;
    }

    public boolean isProxyAuthoritative() { return proxyBridge != null && proxyBridge.isProxyDetected(); }

    public void reconcileVanishState(Player player, boolean dbVanished) {
        if (isProxyJoinPending(player.getUniqueId())) return;
        if (isProxyAuthoritative()) dbVanished = isVanished(player);
        boolean memVanished = vanishedPlayers.contains(player.getUniqueId());
        if (dbVanished == memVanished) {
            // Even when state looks consistent, defensively reassert the actual visibility
            // flag against any staleness left by a crash, downgrade, or another plugin.
            if (dbVanished) resyncVanishEffects(player);
            else forceEnsureVisible(player);
            return;
        }

        if (dbVanished) {
            // Vanished on another server — apply vanish on this server
            getLogger().fine("Cross-server vanish detected for " + player.getName() + " on join — applying locally");
            applyVanishEffects(player, false);
            updateVanishVisibility(player);
            String joinMsg = configManager.getLanguageManager().getMessage("staff.silent-join")
                    .replace("%player%", player.getName());
            Component joinComp = messageManager.parse(joinMsg, player);
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (!staff.equals(player) && permissionManager.canReceiveStaffNotice(staff, player))
                    staff.sendMessage(joinComp);
            }
            Bukkit.getConsoleSender().sendMessage(joinComp);
        } else {
            // Unvanished on another server — clear stale local state
            getLogger().fine("Cross-server unvanish detected for " + player.getName() + " on join — clearing locally");
            removeVanishEffects(player, false);
        }
    }

    private void startDowngradeAlertTask() {
        vanishScheduler.runTimerGlobal(() -> {
            if (!downgradeDetected) return;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.isOp() || p.hasPermission("vanishpp.admin")) {
                    sendDowngradeWarning(p);
                }
            }
        }, 1L, 600L);
    }

    public void sendDowngradeWarning(Player player) {
        player.sendMessage(net.kyori.adventure.text.Component.text(
                "┌─────────────────────────────────────────────┐", net.kyori.adventure.text.format.NamedTextColor.RED));
        player.sendMessage(net.kyori.adventure.text.Component.text(
                "│  ⚠  VANISH++ DB DOWNGRADE DETECTED           │", net.kyori.adventure.text.format.NamedTextColor.RED,
                net.kyori.adventure.text.format.TextDecoration.BOLD));
        player.sendMessage(net.kyori.adventure.text.Component.text(
                "│  DB version: ", net.kyori.adventure.text.format.NamedTextColor.RED)
                .append(net.kyori.adventure.text.Component.text(downgradeFromVersion,
                        net.kyori.adventure.text.format.NamedTextColor.YELLOW)));
        player.sendMessage(net.kyori.adventure.text.Component.text(
                "│  Running:    ", net.kyori.adventure.text.format.NamedTextColor.RED)
                .append(net.kyori.adventure.text.Component.text(getDescription().getVersion(),
                        net.kyori.adventure.text.format.NamedTextColor.YELLOW)));
        player.sendMessage(net.kyori.adventure.text.Component.text(
                "│  All DB writes SUSPENDED — data protected.   │", net.kyori.adventure.text.format.NamedTextColor.GOLD));
        player.sendMessage(net.kyori.adventure.text.Component.text(
                "│  Run /vdowngrade info|allow|reset             │", net.kyori.adventure.text.format.NamedTextColor.WHITE));
        player.sendMessage(net.kyori.adventure.text.Component.text(
                "└─────────────────────────────────────────────┘", net.kyori.adventure.text.format.NamedTextColor.RED));
        player.showTitle(net.kyori.adventure.title.Title.title(
                net.kyori.adventure.text.Component.text("⚠ DB DOWNGRADE",
                        net.kyori.adventure.text.format.NamedTextColor.RED,
                        net.kyori.adventure.text.format.TextDecoration.BOLD),
                net.kyori.adventure.text.Component.text("/vdowngrade info|allow|reset",
                        net.kyori.adventure.text.format.NamedTextColor.YELLOW)));
    }

    public void clearDowngradeState() {
        downgradeDetected = false;
        downgradeFromVersion = null;
    }

    /**
     * Versions up to 1.1.9.1 gave vanished players an infinite, hidden invisibility potion, and it's
     * saved with the player. Removes only that one, a potion the player drank themselves stays.
     */
    private void removeLegacyInvisibility(Player player) {
        PotionEffect effect = player.getPotionEffect(PotionEffectType.INVISIBILITY);
        if (effect != null && effect.isInfinite() && !effect.hasParticles() && !effect.isAmbient())
            player.removePotionEffect(PotionEffectType.INVISIBILITY);
    }

    private void forceEnsureVisible(Player player) {
        removeLegacyInvisibility(player);
        player.setInvisible(false);
        try {
            player.setVisibleByDefault(true);
        } catch (Throwable ignored) {
            // Best-effort: older API versions lack this method, and test mocks (MockBukkit) may
            // throw UnsupportedOperationException-style errors instead of NoSuchMethodError.
        }
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.canSee(player)) online.showPlayer(this, player);
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
     * Missing entries are unknown; only explicit proxy decisions can remove them.
     * Must run on the global/main thread.
     */
    public void applyNetworkVanishState(java.util.List<NetworkVanishState> states) {
        // Absence in a fresh proxy is unknown, not an unvanish decision.
        for (NetworkVanishState state : states) handleNetworkVanishSync(state.uuid(), true);
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
        if (!(executor instanceof org.bukkit.command.TabCompleter))
            cmd.setTabCompleter((sender, command, alias, args) -> java.util.Collections.emptyList());
    }

    /** Cleans up per-player cached state. Call on player quit. */
    public void retireVisibility(Player player) {
        if (vanishTeam != null && vanishTeam.hasEntry(player.getName())) vanishTeam.removeEntry(player.getName());
        if (!hasProtocolLib) viewerTeams.retireSubject(player);
        voiceAudience.retire(player.getUniqueId());
    }

    public void resumeVisibility(Player player) {
        viewerTeams.joined(player.getUniqueId());
        voiceAudience.joined(player.getUniqueId());
        voiceAudience.refresh();
    }

    public void cleanupPlayerCache(UUID uuid) {
        abortProxyJoin(uuid);
        stateVersions.merge(uuid, 1L, Long::sum);
        if (proxyBridge != null) proxyBridge.forgetPlayer(uuid);
        viewerTeams.forget(uuid);
        voiceAudience.invalidate(uuid);
        publishedVisibilityLevels.remove(uuid);
        actionBarPausedUntil.remove(uuid);
        actionBarWarningComponent.remove(uuid);
        ignoredWarningPlayers.remove(uuid);
        playerNameCache.values().remove(uuid);
        spectateFollowTargets.remove(uuid);
        spectateOrigins.remove(uuid);
        pendingGamemodeRestore.remove(uuid);
        incognitoNames.remove(uuid);
        originalIdentities.remove(uuid);
        vanishReasons.remove(uuid);
        // Record session on quit if still vanished
        Long start = vanishStartTimes.remove(uuid);
        if (start != null) {
            long duration = System.currentTimeMillis() - start;
            if (duration > 0) {
                stateWrites.submit(() -> {
                    storageProvider.recordVanishSession(uuid, duration);
                });
            }
        }
        if (vanishScoreboard != null)
            vanishScoreboard.cleanup(uuid);
        if (vanishBossbar != null)
            vanishBossbar.cleanup(uuid);
    }

    public GameMode getPreVanishGamemodePublic(Player player) {
        GameMode saved = savedGamemode(player);
        return saved == null ? GameMode.SURVIVAL : saved;
    }

    /**
     * Save the player's current gamemode for later restoration, but only if nothing is
     * already pending - prevents one override mechanism (vspec/vfollow/double-shift/vanish)
     * from clobbering another's saved original gamemode when more than one is active at
     * once for the same player. Call before switching a player into any vanish-related
     * override gamemode.
     */
    public void saveGamemodeForRestore(Player player) {
        UUID uuid = player.getUniqueId();
        if (savedGamemode(player) == null) {
            player.getPersistentDataContainer().set(savedGamemodeKey, org.bukkit.persistence.PersistentDataType.STRING, player.getGameMode().name());
            pendingGamemodeRestore.putIfAbsent(uuid, player.getGameMode());
        }
    }

    /**
     * Restore and clear the saved gamemode. Always applies a gamemode (falls back to
     * SURVIVAL if nothing was pending, e.g. the player entered spectator via a path outside
     * Vanish++'s own tracking) so callers never leave a player stuck in SPECTATOR.
     */
    public void restoreSavedGamemode(Player player) {
        UUID uuid = player.getUniqueId();
        GameMode gm = savedGamemode(player);
        player.setGameMode(gm != null ? gm : GameMode.SURVIVAL);
        player.getPersistentDataContainer().remove(savedGamemodeKey);
        pendingGamemodeRestore.remove(uuid);
    }

    private GameMode savedGamemode(Player player) {
        UUID uuid = player.getUniqueId();
        GameMode cached = pendingGamemodeRestore.get(uuid);
        if (cached != null) return cached;
        String stored = player.getPersistentDataContainer().get(savedGamemodeKey, org.bukkit.persistence.PersistentDataType.STRING);
        if (stored == null) return null;
        GameMode saved = GameMode.valueOf(stored.toString());
        pendingGamemodeRestore.putIfAbsent(uuid, saved);
        return saved;
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
        if (!hasProtocolLib) { viewerTeams.update(player, isVanished(player)); return; }
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
                // /vspec and /vfollow own the action bar for this player while active (their
                // own periodic task drives the spectate overlay) - sending the generic text
                // here too raced with it and caused visible flicker between the two.
                if (spectateFollowTargets.containsKey(uuid) || isProxyJoinPending(uuid)) continue;
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && p.isOnline()) {
                    long pausedUntil = actionBarPausedUntil.getOrDefault(uuid, 0L);
                    if (now > pausedUntil) {
                        actionBarWarningComponent.remove(uuid);
                        p.sendActionBar(messageManager.parse(configManager.actionBarText, p));
                    } else {
                        Component warning = actionBarWarningComponent.get(uuid);
                        if (warning != null) p.sendActionBar(warning);
                    }
                }
            }
        }, 1L, 20L);
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
            voiceAudience.refresh();
            if (!hasProtocolLib) viewerTeams.refresh();
            Set<UUID> vanishedCopy = Set.copyOf(vanishedPlayers);
            List<Player> onlinePlayers = new ArrayList<>(Bukkit.getOnlinePlayers());

            for (UUID uuid : vanishedCopy) {
                Player vanished = Bukkit.getPlayer(uuid);
                if (vanished == null || !vanished.isOnline()) continue;
                if (isProxyJoinPending(uuid)) { updateVanishVisibility(vanished); continue; }
                if (proxyBridge != null && proxyBridge.isProxyDetected() && !proxyBridge.isTransferPending(uuid)) {
                    int level = permissionManager.vanishLevel(vanished);
                    Integer previous = publishedVisibilityLevels.put(uuid, level);
                    if (!java.util.Objects.equals(previous, level))
                        proxyBridge.sendVanishEvent(uuid, vanished.getName(), true, level);
                }

                // Reapply team entry + tab prefix (catches TAB plugin or scoreboard overrides)
                if (hasProtocolLib && vanishTeam != null && !vanishTeam.hasEntry(vanished.getName()))
                    vanishTeam.addEntry(vanished.getName());
                updatePlayerListName(vanished);

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
            if (proxyBridge != null) proxyBridge.retryPendingChanges();
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
                            submitStorageWork(() -> storageProvider.setRule(p.getUniqueId(), entry.getKey(), false));
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
            Map<UUID, Long> versions = stateVersionsSnapshot();
            vanishScheduler.runAsync(() -> {
                if (isProxyAuthoritative()) return;
                Set<UUID> dbVanished;
                try { dbVanished = storageProvider.getVanishedPlayers(); }
                catch (RuntimeException e) { getLogger().warning("Vanish reconciliation will retry: " + e.getMessage()); return; }
                vanishScheduler.runGlobal(() -> {
                    for (UUID uuid : offlineVanished) {
                        if (Bukkit.getPlayer(uuid) == null && stateVersion(uuid) == versions.getOrDefault(uuid, 0L)
                                && !dbVanished.contains(uuid)) vanishedPlayers.remove(uuid);
                    }
                });
            });
        }, 1200L, 1200L); // 60 seconds
    }

    public void applyVanishEffects(Player player) { applyVanishEffects(player, true); }

    public void restoreVanishEffects(Player player) { applyVanishEffects(player, false); }

    private void applyVanishEffects(Player player, boolean persist) {
        if (persist && proxyBridge != null && proxyBridge.isTransferPending(player.getUniqueId())) return;
        if (stopping) return;
        long decisionVersion = persist ? beginStateDecision(player.getUniqueId()) : stateVersion(player.getUniqueId());
        vanishedPlayers.add(player.getUniqueId());
        if (hasProtocolLib && vanishTeam != null) vanishTeam.addEntry(player.getName());
        else viewerTeams.update(player, true);
        player.setMetadata("vanished", new FixedMetadataValue(this, true));

        updatePlayerListName(player);

        if (configManager.preventSleeping)
            try {
                player.setSleepingIgnored(true);
            } catch (Throwable ignored) {
            }

        if (configManager.enableNightVision && permissionManager.hasPermission(player, "vanishpp.nightvision")) {
            player.setMetadata("vanishpp_night_vision", new FixedMetadataValue(this, true));
            player.addPotionEffect(
                    new PotionEffect(PotionEffectType.NIGHT_VISION, PotionEffect.INFINITE_DURATION, 0, false, false));
        }

        // The invisible flag alone shrinks how far mobs notice the player, the same as the
        // invisibility potion did (vanilla checks the flag, and Paper keeps it set). No potion, so
        // it doesn't show up for the player and a real one they drank isn't taken away on unvanish.
        player.setInvisible(true);

        // DO NOT change gamemode — player should remain in their original mode
        // Combat targeting is prevented by EntityTargetEvent cancellation (PlayerListener).
        // Visual head/body look-at is a separate AI goal, handled by VanishLookGoal
        // (injected via MobAiManager, replacing the vanilla LOOK_AT_PLAYER goals), which never
        // picks a vanished player.

        player.setCollidable(false);

        // ALWAYS clear existing mob targets when vanishing (regardless of mob_targeting rule)
        // The rule only controls whether new targets can be acquired AFTER vanishing
        // Each mob's target access must run on its owning region's thread (Folia safety)
        for (Entity entity : player.getWorld().getEntities()) {
            if (entity instanceof Mob mob) {
                vanishScheduler.runEntity(mob, () -> {
                    if (player.equals(mob.getTarget())) {
                        mob.setTarget(null);
                        mob.getPathfinder().stopPathfinding();
                    }
                }, null);
            }
        }

        if (configManager.enableFly && player.getGameMode() != GameMode.SPECTATOR) {
            // Store original state if not already saved
            if (!player.hasMetadata("vanishpp_pre_vanish_fly")) {
                player.setMetadata("vanishpp_pre_vanish_fly", 
                        new FixedMetadataValue(this, player.getAllowFlight()));
            }

            // Apply immediately for smooth manual vanish
            player.setAllowFlight(true);

            // Re-enforce after 1 second to override plugins like AuthMe that reset flight on login
            vanishScheduler.runLaterGlobal(() -> {
                if (player.isOnline() && isVanished(player)) {
                    if (!player.getAllowFlight()) {
                        player.setAllowFlight(true);
                    }
                }
            }, 20L);
        }

        integrationManager.updateHooks(player, true);
        if (integrationManager.getStatusbot() != null)
            integrationManager.getStatusbot().refreshStatus();
        if (tabPluginHook != null)
            tabPluginHook.update(player, true);
        refreshVisibilityWithGlow(player);

        // Instant action bar feedback — don't wait for the scheduler's next cycle
        if (configManager.actionBarEnabled) {
            player.sendActionBar(messageManager.parse(configManager.actionBarText, player));
        }

        // Track vanish start time for stats
        vanishStartTimes.putIfAbsent(player.getUniqueId(), System.currentTimeMillis());
        // Show bossbar
        if (vanishBossbar != null) vanishBossbar.show(player);

        UUID persistUuid = player.getUniqueId();
        String persistName = player.getName();
        int persistLevel = permissionManager.vanishLevel(player);
        publishedVisibilityLevels.put(persistUuid, persistLevel);
        String reason = vanishReasons.getOrDefault(persistUuid, null);
        byte[] proxyPacket = persist && proxyBridge != null
                ? proxyBridge.prepareVanishEvent(persistUuid, persistName, true, persistLevel) : null;
        if (persist) stateWrites.submit(() -> persistState(persistUuid, true, decisionVersion, () -> {
            // Add history entry
            try {
                String serverName = Bukkit.getServer().getName();
                storageProvider.addHistoryEntry(
                    net.thecommandcraft.vanishpp.storage.VanishHistoryEntry.vanish(persistUuid, persistName, serverName, reason));
            } catch (Exception ignored) {}
            if (proxyBridge != null && proxyBridge.isProxyDetected()) {
                // Proxy handles cross-server broadcast
                if (proxyPacket != null) proxyBridge.sendPacket(proxyPacket);
                else proxyBridge.sendVanishEvent(persistUuid, persistName, true, persistLevel);
            } else if (redisStorage != null) {
                redisStorage.broadcastVanish(persistUuid, true);
            }
        }, 5L, false));
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

    public void removeVanishEffects(Player player) { removeVanishEffects(player, true); }

    private void removeVanishEffects(Player player, boolean persist) {
        if (persist && proxyBridge != null && proxyBridge.isTransferPending(player.getUniqueId())) return;
        if (stopping) return;
        long decisionVersion = persist ? beginStateDecision(player.getUniqueId()) : stateVersion(player.getUniqueId());
        vanishedPlayers.remove(player.getUniqueId());
        messageRecipients.forget(player.getUniqueId());
        publishedVisibilityLevels.remove(player.getUniqueId());
        if (!hasProtocolLib) viewerTeams.update(player, false);
        try {
            player.setSleepingIgnored(false);
        } catch (Throwable ignored) {
        }
        player.removeMetadata("vanished", this);
        updatePlayerListName(player);

        if (vanishTeam != null && vanishTeam.hasEntry(player.getName()))
            vanishTeam.removeEntry(player.getName());

        player.setCollidable(true);

        // If the player is in spectator (double-shift toggle, /vspec, or /vfollow), restore
        // their pre-vanish gamemode. Also drop any active /vspec/vfollow tracking - leaving it
        // behind would let VanishFollowCommand's periodic task keep re-teleporting an already-
        // unvanished, no-longer-spectator player to the follow target every few ticks.
        // Players with vanishpp.spectator.bypass are allowed to stay in spectator (and keep
        // following/spectating) after unvanish.
        if (player.getGameMode() == GameMode.SPECTATOR
                && !permissionManager.hasPermission(player, "vanishpp.spectator.bypass")) {
            spectateFollowTargets.remove(player.getUniqueId());
            spectateOrigins.remove(player.getUniqueId());
            restoreSavedGamemode(player);
            String msg = configManager.getLanguageManager().getMessage("spectator.forced-unvanish")
                    .replace("%gamemode%", player.getGameMode().name().toLowerCase());
            messageManager.sendMessage(player, msg);
        }

        // Only handle night vision if the plugin added it
        if (player.hasMetadata("vanishpp_night_vision")) {
            player.removeMetadata("vanishpp_night_vision", this);

            // Replace INFINITE NV with a 1-tick effect that expires naturally.
            // Do NOT use removePotionEffect() — it breaks the game engine's internal
            // equipment-effect tracking and prevents equipment from re-applying NV.
            player.addPotionEffect(
                    new PotionEffect(PotionEffectType.NIGHT_VISION, 1, 0, false, false), true);

        }
        removeLegacyInvisibility(player);
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

        integrationManager.updateHooks(player, false);
        if (integrationManager.getStatusbot() != null)
            integrationManager.getStatusbot().refreshStatus();
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
        byte[] proxyPacket = persist && proxyBridge != null
                ? proxyBridge.prepareVanishEvent(persistUuid, persistNameUv, false, 1) : null;
        if (!persist && sessionDuration > 0) stateWrites.submit(() -> storageProvider.recordVanishSession(persistUuid, sessionDuration));
        if (persist) stateWrites.submit(() -> persistState(persistUuid, false, decisionVersion, () -> {
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
                if (proxyPacket != null) proxyBridge.sendPacket(proxyPacket);
                else proxyBridge.sendVanishEvent(persistUuid, persistNameUv, false, 1);
            } else if (redisStorage != null) {
                redisStorage.broadcastVanish(persistUuid, false);
            }
        }, 5L, false));
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

    /**
     * Vanish a player silently — applies all vanish effects and notifies the player,
     * but does NOT broadcast fake-quit messages or staff notifications.
     * Used for auto-vanish on join to avoid a spurious "player left" message
     * right after they connect.
     */
    public void vanishPlayerSilently(Player player) {
        if (proxyBridge != null && proxyBridge.isTransferPending(player.getUniqueId())) return;
        applyVanishEffects(player);
        if (isValidMessage(configManager.vanishMessage)) {
            player.sendMessage(messageManager.parse(configManager.vanishMessage, player));
        }
        // No fake-quit broadcast, no Discord fake-quit, no staff notification
    }

    public void vanishPlayer(Player player, CommandSender executor) {
        if (proxyBridge != null && proxyBridge.isTransferPending(player.getUniqueId())) return;
        // No gamemode snapshot needed here: vanish itself never forces spectator mode, and
        // whichever mechanism actually puts the player into spectator later (/vspec,
        // /vfollow, double-shift toggle) saves the real original gamemode itself via
        // saveGamemodeForRestore()'s putIfAbsent guard - so it's always captured at the
        // right moment regardless of vanish/spectate ordering.
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
            if (integrationManager.getStatusbot() != null) {
                integrationManager.getStatusbot().sendFakeQuit(player);
            }
        }
        notifyStaff(player, executor, true);
    }

    public void unvanishPlayer(Player player, CommandSender executor) {
        if (proxyBridge != null && proxyBridge.isTransferPending(player.getUniqueId())) return;
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
            if (integrationManager.getStatusbot() != null) {
                integrationManager.getStatusbot().sendFakeJoin(player);
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
        if (proxyBridge != null && proxyBridge.isTransferPending(player.getUniqueId())) return;
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
            if (permissionManager.canReceiveStaffNotice(p, subject))
                p.sendMessage(comp);
        }
        Bukkit.getConsoleSender().sendMessage(comp);
    }

    public void updateVanishVisibility(Player subject) {
        voiceAudience.refresh();
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
        if (hasProtocolLib && vanishTeam != null) vanishTeam.addEntry(player.getName());
        else viewerTeams.update(player, true);
        refreshTeamPrefix();
        updatePlayerListName(player);

        // Metadata
        player.setMetadata("vanished", new FixedMetadataValue(this, true));
        player.setInvisible(true);
        player.setCollidable(false);

        // Fly
        if (configManager.enableFly && player.getGameMode() != GameMode.SPECTATOR) {
            player.setAllowFlight(true);
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
            if (entity instanceof Mob mob) vanishScheduler.runEntity(mob, () -> {
                if (isVanished(player) && player.equals(mob.getTarget())) {
                    mob.setTarget(null);
                    mob.getPathfinder().stopPathfinding();
                }
            }, null);
        }

        // Hooks
        integrationManager.updateHooks(player, true);
        if (tabPluginHook != null)
            tabPluginHook.update(player, true);

        // Visibility + glow
        refreshVisibilityWithGlow(player);
    }

    public void scheduleRuleRevert(Player player, String rule, boolean originalValue, int seconds) {
        setTemporaryRule(player, rule, ruleManager.getRule(player, rule), originalValue, seconds);
    }

    public Boolean pendingRule(UUID uuid, String rule) {
        RuleDecision decision = pendingRules.get(new RuleKey(uuid, rule));
        return decision == null ? null : decision.value();
    }

    public void setRule(Player player, String rule, boolean value) {
        RuleKey key = new RuleKey(player.getUniqueId(), rule);
        RuleDecision decision = new RuleDecision(value, UUID.randomUUID());
        pendingRules.put(key, decision);
        submitStorageWork(() -> {
            try {
                storageProvider.setRule(key.uuid(), rule, value);
                ruleExpiries.cancel(key.uuid(), rule);
            } finally { pendingRules.remove(key, decision); }
        });
    }

    public void setTemporaryRule(Player player, String rule, boolean value, boolean originalValue, int seconds) {
        long deadline = System.currentTimeMillis() + Math.max(0L, (long) seconds) * 1000L;
        String token = deadline + ":" + originalValue + ":" + UUID.randomUUID();
        RuleKey key = new RuleKey(player.getUniqueId(), rule);
        RuleDecision decision = new RuleDecision(value, UUID.randomUUID());
        pendingRules.put(key, decision);
        submitStorageWork(() -> {
            try {
                StorageProvider provider = storageProvider;
                provider.setTemporaryRule(key.uuid(), rule, value, token);
                ruleExpiries.replace(key.uuid(), rule, token, provider);
            } finally { pendingRules.remove(key, decision); }
        });
    }

    private void restoreRuleExpiries() { ruleExpiries.reload(); }

    /** Called on the storage worker after a successful compare-and-expire transaction. */
    public void ruleExpired(UUID uuid, String rule, StorageProvider provider) {
        long version = stateVersion(uuid);
        vanishScheduler.runGlobal(() -> {
            if (stopping || provider != storageProvider) return;
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                vanishScheduler.runEntity(player, () -> {
                    if (!player.isOnline() || stopping || provider != storageProvider || stateVersion(uuid) != version) return;
                    String message = configManager.getLanguageManager().getMessage("rules.expired")
                            .replace("%rule%", rule).replace("%player%", player.getName());
                    messageManager.sendMessage(player, message);
                    if (isVanished(player)) resyncVanishEffects(player);
                }, null);
            } else {
                String message = configManager.getLanguageManager().getMessage("rules.expired")
                        .replace("%rule%", rule).replace("%player%", uuid.toString());
                if (proxyBridge != null && proxyBridge.isProxyDetected() && !Bukkit.getOnlinePlayers().isEmpty())
                    proxyBridge.sendPlayerMessage(uuid, message);
                else submitStorageWork(() -> {
                    if (provider == storageProvider) provider.setRule(uuid, PENDING_NOTIFY_PREFIX + rule, true);
                });
            }
        });
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

    public String setIncognitoName(Player player, String name) {
        UUID uuid = player.getUniqueId();
        String previous = incognitoNames.get(uuid);
        if (name == null) {
            incognitoNames.remove(uuid);
            OriginalIdentity original = originalIdentities.remove(uuid);
            if (original != null) {
                player.displayName(original.display());
                player.playerListName(original.tab());
            } else updatePlayerListName(player);
        } else {
            originalIdentities.putIfAbsent(uuid, new OriginalIdentity(player.displayName(), player.playerListName()));
            incognitoNames.put(uuid, name);
            player.displayName(Component.text(name));
            updatePlayerListName(player);
        }
        if (tabPluginHook != null) tabPluginHook.update(player, isVanished(player));
        return previous;
    }

    public void updatePlayerListName(Player player) {
        String alias = incognitoNames.get(player.getUniqueId());
        Component name = Component.text(alias == null ? player.getName() : alias);
        if (isVanished(player) && configManager.vanishTabPrefix != null)
            name = messageManager.parse(configManager.vanishTabPrefix, player).append(name);
        player.playerListName(name);
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
     * Returns ms remaining on a timed vanish, or -1 if the vanish is not timed.
     * Timed vanish is not yet implemented — always returns -1.
     */
    public long getTimedRemaining(UUID uuid) {
        return -1L;
    }

    public void acknowledge(UUID uuid, String notification) {
        submitStorageWork(() -> storageProvider.addAcknowledgement(uuid, notification));
    }

    public void setWarningIgnored(Player player, boolean ignored) {
        UUID uuid = player.getUniqueId();
        if (ignored) ignoredWarningPlayers.add(uuid);
        else ignoredWarningPlayers.remove(uuid);
        submitStorageWork(() -> {
            if (ignored) storageProvider.addAcknowledgement(uuid, "protocol-lib-warning");
            else storageProvider.removeAcknowledgement(uuid, "protocol-lib-warning");
        });
    }
}
