package net.thecommandcraft.vanishpp;

import com.destroystokyo.paper.event.entity.ProjectileCollideEvent;
import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
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

import java.time.Duration;
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

        if (plugin.isVanished(player)) {
            plugin.applyVanishEffects(player);
            plugin.updateVanishVisibility(player);
            if (config.fakeJoinMessage) event.joinMessage(null);
        }

        for (UUID uuid : plugin.getRawVanishedPlayers()) {
            Player v = plugin.getServer().getPlayer(uuid);
            if (v != null) {
                if (!plugin.getPermissionManager().canSee(player, v)) {
                    player.hidePlayer(plugin, v);
                }
            }
        }

        // ProtocolLib Warning
        if (!plugin.hasProtocolLib() && player.isOp() && !plugin.isWarningIgnored(player)) {
            player.sendMessage(Component.text("█▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀█", NamedTextColor.DARK_RED, TextDecoration.BOLD));
            player.sendMessage(Component.text(" CRITICAL DEPENDENCY MISSING", NamedTextColor.RED, TextDecoration.BOLD));
            player.sendMessage(Component.text(" ProtocolLib is NOT installed.", NamedTextColor.YELLOW));
            player.sendMessage(Component.text(" ⚠ YOU ARE NOT FULLY HIDDEN! ⚠", NamedTextColor.RED));
            player.sendMessage(Component.text(" - Tab-Complete Scrubbing: OFF", NamedTextColor.GRAY));
            player.sendMessage(Component.text(" - Server List Hiding: OFF", NamedTextColor.GRAY));
            player.sendMessage(Component.text(" - Spectator View: OFF", NamedTextColor.GRAY));
            player.sendMessage(Component.text("█▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄█", NamedTextColor.DARK_RED, TextDecoration.BOLD));
            player.sendMessage(Component.text("Type /vignore to silence this alarm.", NamedTextColor.GRAY, TextDecoration.ITALIC));

            Title title = Title.title(
                    Component.text("⚠ WARNING ⚠", NamedTextColor.RED, TextDecoration.BOLD),
                    Component.text("ProtocolLib Missing!", NamedTextColor.YELLOW),
                    Title.Times.times(Duration.ofMillis(500), Duration.ofMillis(4000), Duration.ofMillis(1000))
            );
            player.showTitle(title);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2.0f, 0.5f);
        }

        // Update Check (With Delay)
        if (plugin.getUpdateChecker() != null) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    plugin.getUpdateChecker().notifyPlayer(player);
                }
            }, 5L); // 0.25 seconds delay (5 ticks)
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (plugin.isVanished(event.getPlayer())) {
            if (config.fakeLeaveMessage) event.quitMessage(null);
            silentChestViewers.remove(uuid);
            plugin.pendingChatMessages.remove(uuid);
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
                return;
            }

            boolean canChat = rules.getRule(player, RuleManager.CAN_CHAT);
            if (!canChat) {
                event.setCancelled(true);
                String msgContent = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(event.message());
                plugin.pendingChatMessages.put(player.getUniqueId(), msgContent);

                Component msg = Component.text(config.chatLockedMessage, NamedTextColor.RED)
                        .append(Component.text(" [CONFIRM]", NamedTextColor.GREEN, TextDecoration.BOLD)
                                .clickEvent(ClickEvent.runCommand("/vchat confirm"))
                                .hoverEvent(HoverEvent.showText(Component.text("Click to send ONCE"))))
                        .append(Component.text(" or ", NamedTextColor.GRAY))
                        .append(Component.text("[ENABLE CHAT]", NamedTextColor.GOLD, TextDecoration.BOLD)
                                .clickEvent(ClickEvent.runCommand("/vrules can_chat true"))
                                .hoverEvent(HoverEvent.showText(Component.text("Click to enable rule permanently"))));

                player.sendMessage(msg);
            }
        }
    }

    @EventHandler
    public void onProjectileCollide(ProjectileCollideEvent event) {
        if (!config.ignoreProjectiles) return;
        if (event.getCollidedWith() instanceof Player player && plugin.isVanished(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        if (plugin.isVanished(event.getPlayer())) {
            if (!rules.getRule(event.getPlayer(), RuleManager.CAN_BREAK_BLOCKS)) {
                event.setCancelled(true);
                sendRuleDeny(event.getPlayer(), RuleManager.CAN_BREAK_BLOCKS, "breaking blocks");
            }
        }
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        if (plugin.isVanished(event.getPlayer())) {
            if (!rules.getRule(event.getPlayer(), RuleManager.CAN_PLACE_BLOCKS)) {
                event.setCancelled(true);
                sendRuleDeny(event.getPlayer(), RuleManager.CAN_PLACE_BLOCKS, "placing blocks");
            }
        }
    }

    @EventHandler
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && plugin.isVanished(player)) {
            if (!rules.getRule(player, RuleManager.CAN_HIT_ENTITIES)) {
                event.setCancelled(true);
                sendRuleDeny(player, RuleManager.CAN_HIT_ENTITIES, "attacking");
            }
        }
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && plugin.isVanished(player)) {
            if (!rules.getRule(player, RuleManager.CAN_PICKUP_ITEMS)) {
                event.setCancelled(true);
                sendRuleDeny(player, RuleManager.CAN_PICKUP_ITEMS, "picking up items");
            }
        }
    }

    @EventHandler
    public void onArrowPickup(PlayerPickupArrowEvent event) {
        if (plugin.isVanished(event.getPlayer())) {
            if (!rules.getRule(event.getPlayer(), RuleManager.CAN_PICKUP_ITEMS)) {
                event.setCancelled(true);
                plugin.triggerActionBarWarning(event.getPlayer(), Component.text("✖ Arrow Pickup Blocked", NamedTextColor.RED));
            }
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (plugin.isVanished(event.getPlayer())) {
            if (!rules.getRule(event.getPlayer(), RuleManager.CAN_DROP_ITEMS)) {
                event.setCancelled(true);
                sendRuleDeny(event.getPlayer(), RuleManager.CAN_DROP_ITEMS, "dropping items");
            }
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        if (!plugin.isVanished(p)) return;

        if (event.getAction() == Action.PHYSICAL) {
            if (!rules.getRule(p, RuleManager.CAN_TRIGGER_PHYSICAL)) {
                event.setCancelled(true);
            }
            return;
        }

        if (event.getAction() == Action.RIGHT_CLICK_BLOCK || event.getAction() == Action.RIGHT_CLICK_AIR) {
            if (!rules.getRule(p, RuleManager.CAN_INTERACT)) {
                event.setCancelled(true);
                if (event.getAction() == Action.RIGHT_CLICK_BLOCK || event.hasItem()) {
                    sendRuleDeny(p, RuleManager.CAN_INTERACT, "interaction");
                }
                return;
            }
            if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                handleSilentChest(event);
            }
        }
    }

    @EventHandler
    public void onMobTarget(EntityTargetLivingEntityEvent event) {
        if (event.getTarget() instanceof Player p && plugin.isVanished(p)) {
            event.setCancelled(true);
            if (event.getEntity() instanceof org.bukkit.entity.Mob mob) {
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
        if (config.preventSculk && event.getEntity() instanceof Player player) {
            if (plugin.isVanished(player) && !rules.getRule(player, RuleManager.CAN_TRIGGER_PHYSICAL)) event.setCancelled(true);
        }
    }
    @EventHandler public void onRaidTrigger(RaidTriggerEvent event) { if (config.preventRaid && plugin.isVanished(event.getPlayer())) event.setCancelled(true); }
    @EventHandler public void onBedEnter(PlayerBedEnterEvent event) { if (config.preventSleeping && plugin.isVanished(event.getPlayer())) event.setCancelled(true); }
    @EventHandler public void onHunger(FoodLevelChangeEvent event) { if (config.disableHunger && event.getEntity() instanceof Player p && plugin.isVanished(p)) event.setCancelled(true); }
    @EventHandler public void onDeath(PlayerDeathEvent event) { if (config.hideDeathMessages && plugin.isVanished(event.getEntity())) event.deathMessage(null); }

    private void handleSilentChest(PlayerInteractEvent event) {
        if (!config.silentChests) return;
        Player player = event.getPlayer();
        if (!plugin.isVanished(player)) return;
        if (!plugin.getPermissionManager().hasPermission(player, "vanishpp.silentchest")) return;
        if (player.isSneaking()) return;

        Block block = event.getClickedBlock();
        if (block == null) return;
        Material type = block.getType();

        if (type == Material.CHEST || type == Material.TRAPPED_CHEST || type == Material.BARREL || type.name().endsWith("SHULKER_BOX")) {
            event.setCancelled(true);
            GameMode original = player.getGameMode();

            if (original != GameMode.SPECTATOR) {
                silentChestViewers.put(player.getUniqueId(), original);
                player.setGameMode(GameMode.SPECTATOR);
            }

            Inventory inv = null;
            if (block.getState() instanceof Container c) inv = c.getInventory();
            else if (type == Material.ENDER_CHEST) inv = player.getEnderChest();

            if (inv != null) player.openInventory(inv);
            else {
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
                p.setGameMode(gm);
                if (config.enableFly && gm != GameMode.CREATIVE && gm != GameMode.SPECTATOR && plugin.isVanished(p)) {
                    p.setAllowFlight(true);
                    p.setFlying(true);
                }
            }
        }
    }

    private void sendRuleDeny(Player p, String ruleName, String actionName) {
        plugin.triggerActionBarWarning(p, Component.text("✖ Action Blocked: " + actionName, NamedTextColor.RED, TextDecoration.BOLD));

        if (!rules.getRule(p, RuleManager.SHOW_NOTIFICATIONS)) return;

        UUID uuid = p.getUniqueId();
        long now = System.currentTimeMillis();
        Map<String, Long> playerCooldowns = ruleNotificationCooldowns.computeIfAbsent(uuid, k -> new HashMap<>());
        long lastTime = playerCooldowns.getOrDefault(ruleName, 0L);

        if (now - lastTime < 60000) return;
        playerCooldowns.put(ruleName, now);

        Component message = Component.text("Vanish blocked " + actionName + ". ", NamedTextColor.RED)
                .append(Component.text("[ENABLE]", NamedTextColor.GREEN, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/vrules " + ruleName + " true"))
                        .hoverEvent(HoverEvent.showText(Component.text("Click to allow", NamedTextColor.GREEN))))
                .append(Component.text(" | ", NamedTextColor.GRAY))
                .append(Component.text("[ENABLE 1m]", NamedTextColor.YELLOW, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/vrules " + ruleName + " true 60"))
                        .hoverEvent(HoverEvent.showText(Component.text("Enable for 60 seconds", NamedTextColor.GOLD))));

        p.sendMessage(message);

        if (!hasSeenDisableTip.contains(uuid)) {
            hasSeenDisableTip.add(uuid);
            p.sendMessage(Component.text("Tip: Type /vignore to disable these warnings.", NamedTextColor.GRAY));
        }
    }
}