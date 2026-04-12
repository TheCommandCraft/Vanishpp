package net.thecommandcraft.vanishpp.commands;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

/**
 * /vwand — gives the configured vanish wand item.
 * Requires vanishpp.wand permission.
 */
public class VanishWandCommand implements CommandExecutor {

    private final Vanishpp plugin;

    public VanishWandCommand(Vanishpp plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getMessageManager().sendMessage(sender,
                    plugin.getConfigManager().getLanguageManager().getMessage("console-specify"));
            return true;
        }
        if (!player.hasPermission("vanishpp.wand")) {
            plugin.getMessageManager().sendMessage(player,
                    plugin.getConfigManager().getLanguageManager().getMessage("unknown-command"));
            return true;
        }
        if (!plugin.getConfigManager().wandEnabled) {
            plugin.getMessageManager().sendMessage(player,
                    plugin.getConfigManager().getLanguageManager().getMessage("wand.disabled"));
            return true;
        }

        ItemStack wand = buildWand();
        player.getInventory().addItem(wand);
        plugin.getMessageManager().sendMessage(player,
                plugin.getConfigManager().getLanguageManager().getMessage("wand.given"));
        return true;
    }

    private ItemStack buildWand() {
        Material mat;
        try {
            mat = Material.valueOf(plugin.getConfigManager().wandMaterial.toUpperCase());
        } catch (IllegalArgumentException e) {
            mat = Material.BLAZE_ROD;
        }
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String displayName = plugin.getConfigManager().wandDisplayName;
            meta.displayName(plugin.getMessageManager().parse(displayName, null));
            item.setItemMeta(meta);
        }
        return item;
    }
}
