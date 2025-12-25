package net.thecommandcraft.vanishpp;

import com.destroystokyo.paper.event.entity.ProjectileCollideEvent;
import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
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

import java.util.*;

public class PlayerListener implements Listener {

    private final Vanishpp plugin;
    private final ConfigManager config;
    private final RuleManager rules;
    private final Map<UUID, GameMode> silentChestViewers = new HashMap<>();

    public PlayerListener(Vanishpp plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        this.rules = plugin.getRuleManager();
    }

    // LOWEST: Hide them BEFORE plugins process join messages or spawns
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // 1. If they were vanished, hide them immediately from everyone
        if (plugin.isVanished(player)) {
            plugin.applyVanishEffects(player);
            // Hide from everyone initially
            plugin.updateVanishVisibility(player);

            if (config.fakeJoinMessage) event.joinMessage(null);
        }

        // 2. Hide EXISTING vanished players from this new joiner if they can't see them
        for (UUID uuid : plugin.getRawVanishedPlayers()) {
            Player v = plugin.getServer().getPlayer(uuid);
            if (v != null) {
                if (!plugin.getPermissionManager().canSee(player, v)) {
                    player.hidePlayer(plugin, v);
                }
            }
        }

        if (!plugin.hasProtocolLib() && player.isOp() && !plugin.isWarningIgnored(player)) {
            player.sendMessage(Component.empty());
            player.sendMessage(Component.text("[Vanish++]", NamedTextColor.GOLD).append(Component.text(" ProtocolLib missing!", NamedTextColor.RED)));
            player.sendMessage(Component.text("Type /vignore to disable warning.", NamedTextColor.GRAY));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent event) {
        if (plugin.isVanished(event.getPlayer())) {
            if (config.fakeLeaveMessage) event.quitMessage(null);
            silentChestViewers.remove(event.getPlayer().getUniqueId());
            plugin.pendingChatMessages.remove(event.getPlayer().getUniqueId());
        }
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
                player.sendMessage(Component.text(config.chatLockedMessage, NamedTextColor.RED)
                        .clickEvent(ClickEvent.runCommand("/vchat confirm"))
                        .hoverEvent(Component.text("Click to confirm", NamedTextColor.GRAY)));
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
                sendRuleDeny(event.getPlayer(), RuleManager.CAN_BREAK_BLOCKS);
            }
        }
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        if (plugin.isVanished(event.getPlayer())) {
            if (!rules.getRule(event.getPlayer(), RuleManager.CAN_PLACE_BLOCKS)) {
                event.setCancelled(true);
                sendRuleDeny(event.getPlayer(), RuleManager.CAN_PLACE_BLOCKS);
            }
        }
    }

    @EventHandler
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && plugin.isVanished(player)) {
            if (!rules.getRule(player, RuleManager.CAN_HIT_ENTITIES)) {
                event.setCancelled(true);
                sendRuleDeny(player, RuleManager.CAN_HIT_ENTITIES);
            }
        }
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && plugin.isVanished(player)) {
            if (!rules.getRule(player, RuleManager.CAN_PICKUP_ITEMS)) {
                event.setCancelled(true);
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

        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            if (!rules.getRule(p, RuleManager.CAN_INTERACT)) {
                event.setCancelled(true);
                sendRuleDeny(p, RuleManager.CAN_INTERACT);
                return;
            }
            handleSilentChest(event);
        }
    }

    @EventHandler
    public void onMobTarget(EntityTargetLivingEntityEvent event) {
        if (event.getTarget() instanceof Player p && plugin.isVanished(p)) {
            if (!rules.getRule(p, RuleManager.MOB_TARGETING)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (plugin.isVanished(event.getPlayer()) && !rules.getRule(event.getPlayer(), RuleManager.CAN_INTERACT)) {
            event.setCancelled(true);
        }
    }

    // SERVER LIST FIX: Remove vanished players from the hover sample
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

            // Remove from sample list (Hover text)
            // FIX: Use id() instead of getId() or getUniqueId() for Java Records
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
    @EventHandler
    public void onRaidTrigger(RaidTriggerEvent event) {
        if (config.preventRaid && plugin.isVanished(event.getPlayer())) event.setCancelled(true);
    }
    @EventHandler
    public void onBedEnter(PlayerBedEnterEvent event) {
        if (config.preventSleeping && plugin.isVanished(event.getPlayer())) event.setCancelled(true);
    }
    @EventHandler
    public void onHunger(FoodLevelChangeEvent event) {
        if (config.disableHunger && event.getEntity() instanceof Player p && plugin.isVanished(p)) event.setCancelled(true);
    }
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (config.hideDeathMessages && plugin.isVanished(event.getEntity())) event.deathMessage(null);
    }

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

    private void sendRuleDeny(Player p, String rule) {
        p.sendMessage(Component.text("Action blocked by rule: ", NamedTextColor.RED)
                .append(Component.text(rule, NamedTextColor.GOLD))
                .append(Component.text(" (Click to Enable)", NamedTextColor.GRAY)
                        .clickEvent(ClickEvent.runCommand("/vrules " + rule + " true"))
                        .hoverEvent(Component.text("Enable " + rule, NamedTextColor.YELLOW))));
    }
}