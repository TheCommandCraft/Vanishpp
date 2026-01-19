package net.thecommandcraft.vanishpp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.*;

public final class Vanishpp extends JavaPlugin implements Listener {

    private Set<UUID> vanishedPlayers;
    private Set<UUID> ignoredWarningPlayers;

    private ConfigManager configManager;
    private DataManager dataManager;
    private PermissionManager permissionManager;
    private RuleManager ruleManager;
    private IntegrationManager integrationManager;
    private Object protocolLibManager;
    private TabPluginHook tabPluginHook;
    private UpdateChecker updateChecker;

    private Team vanishTeam;
    private BukkitTask actionBarTask;
    private BukkitTask syncTask;
    private VoiceChatHook voiceChatHook;

    public final Map<UUID, String> pendingChatMessages = new HashMap<>();
    private final Map<UUID, Long> actionBarPausedUntil = new HashMap<>();
    private boolean hasProtocolLib = false;

    @Override
    public void onEnable() {
        // 1. Load Managers
        this.dataManager = new DataManager(this);
        this.dataManager.load();

        this.configManager = new ConfigManager(this);
        configManager.load();

        this.permissionManager = new PermissionManager(this);
        permissionManager.load();

        this.ruleManager = new RuleManager(this);
        ruleManager.load();

        // 2. Load Data
        this.vanishedPlayers = loadUuidSet(dataManager.getConfig().getStringList("vanished-players"));
        this.ignoredWarningPlayers = loadUuidSet(dataManager.getConfig().getStringList("ignored-warnings"));

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
            getLogger().warning("ProtocolLib NOT found! Advanced features disabled.");
        }

        setupTeams();

        // 4. Register Commands
        this.getCommand("vanish").setExecutor(new VanishCommand(this));
        this.getCommand("vperms").setExecutor(new VpermsCommand(this));
        this.getCommand("vanishrules").setExecutor(new VanishRulesCommand(this));
        this.getCommand("vanishchat").setExecutor(new VanishChatCommand(this));
        this.getCommand("vanishpickup").setExecutor(new VanishPickupCommand(this));
        this.getCommand("vanishignore").setExecutor(new VanishIgnoreCommand(this));
        this.getCommand("vanishlist").setExecutor(new VanishListCommand(this));

        // 5. Register Listeners
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        getServer().getPluginManager().registerEvents(this, this);

        try {
            new MobAiManager(this).register();
        } catch (Throwable e) {
            getLogger().warning("Could not register Mob AI Manager: " + e.getMessage());
        }

        if (Bukkit.getPluginManager().getPlugin("SimpleVoiceChat") != null && configManager.voiceChatEnabled) {
            this.voiceChatHook = new VoiceChatHook(this);
            getServer().getPluginManager().registerEvents(voiceChatHook, this);
        }

        // 6. Init Update Checker
        this.updateChecker = new UpdateChecker(this);
        this.updateChecker.check();

        startActionBarTask();
        startSyncTask();

        // 7. Restore State
        for (UUID uuid : vanishedPlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                applyVanishEffects(p);
                integrationManager.updateHooks(p, true);
                tabPluginHook.update(p, true);
                updateVanishVisibility(p);
            }
        }

        getLogger().info("Vanish++ 1.1.1 enabled.");
    }

    private void hookProtocolLib() {
        ProtocolLibManager manager = new ProtocolLibManager(this);
        manager.load();
        this.protocolLibManager = manager;
    }

    @Override
    public void onDisable() {
        if (actionBarTask != null) actionBarTask.cancel();
        if (syncTask != null) syncTask.cancel();

        for (UUID uuid : vanishedPlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                p.setInvisible(false);
                p.setSilent(false);
                p.setCollidable(true);
                p.removePotionEffect(PotionEffectType.NIGHT_VISION);
                p.removeMetadata("vanished", this);
                tabPluginHook.update(p, false);
            }
        }

        saveDataSync();
        if (vanishTeam != null) vanishTeam.unregister();
    }

    // --- PUBLIC API METHODS (Fixes Compilation Errors) ---
    public boolean isVanished(Player player) {
        return vanishedPlayers.contains(player.getUniqueId());
    }

    public boolean isVanished(UUID uuid) {
        return vanishedPlayers.contains(uuid);
    }

    public UpdateChecker getUpdateChecker() {
        return updateChecker;
    }

    public boolean hasProtocolLib() {
        return hasProtocolLib;
    }

    public Set<UUID> getIgnoredWarningPlayers() {
        return ignoredWarningPlayers;
    }
    // -----------------------------------------------------

    private void setupTeams() {
        Scoreboard mainScoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        this.vanishTeam = mainScoreboard.getTeam("Vanishpp_Vanished");
        if (this.vanishTeam == null) this.vanishTeam = mainScoreboard.registerNewTeam("Vanishpp_Vanished");

        Component prefix = (configManager.vanishNametagPrefix != null && !configManager.vanishNametagPrefix.isEmpty())
                ? Component.text(configManager.vanishNametagPrefix)
                : Component.empty();

        vanishTeam.prefix(prefix);
        vanishTeam.setCanSeeFriendlyInvisibles(true);
        vanishTeam.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
    }

    private void startActionBarTask() {
        if (!configManager.actionBarEnabled) return;
        Component actionBarComponent = Component.text(configManager.actionBarText);
        this.actionBarTask = getServer().getScheduler().runTaskTimer(this, () -> {
            long now = System.currentTimeMillis();
            for (UUID uuid : vanishedPlayers) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && p.isOnline()) {
                    long pausedUntil = actionBarPausedUntil.getOrDefault(uuid, 0L);
                    if (now > pausedUntil) {
                        p.sendActionBar(actionBarComponent);
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
        this.syncTask = getServer().getScheduler().runTaskTimer(this, () -> {
            for (UUID uuid : vanishedPlayers) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && p.isOnline()) {
                    updateVanishVisibility(p);
                }
            }
        }, 20L, 20L);
    }

    public void applyVanishEffects(Player player) {
        vanishedPlayers.add(player.getUniqueId());
        vanishTeam.addEntry(player.getName());
        player.setMetadata("vanished", new FixedMetadataValue(this, true));

        if (configManager.disableBlockTriggering) player.setAffectsSpawning(false);
        if (configManager.preventSleeping) player.setSleepingIgnored(true);

        if (configManager.enableNightVision && permissionManager.hasPermission(player, "vanishpp.nightvision")) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, PotionEffect.INFINITE_DURATION, 0, false, false));
        }

        // Native Stealth (Smart AI handles targeting, ProtocolLib handles visibility for staff)
        player.setInvisible(true);
        player.setSilent(true);
        player.setCollidable(false);

        if (configManager.enableFly && player.getGameMode() != GameMode.SPECTATOR) {
            player.setAllowFlight(true);
            player.setFlying(true);
        }

        if (voiceChatHook != null) voiceChatHook.updateVanishState(player, true);
        integrationManager.updateHooks(player, true);
        tabPluginHook.update(player, true);
        updateVanishVisibility(player);
        saveDataAsynchronously();
    }

    public void removeVanishEffects(Player player) {
        vanishedPlayers.remove(player.getUniqueId());
        player.setAffectsSpawning(true);
        player.setSleepingIgnored(false);
        player.removeMetadata("vanished", this);

        if (vanishTeam.hasEntry(player.getName())) vanishTeam.removeEntry(player.getName());

        player.setInvisible(false);
        player.setSilent(false);
        player.setCollidable(true);

        if (player.hasPotionEffect(PotionEffectType.NIGHT_VISION)) player.removePotionEffect(PotionEffectType.NIGHT_VISION);

        if (player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR) {
            player.setAllowFlight(false);
            player.setFlying(false);
        }

        if (voiceChatHook != null) voiceChatHook.updateVanishState(player, false);
        integrationManager.updateHooks(player, false);
        tabPluginHook.update(player, false);
        updateVanishVisibility(player);
        saveDataAsynchronously();
    }

    public void vanishPlayer(Player player, CommandSender executor) {
        applyVanishEffects(player);
        player.sendMessage(Component.text(configManager.vanishMessage));
        if (configManager.broadcastFakeQuit) {
            broadcastToUnaware(Component.translatable("multiplayer.player.left", NamedTextColor.YELLOW, player.displayName()), player);
        }
        notifyStaff(player, executor, true);
    }

    public void unvanishPlayer(Player player, CommandSender executor) {
        if (configManager.broadcastFakeJoin) {
            broadcastToUnaware(Component.translatable("multiplayer.player.joined", NamedTextColor.YELLOW, player.displayName()), player);
        }

        removeVanishEffects(player);
        player.sendMessage(Component.text(configManager.unvanishMessage));
        notifyStaff(player, executor, false);
    }

    private void broadcastToUnaware(Component message, Player vanishedPlayer) {
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (!permissionManager.canSee(onlinePlayer, vanishedPlayer) && !onlinePlayer.equals(vanishedPlayer)) {
                onlinePlayer.sendMessage(message);
            }
        }
    }

    private void notifyStaff(Player subject, CommandSender executor, boolean isVanishing) {
        if (!configManager.staffNotifyEnabled) return;
        String template = isVanishing ? configManager.staffVanishMessage : configManager.staffUnvanishMessage;
        String notification = template.replace("%player%", subject.getName()).replace("%staff%", executor.getName());
        Component comp = Component.text(notification);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (permissionManager.hasPermission(p, "vanishpp.see")) p.sendMessage(comp);
        }
    }

    public void updateVanishVisibility(Player subject) {
        boolean isVanished = isVanished(subject);
        for (Player observer : Bukkit.getOnlinePlayers()) {
            if (observer.equals(subject)) continue;
            boolean canSee = permissionManager.canSee(observer, subject);

            if (isVanished && !canSee) {
                observer.hidePlayer(this, subject);
            } else {
                observer.showPlayer(this, subject);
            }
        }
    }

    public void scheduleRuleRevert(Player player, String rule, boolean originalValue, int seconds) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            ruleManager.setRule(player, rule, originalValue);
            if (player.isOnline()) {
                player.sendMessage(Component.text("Temporary rule '" + rule + "' has expired.", NamedTextColor.YELLOW));
            }
        }, seconds * 20L);
    }

    @EventHandler
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        String msg = event.getMessage().toLowerCase();
        if (msg.startsWith("/op ") || msg.startsWith("/deop ") || msg.startsWith("/lp user ") || msg.startsWith("/luckperms user ")) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                for (UUID uuid : vanishedPlayers) {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null) updateVanishVisibility(p);
                }
            }, 10L);
        }
    }

    public void togglePickup(Player player) {
        boolean current = ruleManager.getRule(player, RuleManager.CAN_PICKUP_ITEMS);
        ruleManager.setRule(player, RuleManager.CAN_PICKUP_ITEMS, !current);
        saveDataAsynchronously();
    }

    public boolean isWarningIgnored(Player player) {
        return ignoredWarningPlayers.contains(player.getUniqueId());
    }

    public void setWarningIgnored(Player player, boolean ignored) {
        if (ignored) ignoredWarningPlayers.add(player.getUniqueId());
        else ignoredWarningPlayers.remove(player.getUniqueId());
        saveDataAsynchronously();
    }

    private void saveDataSync() {
        List<String> uuidStrings = vanishedPlayers.stream().map(UUID::toString).toList();
        dataManager.getConfig().set("vanished-players", uuidStrings);

        List<String> ignoredStrings = ignoredWarningPlayers.stream().map(UUID::toString).toList();
        dataManager.getConfig().set("ignored-warnings", ignoredStrings);

        dataManager.save();
        ruleManager.save();
    }

    private void saveDataAsynchronously() {
        Bukkit.getScheduler().runTaskAsynchronously(this, this::saveDataSync);
    }

    private Set<UUID> loadUuidSet(List<String> list) {
        Set<UUID> set = new HashSet<>();
        if (list == null) return set;
        for (String s : list) {
            try {
                set.add(UUID.fromString(s));
            } catch (Exception ignored) {
            }
        }
        return set;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public DataManager getDataManager() {
        return dataManager;
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

    public Set<UUID> getRawPickupPlayers() {
        return new HashSet<>();
    }
}