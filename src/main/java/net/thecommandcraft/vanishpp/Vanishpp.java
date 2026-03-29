package net.thecommandcraft.vanishpp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.thecommandcraft.vanishpp.commands.*;
import net.thecommandcraft.vanishpp.config.*;
import net.thecommandcraft.vanishpp.listeners.*;
import net.thecommandcraft.vanishpp.hooks.*;
import net.thecommandcraft.vanishpp.utils.*;
import net.thecommandcraft.vanishpp.storage.*;
import net.thecommandcraft.vanishpp.scoreboard.VanishScoreboard;
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

    private Set<UUID> vanishedPlayers;
    private Set<UUID> ignoredWarningPlayers;

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
    private ProtocolLibManager protocolLibManager;
    private List<StartupChecker.Warning> startupWarnings = new ArrayList<>();
    /** Blocks currently being silently opened by a vanished player — suppress animation/sound packets for these.
     *  Key format: "x,y,z" */
    public final Set<String> silentlyOpenedBlocks = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    @Override
    public void onEnable() {
        // 0. Folia Detection
        boolean isFolia = false;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionScheduler");
            isFolia = true;
        } catch (ClassNotFoundException ignored) {
        }
        // Some Folia builds rename or move the detection class — fall back to server name
        if (!isFolia) {
            isFolia = "Folia".equalsIgnoreCase(Bukkit.getName());
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

        // Scoreboard
        saveResource("scoreboards.yml", false);
        scoreboardConfig = YamlConfiguration.loadConfiguration(
                new java.io.File(getDataFolder(), "scoreboards.yml"));
        this.vanishScoreboard = new VanishScoreboard(this);
        vanishScoreboard.startUpdateTask();

        // 5. Register Listeners
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        getServer().getPluginManager().registerEvents(this, this); // Preprocess listener


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

        // 7. Restore Player State
        this.vanishedPlayers = ConcurrentHashMap.newKeySet();
        this.vanishedPlayers.addAll(storageProvider.getVanishedPlayers());
        this.ignoredWarningPlayers = new HashSet<>(); // Loaded per-player from storage on demand or pre-load

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
        configManager.load();

        // Reload scoreboard config
        java.io.File sbFile = new java.io.File(getDataFolder(), "scoreboards.yml");
        if (sbFile.exists())
            scoreboardConfig = YamlConfiguration.loadConfiguration(sbFile);
        if (vanishScoreboard != null)
            vanishScoreboard.startUpdateTask();

        // Refresh action bar state
        if (vanishScheduler != null) {
            vanishScheduler.cancelAllTasks();
            startActionBarTask();
            startSyncTask();
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
        if (vanishScoreboard != null) {
            vanishScoreboard.shutdown();
        }
    }

    private void initStorage() {
        String type = configManager.getConfig().getString("storage.type", "YAML").toUpperCase();
        if (type.equals("MYSQL") || type.equals("POSTGRESQL")) {
            this.storageProvider = new SqlStorage(this, type);
        } else {
            this.storageProvider = new YamlStorage(this);
        }

        try {
            this.storageProvider.init();
        } catch (Exception e) {
            getLogger().severe("FAILED TO INITIALIZE STORAGE: " + e.getMessage());
            getLogger().severe("Falling back to YAML storage.");
            this.storageProvider = new YamlStorage(this);
            try {
                this.storageProvider.init();
            } catch (Exception ignored) {
            }
        }

        if (configManager.getConfig().getBoolean("storage.redis.enabled", false)) {
            this.redisStorage = new RedisStorage(this);
            this.redisStorage.init();
        }
    }

    public void handleNetworkVanishSync(UUID uuid, boolean vanish) {
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
        if (vanishScoreboard != null)
            vanishScoreboard.cleanup(uuid);
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
            for (UUID uuid : Set.copyOf(vanishedPlayers)) {
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
                for (Player observer : Bukkit.getOnlinePlayers()) {
                    if (observer.equals(vanished)) continue;
                    boolean canSee = permissionManager.canSee(observer, vanished);
                    if (!canSee && observer.canSee(vanished)) {
                        observer.hidePlayer(this, vanished);
                    } else if (canSee && !observer.canSee(vanished)) {
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

        player.setCollidable(false);

        // Clear existing mob targets only if mob_targeting rule is OFF
        if (!ruleManager.getRule(player, RuleManager.MOB_TARGETING)) {
            for (Entity entity : player.getNearbyEntities(64, 64, 64)) {
                if (entity instanceof Mob mob && player.equals(mob.getTarget())) {
                    mob.setTarget(null);
                    mob.getPathfinder().stopPathfinding();
                }
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

        storageProvider.setVanished(player.getUniqueId(), true);
        if (redisStorage != null)
            redisStorage.broadcastVanish(player.getUniqueId(), true);

        if (vanishScoreboard != null)
            vanishScoreboard.onVanish(player);
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

        storageProvider.setVanished(player.getUniqueId(), false);
        if (redisStorage != null)
            redisStorage.broadcastVanish(player.getUniqueId(), false);

        if (vanishScoreboard != null)
            vanishScoreboard.onUnvanish(player);
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
            if (permissionManager.hasPermission(p, "vanishpp.see"))
                p.sendMessage(comp);
        }
        Bukkit.getConsoleSender().sendMessage(comp);
    }

    public void updateVanishVisibility(Player subject) {
        boolean isVanished = isVanished(subject);
        for (Player observer : Bukkit.getOnlinePlayers()) {
            if (observer.equals(subject))
                continue;
            boolean canSee = permissionManager.canSee(observer, subject);
            if (isVanished && !canSee)
                observer.hidePlayer(this, subject);
            else
                observer.showPlayer(this, subject);
        }
    }

    /**
     * Hide-then-show for observers to force entity respawn + metadata packets.
     * After showing, sends an explicit glow metadata packet to staff so the glow
     * appears immediately (not on next sneak/pose change).
     */
    public void refreshVisibilityWithGlow(Player subject) {
        boolean subjectVanished = isVanished(subject);
        for (Player observer : Bukkit.getOnlinePlayers()) {
            if (observer.equals(subject))
                continue;
            boolean canSee = permissionManager.canSee(observer, subject);
            if (subjectVanished && !canSee) {
                observer.hidePlayer(this, subject);
            } else {
                observer.hidePlayer(this, subject);
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

        // Clear mob targets
        if (!ruleManager.getRule(player, RuleManager.MOB_TARGETING)) {
            for (Entity entity : player.getNearbyEntities(64, 64, 64)) {
                if (entity instanceof Mob mob && player.equals(mob.getTarget())) {
                    mob.setTarget(null);
                    mob.getPathfinder().stopPathfinding();
                }
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
        vanishScheduler.runLaterGlobal(() -> {
            ruleManager.setRule(player, rule, originalValue);
            if (player.isOnline()) {
                String msg = configManager.getLanguageManager().getMessage("rules.expired")
                        .replace("%rule%", rule);
                messageManager.sendMessage(player, msg);
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

    public void setWarningIgnored(Player player, boolean ignored) {
        if (ignored) {
            ignoredWarningPlayers.add(player.getUniqueId());
            storageProvider.addAcknowledgement(player.getUniqueId(), "protocol-lib-warning");
        } else {
            ignoredWarningPlayers.remove(player.getUniqueId());
        }
    }
}