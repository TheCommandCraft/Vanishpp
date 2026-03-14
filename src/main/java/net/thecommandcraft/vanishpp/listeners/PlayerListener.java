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

import java.util.*;

public class PlayerListener implements Listener {

    private final Vanishpp plugin;
    private final ConfigManager config;
    private final RuleManager rules;
    private final Map<UUID, GameMode> silentChestViewers = new HashMap<>();
    private final Map<UUID, Map<String, Long>> ruleNotificationCooldowns = new HashMap<>();
    private final Set<UUID> hasSeenDisableTip = new HashSet<>();

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
        }

        for (UUID uuid : plugin.getRawVanishedPlayers()) {
            Player v = plugin.getServer().getPlayer(uuid);
            if (v != null && !plugin.getPermissionManager().canSee(player, v)) {
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
                    player.sendMessage(Component.text("⚠ Vanish++ Setup Issues:", NamedTextColor.RED));
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
        if (event.getAction() == Action.PHYSICAL && !rules.getRule(p, RuleManager.CAN_TRIGGER_PHYSICAL))
            event.setCancelled(true);
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK || event.getAction() == Action.RIGHT_CLICK_AIR) {
            if (!rules.getRule(p, RuleManager.CAN_INTERACT)) {
                event.setCancelled(true);
                if (event.getAction() == Action.RIGHT_CLICK_BLOCK || event.hasItem())
                    sendRuleDeny(p, RuleManager.CAN_INTERACT, "interaction");
            } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                handleSilentChest(event);
            }
        }
    }

    @EventHandler
    public void onMobTarget(EntityTargetLivingEntityEvent event) {
        if (event.getTarget() instanceof Player p && plugin.isVanished(p)) {
            // Only cancel targeting if mob_targeting rule is OFF (false = mobs ignore vanished player)
            if (!rules.getRule(p, RuleManager.MOB_TARGETING)) {
                event.setCancelled(true);
                if (event.getEntity() instanceof org.bukkit.entity.Mob mob)
                    mob.setTarget(null);
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
        if (config.hideTabComplete && !plugin.getPermissionManager().hasPermission(event.getPlayer(), "vanishpp.see")) {
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
        if (config.preventSleeping || !rules.getRule(p, RuleManager.CAN_INTERACT)) {
            event.setCancelled(true);
            if (!rules.getRule(p, RuleManager.CAN_INTERACT))
                sendRuleDeny(p, RuleManager.CAN_INTERACT, "sleeping");
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
            // ProtocolLib available: suppress block action/sound packets, open normally
            String blockKey = block.getX() + "," + block.getY() + "," + block.getZ();
            plugin.silentlyOpenedBlocks.add(blockKey);
            Inventory inv = (type == Material.ENDER_CHEST) ? player.getEnderChest()
                    : (block.getState() instanceof Container c ? c.getInventory() : null);
            if (inv != null) {
                silentChestViewers.put(player.getUniqueId(), player.getGameMode()); // track for close event
                player.openInventory(inv);
            } else {
                plugin.silentlyOpenedBlocks.remove(blockKey);
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
        if (silentChestViewers.containsKey(event.getPlayer().getUniqueId())) {
            Player p = (Player) event.getPlayer();
            GameMode gm = silentChestViewers.remove(p.getUniqueId());
            if (p.isOnline()) {
                // Restore game mode only if we switched to spectator (ProtocolLib path keeps original game mode)
                if (gm != GameMode.SPECTATOR && p.getGameMode() == GameMode.SPECTATOR) {
                    p.setGameMode(gm);
                    if (config.enableFly && gm != GameMode.CREATIVE && plugin.isVanished(p)) {
                        p.setAllowFlight(true);
                        p.setFlying(true);
                    }
                }
            }
        }
        // Clean up any silently opened block (ProtocolLib path)
        // Find the block key by checking nearby blocks — InventoryCloseEvent doesn't give block info
        // We rely on the block being cleaned up when tracked in handleSilentChest.
        // Remove all keys that are no longer tracked by any open viewer.
        if (!plugin.hasProtocolLib()) return;
        // For ProtocolLib path: remove block tracking when no viewer is watching it
        // (A simpler approach: clear when the specific viewer closes)
        // We store block key per viewer by tracking in silentChestViewers key
        // Since we can't easily get the block from InventoryCloseEvent, we clear all
        // silently tracked blocks when no viewers remain — safe enough for now
        if (silentChestViewers.isEmpty()) {
            plugin.silentlyOpenedBlocks.clear();
        }
    }

    private static final long RULE_NOTIFY_COOLDOWN_MS = 3000;

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

        // Interactive buttons: [Allow 1m] and [Allow permanently]
        Component allow1m = Component.text("[Allow 1m]", NamedTextColor.GREEN, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/vrules " + ruleName + " true 60"))
                .hoverEvent(HoverEvent.showText(Component.text("Enable '" + ruleName + "' for 60 seconds", NamedTextColor.GRAY)));
        Component allowPerm = Component.text("[Allow permanently]", NamedTextColor.AQUA, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/vrules " + ruleName + " true"))
                .hoverEvent(HoverEvent.showText(Component.text("Enable '" + ruleName + "' permanently", NamedTextColor.GRAY)));
        p.sendMessage(allow1m.append(Component.text("  ")).append(allowPerm));

        if (!hasSeenDisableTip.contains(uuid)) {
            hasSeenDisableTip.add(uuid);
            plugin.getMessageManager().sendMessage(p, lm.getMessage("warnings.ignore-tip"));
        }
    }
}