package net.thecommandcraft.vanishpp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.thecommandcraft.vanishpp.commands.*;
import net.thecommandcraft.vanishpp.config.*;
import net.thecommandcraft.vanishpp.listeners.*;
import net.thecommandcraft.vanishpp.hooks.*;
import net.thecommandcraft.vanishpp.utils.*;
import net.thecommandcraft.vanishpp.storage.*;
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

    public final Map<UUID, String> pendingChatMessages = new HashMap<>();
    private final Map<UUID, Long> actionBarPausedUntil = new HashMap<>();
    private boolean hasProtocolLib = false;
    private List<String> startupWarnings = new ArrayList<>();
    /** Blocks currently being silently opened by a vanished player — suppress animation/sound packets for these.
     *  Key format: "x,y,z" */
    public final Set<String> silentlyOpenedBlocks = new HashSet<>();

    @Override
    public void onEnable() {
        // 0. Folia Detection
        boolean isFolia = false;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionScheduler");
            isFolia = true;
        } catch (ClassNotFoundException ignored) {
        }

        if (isFolia) {
            this.vanishScheduler = new FoliaSchedulerBridge(this);
            getLogger().info("Folia environment detected. Using Regional Scheduler.");
        } else {
            this.vanishScheduler = new BukkitSchedulerBridge(this);
            getLogger().info("Standard Bukkit/Paper environment detected. Using Legacy Scheduler.");
        }

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
                getLogger().warning("ProtocolLib found but failed to hook: " + e.getMessage());
                this.hasProtocolLib = false;
            }
        } else {
            this.hasProtocolLib = false;
            getLogger().warning("ProtocolLib NOT found! Advanced features (Tab scrubbing) disabled.");
        }

        // Run config sanity checks after all hooks are resolved
        this.startupWarnings = new StartupChecker(this).run();
        for (String w : startupWarnings) {
            getLogger().warning("[Setup Check] " + w);
        }

        setupTeams();

        // 4. Register Commands
        this.getCommand("vanish").setExecutor(new VanishCommand(this));
        this.getCommand("vperms").setExecutor(new VpermsCommand(this));
        this.getCommand("vanishrules").setExecutor(new VanishRulesCommand(this));
        this.getCommand("vanishchat").setExecutor(new VanishChatCommand(this));
this.getCommand("vanishignore").setExecutor(new VanishIgnoreCommand(this));
        this.getCommand("vanishlist").setExecutor(new VanishListCommand(this));
        this.getCommand("vanishhelp").setExecutor(new VanishHelpCommand(this));
        this.getCommand("vanishconfig").setExecutor(new VanishConfigCommand(this));
        this.getCommand("vack").setExecutor(new VanishAckCommand(this));
        this.getCommand("vanishreload").setExecutor(new VanishReloadCommand(this));

        // 5. Register Listeners
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        getServer().getPluginManager().registerEvents(this, this); // Preprocess listener

        try {
            new MobAiManager(this).register();
        } catch (Throwable e) {
            getLogger().warning("Could not register Mob AI Manager: " + e.getMessage());
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

        try {
            this.pluginHider = new PluginHider(this);
            this.pluginHider.register();
        } catch (Throwable e) {
            getLogger().warning("Failed to initialize Plugin Hider: " + e.getMessage());
        }

        startActionBarTask();
        startSyncTask();

        // 7. Restore Player State
        this.vanishedPlayers = storageProvider.getVanishedPlayers();
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

    public void reloadPluginConfig() {
        configManager.load();

        // Refresh action bar state
        if (vanishScheduler != null) {
            vanishScheduler.cancelAllTasks();
            startActionBarTask();
            startSyncTask();
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

    public List<String> getStartupWarnings() {
        return startupWarnings;
    }

    // --- CORE LOGIC ---
    private void setupTeams() {
        Scoreboard mainScoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        this.vanishTeam = mainScoreboard.getTeam("Vanishpp_Vanished");
        if (this.vanishTeam == null)
            this.vanishTeam = mainScoreboard.registerNewTeam("Vanishpp_Vanished");

        refreshTeamPrefix();
        vanishTeam.setCanSeeFriendlyInvisibles(true);
        vanishTeam.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
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

    private void startActionBarTask() {
        vanishScheduler.runTimerGlobal(() -> {
            if (!configManager.actionBarEnabled) return;
            long now = System.currentTimeMillis();
            for (UUID uuid : vanishedPlayers) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && p.isOnline()) {
                    long pausedUntil = actionBarPausedUntil.getOrDefault(uuid, 0L);
                    if (now > pausedUntil) {
                        p.sendActionBar(messageManager.parse(configManager.actionBarText, p));
                    }
                }
            }
        }, 0L, 20L);
    }

    public void triggerActionBarWarning(Player p, Component warning) {
        actionBarPausedUntil.put(p.getUniqueId(), System.currentTimeMillis() + 2000);
        p.sendActionBar(warning);
    }

    private void startSyncTask() {
        vanishScheduler.runTimerGlobal(() -> {
            for (UUID uuid : vanishedPlayers) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && p.isOnline())
                    updateVanishVisibility(p);
            }
        }, 20L, 20L);
    }

    public void applyVanishEffects(Player player) {
        vanishedPlayers.add(player.getUniqueId());
        vanishTeam.addEntry(player.getName());
        player.setMetadata("vanished", new FixedMetadataValue(this, true));

        if (configManager.vanishTabPrefix != null && !configManager.vanishTabPrefix.isEmpty()) {
            player.playerListName(messageManager.parse(configManager.vanishTabPrefix + player.getName(), player));
        }

        if (configManager.disableBlockTriggering)
            try {
                player.setAffectsSpawning(false);
            } catch (Throwable ignored) {
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
        updateVanishVisibility(player);

        storageProvider.setVanished(player.getUniqueId(), true);
        if (redisStorage != null)
            redisStorage.broadcastVanish(player.getUniqueId(), true);
    }

    public void removeVanishEffects(Player player) {
        vanishedPlayers.remove(player.getUniqueId());
        try {
            player.setAffectsSpawning(true);
        } catch (Throwable ignored) {
        }
        try {
            player.setSleepingIgnored(false);
        } catch (Throwable ignored) {
        }
        player.removeMetadata("vanished", this);
        player.playerListName(null);

        if (vanishTeam.hasEntry(player.getName()))
            vanishTeam.removeEntry(player.getName());

        player.setCollidable(true);

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
        updateVanishVisibility(player);

        storageProvider.setVanished(player.getUniqueId(), false);
        if (redisStorage != null)
            redisStorage.broadcastVanish(player.getUniqueId(), false);
    }

    public void vanishPlayer(Player player, CommandSender executor) {
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

    public void scheduleRuleRevert(Player player, String rule, boolean originalValue, int seconds) {
        vanishScheduler.runLaterGlobal(() -> {
            ruleManager.setRule(player, rule, originalValue);
            if (player.isOnline())
                player.sendMessage(Component.text("Temporary rule '" + rule + "' has expired.", NamedTextColor.YELLOW));
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
                        updateVanishVisibility(p);
                }
            }, 10L);
        }
    }

    public void togglePickup(Player player) {
        boolean current = ruleManager.getRule(player, RuleManager.CAN_PICKUP_ITEMS);
        ruleManager.setRule(player, RuleManager.CAN_PICKUP_ITEMS, !current);
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