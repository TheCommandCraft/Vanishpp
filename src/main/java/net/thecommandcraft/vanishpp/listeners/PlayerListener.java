package net.thecommandcraft.vanishpp.listeners;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import net.thecommandcraft.vanishpp.Vanishpp;
import net.thecommandcraft.vanishpp.config.ConfigManager;
import net.thecommandcraft.vanishpp.config.RuleManager;
import net.thecommandcraft.vanishpp.utils.LanguageManager;
import net.thecommandcraft.vanishpp.utils.StartupChecker;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockReceiveGameEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.raid.RaidTriggerEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.util.Vector;
import org.bukkit.event.Event;

import java.util.*;

public class PlayerListener implements Listener {

    private final Vanishpp plugin;
    private final ConfigManager config;
    private final RuleManager rules;
    private final Map<UUID, GameMode> silentChestViewers = new HashMap<>();
    private final Map<UUID, String> silentChestBlockKeys = new HashMap<>(); // block key per viewer for cleanup
    private final Map<UUID, Inventory> silentChestRealInventories = new HashMap<>(); // snapshot → real for sync-back
    private final Map<UUID, Map<String, Long>> ruleNotificationCooldowns = new HashMap<>();
    private final Set<UUID> hasSeenDisableTip = new HashSet<>();
    private final Map<UUID, Long> lastSneakTime = new HashMap<>();

    public PlayerListener(Vanishpp plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        this.rules = plugin.getRuleManager();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Immediate Vanish Logic
        if (plugin.isVanished(player)) {
            plugin.applyVanishEffects(player);
            plugin.updateVanishVisibility(player);
            if (config.hideRealJoin)
                event.joinMessage(null);
            // Notify staff that a vanished player silently joined
            String joinMsg = config.getLanguageManager().getMessage("staff.silent-join")
                    .replace("%player%", player.getName());
            Component joinComp = plugin.getMessageManager().parse(joinMsg, player);
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (!staff.equals(player) && plugin.getPermissionManager().hasPermission(staff, "vanishpp.see"))
                    staff.sendMessage(joinComp);
            }
            Bukkit.getConsoleSender().sendMessage(joinComp);

            // Multi-stage reapply to catch TAB plugin overrides at different stages of its async pipeline.
            // Stage 1 (2 ticks / ~100ms): catches most cases instantly
            // Stage 2 (20 ticks / 1s): catches delayed TAB processing
            // Stage 3 (60 ticks / 3s): final safety net for heavily loaded servers
            for (long delay : new long[]{2L, 20L, 60L}) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline() && plugin.isVanished(player)) {
                        plugin.reapplyTeamEntry(player);
                        if (config.vanishTabPrefix != null && !config.vanishTabPrefix.isEmpty()) {
                            player.playerListName(plugin.getMessageManager().parse(
                                    config.vanishTabPrefix + player.getName(), player));
                        }
                        plugin.getIntegrationManager().updateHooks(player, true);
                        plugin.getTabPluginHook().update(player, true);
                    }
                }, delay);
            }
        }

        for (UUID uuid : plugin.getRawVanishedPlayers()) {
            Player v = plugin.getServer().getPlayer(uuid);
            if (v == null) continue;
            if (plugin.getPermissionManager().canSee(player, v)) {
                player.showPlayer(plugin, v);
            } else {
                player.hidePlayer(plugin, v);
            }
        }

        // DELAYED NOTIFICATIONS (250ms / 5 Ticks)
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline())
                return;

            // 1. Migration Report
            if (player.hasPermission("vanishpp.update") || player.isOp()) {
                config.sendMigrationReport(player);
            }

            // 2. ProtocolLib Warning
            if (!plugin.hasProtocolLib() && player.isOp() && !plugin.isWarningIgnored(player)) {
                LanguageManager lm = config.getLanguageManager();
                plugin.getMessageManager().sendMessage(player, lm.getMessage("warnings.box-top"));
                plugin.getMessageManager().sendMessage(player, lm.getMessage("warnings.header"));
                plugin.getMessageManager().sendMessage(player, lm.getMessage("warnings.line"));
                plugin.getMessageManager().sendMessage(player, lm.getMessage("warnings.sub"));
                plugin.getMessageManager().sendMessage(player, lm.getMessage("warnings.box-bottom"));

                Title title = Title.title(
                        plugin.getMessageManager().parse(lm.getMessage("warnings.protocollib-missing-title"), player),
                        plugin.getMessageManager().parse(lm.getMessage("warnings.protocollib-missing-subtitle"),
                                player));
                player.showTitle(title);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2.0f, 0.5f);
            }

            // 3. Update Check
            if (plugin.getUpdateChecker() != null) {
                plugin.getUpdateChecker().notifyPlayer(player);
            }

            // 4. Setup / Config Sanity Warnings
            if (plugin.getPermissionManager().hasPermission(player, "vanishpp.see")) {
                java.util.List<StartupChecker.Warning> warnings = plugin.getStartupWarnings();
                if (!warnings.isEmpty()) {
                    plugin.getMessageManager().sendMessage(player,
                            config.getLanguageManager().getMessage("warnings.setup-header"));
                    for (StartupChecker.Warning w : warnings) {
                        player.sendMessage(Component.text(" • ", NamedTextColor.GOLD)
                                .append(Component.text(w.message, NamedTextColor.YELLOW)));
                        // Action buttons
                        boolean hasButtons = false;
                        Component buttons = Component.text("   ");
                        if (w.configPath != null) {
                            hasButtons = true;
                            buttons = buttons
                                    .append(Component.text("[Set to " + w.fixValue + "]",
                                            NamedTextColor.GREEN, TextDecoration.BOLD)
                                            .clickEvent(ClickEvent.runCommand(
                                                    "/vconfig " + w.configPath + " " + w.fixValue))
                                            .hoverEvent(HoverEvent.showText(Component.text(
                                                    "Sets " + w.configPath + " to " + w.fixValue
                                                    + " and saves config", NamedTextColor.GRAY))))
                                    .append(Component.text("  "))
                                    .append(Component.text("[Reload]", NamedTextColor.AQUA, TextDecoration.BOLD)
                                            .clickEvent(ClickEvent.runCommand("/vreload"))
                                            .hoverEvent(HoverEvent.showText(Component.text(
                                                    "Reload Vanish++ config after fixing", NamedTextColor.GRAY))));
                        }
                        if (w.installUrl != null) {
                            hasButtons = true;
                            buttons = buttons
                                    .append(Component.text("[Install Plugin]",
                                            NamedTextColor.GREEN, TextDecoration.BOLD)
                                            .clickEvent(ClickEvent.openUrl(w.installUrl))
                                            .hoverEvent(HoverEvent.showText(Component.text(
                                                    "Open download page in browser", NamedTextColor.GRAY))));
                        }
                        if (hasButtons) {
                            player.sendMessage(buttons);
                        }
                    }
                }
            }
        }, 5L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVanishDamage(EntityDamageEvent event) {
        if (config.godMode && event.getEntity() instanceof Player player) {
            if (plugin.isVanished(player))
                event.setCancelled(true);
        }
    }

    /** Prevent external velocity (knockback, explosions) from pushing vanished players when god mode is on. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        if (config.godMode && plugin.isVanished(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVanishPotion(EntityPotionEffectEvent event) {
        if (config.preventPotions && event.getEntity() instanceof Player player) {
            if (plugin.isVanished(player) && event.getCause() != EntityPotionEffectEvent.Cause.PLUGIN) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (plugin.isVanished(player)) {
            if (config.hideRealQuit)
                event.quitMessage(null);
            String blockKey = silentChestBlockKeys.remove(uuid);
            if (blockKey != null) plugin.silentlyOpenedBlocks.remove(blockKey);
            silentChestViewers.remove(uuid);
            plugin.pendingChatMessages.remove(uuid);
            // Notify staff that a vanished player silently left
            String quitMsg = config.getLanguageManager().getMessage("staff.silent-quit")
                    .replace("%player%", player.getName());
            Component quitComp = plugin.getMessageManager().parse(quitMsg, player);
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (!staff.equals(player) && plugin.getPermissionManager().hasPermission(staff, "vanishpp.see"))
                    staff.sendMessage(quitComp);
            }
            Bukkit.getConsoleSender().sendMessage(quitComp);
        }
        ruleNotificationCooldowns.remove(uuid);
        hasSeenDisableTip.remove(uuid);
        lastSneakTime.remove(uuid);
        plugin.cleanupPlayerCache(uuid);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (plugin.isVanished(player)) {
            if (player.hasMetadata("vanishpp_chat_bypass")) {
                player.removeMetadata("vanishpp_chat_bypass", plugin);
                // Still allow, but restrict to seers with prefix
                applyVanishChatFilter(event, player);
                return;
            }
            if (!rules.getRule(player, RuleManager.CAN_CHAT)) {
                event.setCancelled(true);
                String msgContent = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(event.message());
                plugin.pendingChatMessages.put(player.getUniqueId(), msgContent);

                String message = config.getLanguageManager().getMessage("chat.locked");
                plugin.getMessageManager().sendMessage(player, message);
                return;
            }
            // CAN_CHAT is true — show only to seers with [Vanished] prefix
            applyVanishChatFilter(event, player);
        }
    }

    private void applyVanishChatFilter(AsyncChatEvent event, Player player) {
        // Remove non-seers from audience
        event.viewers().removeIf(viewer ->
            viewer instanceof Player obs && !plugin.getPermissionManager().hasPermission(obs, "vanishpp.see")
        );
        // Add [Vanished] prefix for seers
        Component prefix = plugin.getMessageManager().parse(config.vanishTabPrefix, player);
        event.renderer((source, displayName, message, audience) ->
            prefix.append(displayName).append(Component.text(": ")).append(message)
        );
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (plugin.isVanished(event.getPlayer()) && !rules.getRule(event.getPlayer(), RuleManager.CAN_DROP_ITEMS)) {
            event.setCancelled(true);
            sendRuleDeny(event.getPlayer(), RuleManager.CAN_DROP_ITEMS, "dropping items");
        }
    }

    // Block throwable items (projectiles create visible entities that reveal position)
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity().getShooter() instanceof Player p)) return;
        if (!plugin.isVanished(p)) return;
        if (!rules.getRule(p, RuleManager.CAN_THROW)) {
            event.setCancelled(true);
            sendRuleDeny(p, RuleManager.CAN_THROW, "throwing items");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onShootBow(org.bukkit.event.entity.EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player p)) return;
        if (!plugin.isVanished(p)) return;
        if (!rules.getRule(p, RuleManager.CAN_THROW)) {
            event.setCancelled(true);
            sendRuleDeny(p, RuleManager.CAN_THROW, "shooting");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!config.ignoreProjectiles)
            return;
        if (event.getHitEntity() instanceof Player target && plugin.isVanished(target)) {
            event.setCancelled(true);
            Projectile original = event.getEntity();
            Vector velocity = original.getVelocity();
            if (velocity.length() > 0.1) {
                org.bukkit.Location spawnLoc = original.getLocation().add(velocity.normalize().multiply(1.5));
                Projectile newProj = (Projectile) original.getWorld().spawnEntity(spawnLoc, original.getType());
                newProj.setVelocity(velocity);
                newProj.setShooter(original.getShooter());
                newProj.setFireTicks(original.getFireTicks());
            }
            original.remove();
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        if (plugin.isVanished(event.getPlayer()) && !rules.getRule(event.getPlayer(), RuleManager.CAN_BREAK_BLOCKS)) {
            event.setCancelled(true);
            sendRuleDeny(event.getPlayer(), RuleManager.CAN_BREAK_BLOCKS, "breaking blocks");
        }
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        if (plugin.isVanished(event.getPlayer()) && !rules.getRule(event.getPlayer(), RuleManager.CAN_PLACE_BLOCKS)) {
            event.setCancelled(true);
            sendRuleDeny(event.getPlayer(), RuleManager.CAN_PLACE_BLOCKS, "placing blocks");
        }
    }

    @EventHandler
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && plugin.isVanished(player)
                && !rules.getRule(player, RuleManager.CAN_HIT_ENTITIES)) {
            event.setCancelled(true);
            sendRuleDeny(player, RuleManager.CAN_HIT_ENTITIES, "attacking");
        }
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && plugin.isVanished(player)
                && !rules.getRule(player, RuleManager.CAN_PICKUP_ITEMS)) {
            event.setCancelled(true);
            sendRuleDeny(player, RuleManager.CAN_PICKUP_ITEMS, "picking up items");
        }
    }

    @EventHandler
    public void onArrowPickup(PlayerPickupArrowEvent event) {
        if (plugin.isVanished(event.getPlayer()) && !rules.getRule(event.getPlayer(), RuleManager.CAN_PICKUP_ITEMS)) {
            event.setCancelled(true);
            plugin.triggerActionBarWarning(event.getPlayer(),
                    plugin.getMessageManager().parse(config.getLanguageManager().getMessage("pickup.blocked-actionbar"),
                            event.getPlayer()));
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        if (!plugin.isVanished(p))
            return;
        if (event.getAction() == Action.PHYSICAL) {
            if (!rules.getRule(p, RuleManager.CAN_TRIGGER_PHYSICAL))
                event.setCancelled(true);
            return;
        }
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK || event.getAction() == Action.RIGHT_CLICK_AIR) {
            boolean isSpawnEgg = event.hasItem() && event.getItem() != null
                    && event.getItem().getType().name().endsWith("_SPAWN_EGG");
            boolean isThrowable = event.hasItem() && event.getItem() != null
                    && isThrowableItem(event.getItem().getType());

            if (!rules.getRule(p, RuleManager.CAN_INTERACT)) {
                // If can_throw is enabled, allow throwables/bows/spawn eggs to pass through
                if (isThrowable && rules.getRule(p, RuleManager.CAN_THROW)) {
                    // fall through to throw handling below
                } else {
                    event.setCancelled(true);
                    event.setUseItemInHand(Event.Result.DENY);
                    if (event.getAction() == Action.RIGHT_CLICK_BLOCK || event.hasItem())
                        sendRuleDeny(p, RuleManager.CAN_INTERACT, isSpawnEgg ? "using spawn eggs" : "interaction");
                    return;
                }
            }

            // Spawn eggs blocked unless can_throw is enabled
            if (isSpawnEgg && !rules.getRule(p, RuleManager.CAN_THROW)) {
                event.setCancelled(true);
                event.setUseItemInHand(Event.Result.DENY);
                sendRuleDeny(p, RuleManager.CAN_THROW, "using spawn eggs");
                return;
            }

            if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                handleSilentChest(event);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMobTarget(EntityTargetEvent event) {
        if (event.getTarget() instanceof Player p && plugin.isVanished(p)) {
            // Only cancel targeting if mob_targeting rule is OFF (false = mobs ignore vanished player)
            if (!rules.getRule(p, RuleManager.MOB_TARGETING)) {
                event.setCancelled(true);
                event.setTarget(null);
                if (event.getEntity() instanceof org.bukkit.entity.Mob mob) {
                    mob.setTarget(null);
                    // Also stop pathfinding to prevent baby mobs from chasing after target is cleared
                    mob.getPathfinder().stopPathfinding();
                }
            }
        }
    }

    @EventHandler
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (plugin.isVanished(event.getPlayer()) && !rules.getRule(event.getPlayer(), RuleManager.CAN_INTERACT)) {
            event.setCancelled(true);
            sendRuleDeny(event.getPlayer(), RuleManager.CAN_INTERACT, "entity interaction");
        }
    }

    @EventHandler
    public void onInventoryClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!plugin.isVanished(player)) return;
        if (!rules.getRule(player, RuleManager.CAN_INTERACT)) {
            // Allow clicking in the player's own inventory, block external containers
            if (event.getClickedInventory() != null
                    && event.getClickedInventory().getType() != org.bukkit.event.inventory.InventoryType.PLAYER) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onServerListPing(PaperServerListPingEvent event) {
        if (config.adjustServerListCount) {
            int vanishedCount = 0;
            List<UUID> toRemove = new ArrayList<>();
            for (UUID uuid : plugin.getRawVanishedPlayers()) {
                if (plugin.getServer().getPlayer(uuid) != null) {
                    vanishedCount++;
                    toRemove.add(uuid);
                }
            }
            event.setNumPlayers(Math.max(0, event.getNumPlayers() - vanishedCount));
            event.getListedPlayers().removeIf(profile -> toRemove.contains(profile.id()));
        }
    }

    @EventHandler
    public void onTabComplete(PlayerCommandSendEvent event) {
        Player player = event.getPlayer();
        if (!plugin.getPermissionManager().hasPermission(player, "vanishpp.vanish")) {
            // Hide ALL vanish++ commands from non-staff to keep the plugin undetectable
            Set<String> vanishCmds = Set.of("vanish", "v", "sv", "vperms", "vanishrules", "vrules",
                    "vsettings", "vanishchat", "vchat", "vanishignore", "vignore", "vanishlist", "vlist",
                    "vanishhelp", "vhelp", "vanishconfig", "vconfig", "vack", "vanishreload", "vreload");
            event.getCommands().removeIf(cmd -> vanishCmds.contains(cmd) || cmd.startsWith("vanishpp:"));
        }
        if (config.hideTabComplete && !plugin.getPermissionManager().hasPermission(player, "vanishpp.see")) {
            event.getCommands().removeIf(cmd -> cmd.contains(":"));
        }
    }

    @EventHandler
    public void onSculkSensor(BlockReceiveGameEvent event) {
        if (config.preventSculk && event.getEntity() instanceof Player player && plugin.isVanished(player)
                && !rules.getRule(player, RuleManager.CAN_TRIGGER_PHYSICAL))
            event.setCancelled(true);
    }

    @EventHandler
    public void onRaidTrigger(RaidTriggerEvent event) {
        if (config.preventRaid && plugin.isVanished(event.getPlayer()))
            event.setCancelled(true);
    }

    @EventHandler
    public void onBedEnter(PlayerBedEnterEvent event) {
        Player p = event.getPlayer();
        if (!plugin.isVanished(p)) return;
        if (!rules.getRule(p, RuleManager.CAN_INTERACT)) {
            event.setCancelled(true);
            sendRuleDeny(p, RuleManager.CAN_INTERACT, "sleeping");
        } else if (config.preventSleeping) {
            event.setCancelled(true);
            sendConfigDeny(p, "invisibility-features.prevent-sleeping", "sleeping");
        }
    }

    @EventHandler
    public void onMount(org.bukkit.event.entity.EntityMountEvent event) {
        if (event.getEntity() instanceof Player p && plugin.isVanished(p)
                && !rules.getRule(p, RuleManager.CAN_INTERACT)) {
            event.setCancelled(true);
            sendRuleDeny(p, RuleManager.CAN_INTERACT, "mounting");
        }
    }

    @EventHandler
    public void onAdvancement(org.bukkit.event.player.PlayerAdvancementDoneEvent event) {
        if (plugin.isVanished(event.getPlayer()))
            event.message(null);
    }

    @EventHandler
    public void onHunger(FoodLevelChangeEvent event) {
        if (config.disableHunger && event.getEntity() instanceof Player p && plugin.isVanished(p))
            event.setCancelled(true);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (config.hideDeathMessages && plugin.isVanished(event.getEntity()))
            event.deathMessage(null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!plugin.isVanished(player)) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && plugin.isVanished(player))
                plugin.resyncVanishEffects(player);
        }, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (!plugin.isVanished(player)) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && plugin.isVanished(player))
                plugin.resyncVanishEffects(player);
        }, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        if (!plugin.isVanished(player)) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && plugin.isVanished(player)) {
                if (config.enableFly && player.getGameMode() != GameMode.SPECTATOR) {
                    player.setAllowFlight(true);
                    player.setFlying(true);
                }
            }
        }, 1L);
    }

    /** Double-tap shift while vanished → toggle spectator mode. */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) return; // only on press, not release
        Player p = event.getPlayer();
        if (!plugin.isVanished(p)) return;
        if (!config.vanishGamemodesEnabled) return;
        if (!plugin.getPermissionManager().hasPermission(p, "vanishpp.spectator")) return;
        if (!rules.getRule(p, RuleManager.SPECTATOR_GAMEMODE)) return;

        UUID uuid = p.getUniqueId();
        long now = System.currentTimeMillis();
        long last = lastSneakTime.getOrDefault(uuid, 0L);
        lastSneakTime.put(uuid, now);

        if (now - last > 400) return; // not a double-tap

        if (p.getGameMode() != GameMode.SPECTATOR) {
            p.setGameMode(GameMode.SPECTATOR);
            plugin.triggerActionBarWarning(p, plugin.getMessageManager().parse(
                    config.getLanguageManager().getMessage("spectator.entered"), p), 3000);
        } else {
            GameMode prev = plugin.getPreVanishGamemodePublic(p);
            p.setGameMode(prev);
            plugin.triggerActionBarWarning(p, plugin.getMessageManager().parse(
                    config.getLanguageManager().getMessage("spectator.exited")
                            .replace("%gamemode%", prev.name().toLowerCase()), p), 3000);
        }
    }

    private void handleSilentChest(PlayerInteractEvent event) {
        if (!config.silentChests)
            return;
        Player player = event.getPlayer();
        if (!plugin.isVanished(player) || !plugin.getPermissionManager().hasPermission(player, "vanishpp.silentchest")
                || player.isSneaking())
            return;
        Block block = event.getClickedBlock();
        if (block == null)
            return;
        Material type = block.getType();
        boolean isContainer = type == Material.CHEST || type == Material.TRAPPED_CHEST || type == Material.BARREL
                || type.name().endsWith("SHULKER_BOX") || type == Material.ENDER_CHEST;
        if (!isContainer) return;

        event.setCancelled(true);

        if (plugin.hasProtocolLib()) {
            // Open a snapshot inventory — avoids triggering Container.startOpen()
            // which is the source of the barrel/chest lid animation and sound.
            String blockKey = block.getX() + "," + block.getY() + "," + block.getZ();
            if (type == Material.ENDER_CHEST) {
                // Ender chest has no shared animation state, open directly
                silentChestViewers.put(player.getUniqueId(), player.getGameMode());
                player.openInventory(player.getEnderChest());
            } else if (block.getState() instanceof Container c) {
                Inventory realInv = c.getInventory();
                Component title = c.customName() != null ? c.customName()
                        : Component.translatable(block.getType().translationKey());
                Inventory snapshot = Bukkit.createInventory(null, realInv.getSize(), title);
                snapshot.setContents(realInv.getContents());
                silentChestViewers.put(player.getUniqueId(), player.getGameMode());
                silentChestBlockKeys.put(player.getUniqueId(), blockKey);
                silentChestRealInventories.put(player.getUniqueId(), realInv);
                player.openInventory(snapshot);
            }
        } else {
            // No ProtocolLib: use spectator mode fallback, warn player
            GameMode original = player.getGameMode();
            if (original != GameMode.SPECTATOR) {
                silentChestViewers.put(player.getUniqueId(), original);
                player.setGameMode(GameMode.SPECTATOR);
            }
            Inventory inv = (type == Material.ENDER_CHEST) ? player.getEnderChest()
                    : (block.getState() instanceof Container c ? c.getInventory() : null);
            if (inv != null) {
                player.openInventory(inv);
                // Show install link for better experience
                net.kyori.adventure.text.Component msg = net.kyori.adventure.text.Component
                        .text("⚠ Install ", NamedTextColor.YELLOW)
                        .append(net.kyori.adventure.text.Component.text("[ProtocolLib]", NamedTextColor.AQUA,
                                TextDecoration.UNDERLINED)
                                .clickEvent(ClickEvent.openUrl("https://www.spigotmc.org/resources/protocollib.1997/"))
                                .hoverEvent(HoverEvent.showText(net.kyori.adventure.text.Component.text(
                                        "Click to open SpigotMC download page", NamedTextColor.GRAY))))
                        .append(net.kyori.adventure.text.Component.text(" to move items in silent chests.", NamedTextColor.YELLOW));
                player.sendMessage(msg);
            } else {
                if (original != GameMode.SPECTATOR) player.setGameMode(original);
                silentChestViewers.remove(player.getUniqueId());
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (!silentChestViewers.containsKey(uuid)) return;

        Player p = (Player) event.getPlayer();
        GameMode gm = silentChestViewers.remove(uuid);
        String blockKey = silentChestBlockKeys.remove(uuid);

        // Sync snapshot contents back to the real container
        Inventory realInv = silentChestRealInventories.remove(uuid);
        if (realInv != null) {
            realInv.setContents(event.getInventory().getContents());
        }

        if (p.isOnline()) {
            // Restore game mode only if we switched to spectator (non-ProtocolLib fallback path)
            if (gm != GameMode.SPECTATOR && p.getGameMode() == GameMode.SPECTATOR) {
                p.setGameMode(gm);
                if (config.enableFly && gm != GameMode.CREATIVE && plugin.isVanished(p)) {
                    p.setAllowFlight(true);
                    p.setFlying(true);
                }
            }
        }

        // Delay removal so ProtocolLib still suppresses close animation + sound packets
        // that fire AFTER InventoryCloseEvent
        if (blockKey != null) {
            final String key = blockKey;
            Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.silentlyOpenedBlocks.remove(key), 3L);
        }
    }

    private static final long RULE_NOTIFY_COOLDOWN_MS = 60000;

    private static boolean isThrowableItem(org.bukkit.Material mat) {
        return switch (mat) {
            case SNOWBALL, EGG, ENDER_PEARL, EXPERIENCE_BOTTLE, SPLASH_POTION,
                 LINGERING_POTION, TRIDENT, BOW, CROSSBOW -> true;
            default -> mat.name().endsWith("_SPAWN_EGG");
        };
    }

    private void sendRuleDeny(Player p, String ruleName, String actionName) {
        LanguageManager lm = config.getLanguageManager();
        plugin.triggerActionBarWarning(p,
                plugin.getMessageManager()
                        .parse(lm.getMessage("warnings.action-blocked-actionbar").replace("%action%", actionName), p));
        if (!rules.getRule(p, RuleManager.SHOW_NOTIFICATIONS))
            return;
        UUID uuid = p.getUniqueId();
        long now = System.currentTimeMillis();
        Map<String, Long> playerCooldowns = ruleNotificationCooldowns.computeIfAbsent(uuid, k -> new HashMap<>());
        // Enforce cooldown — don't spam the same message more than once per 3 seconds
        if (now - playerCooldowns.getOrDefault(ruleName, 0L) < RULE_NOTIFY_COOLDOWN_MS)
            return;
        playerCooldowns.put(ruleName, now);

        String message = lm.getMessage("warnings.vanish-blocked")
                .replace("%action%", actionName)
                .replace("%rule%", ruleName);
        plugin.getMessageManager().sendMessage(p, message);

        // Interactive buttons: [Allow 1m], [Allow permanently], [Unvanish], [Hide notifications]
        Component allow1m = Component.text("[Allow 1m]", NamedTextColor.GREEN, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/vrules " + ruleName + " true 60"))
                .hoverEvent(HoverEvent.showText(Component.text("Enable '" + ruleName + "' for 60 seconds", NamedTextColor.GRAY)));
        Component allowPerm = Component.text("[Allow permanently]", NamedTextColor.AQUA, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/vrules " + ruleName + " true"))
                .hoverEvent(HoverEvent.showText(Component.text("Enable '" + ruleName + "' permanently", NamedTextColor.GRAY)));
        Component unvanish = Component.text("[Unvanish]", NamedTextColor.YELLOW, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/vanish"))
                .hoverEvent(HoverEvent.showText(Component.text("Unvanish so you can " + actionName, NamedTextColor.GRAY)));
        Component hideNotifs = Component.text("[Hide notifications]", NamedTextColor.GRAY)
                .clickEvent(ClickEvent.runCommand("/vrules show_notifications false"))
                .hoverEvent(HoverEvent.showText(Component.text("Disable all rule notifications", NamedTextColor.GRAY)));
        p.sendMessage(allow1m.append(Component.text("  ")).append(allowPerm).append(Component.text("  "))
                .append(unvanish).append(Component.text("  ")).append(hideNotifs));
    }

    /** Notify player that a config-level setting blocked their action, with a button to change it. */
    private void sendConfigDeny(Player p, String configPath, String actionName) {
        LanguageManager lm = config.getLanguageManager();
        plugin.triggerActionBarWarning(p,
                plugin.getMessageManager()
                        .parse(lm.getMessage("warnings.action-blocked-actionbar").replace("%action%", actionName), p));
        UUID uuid = p.getUniqueId();
        long now = System.currentTimeMillis();
        Map<String, Long> playerCooldowns = ruleNotificationCooldowns.computeIfAbsent(uuid, k -> new HashMap<>());
        if (now - playerCooldowns.getOrDefault(configPath, 0L) < RULE_NOTIFY_COOLDOWN_MS)
            return;
        playerCooldowns.put(configPath, now);

        String message = lm.getMessage("warnings.config-blocked")
                .replace("%action%", actionName)
                .replace("%path%", configPath);
        plugin.getMessageManager().sendMessage(p, message);

        Component unvanish = Component.text("[Unvanish]", NamedTextColor.YELLOW, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/vanish"))
                .hoverEvent(HoverEvent.showText(Component.text("Unvanish so you can " + actionName, NamedTextColor.GRAY)));
        if (p.hasPermission("vanishpp.config")) {
            Component changeBtn = Component.text("[Disable in config]", NamedTextColor.GREEN, TextDecoration.BOLD)
                    .clickEvent(ClickEvent.runCommand("/vconfig " + configPath + " false"))
                    .hoverEvent(HoverEvent.showText(
                            Component.text("Sets " + configPath + " to false", NamedTextColor.GRAY)));
            p.sendMessage(changeBtn.append(Component.text("  ")).append(unvanish));
        } else {
            p.sendMessage(unvanish);
        }
    }

}
