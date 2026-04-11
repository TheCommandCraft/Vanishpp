package net.thecommandcraft.vanishpp.commands;

import net.thecommandcraft.vanishpp.Vanishpp;
import net.thecommandcraft.vanishpp.utils.LanguageManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;

public class VanishAckCommand implements TabExecutor {

    private final Vanishpp plugin;

    public VanishAckCommand(Vanishpp plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            @NotNull String[] args) {
        if (!(sender instanceof Player p))
            return true;

        LanguageManager lm = plugin.getConfigManager().getLanguageManager();

        // Smart: no args = acknowledge the latest notification
        if (args.length == 0) {
            // Default to acknowledging the most recent notification type
            plugin.getStorageProvider().addAcknowledgement(p.getUniqueId(),
                    "hiding_v" + plugin.getConfigManager().getLatestVersion());
            plugin.getMessageManager().sendMessage(p, lm.getMessage("acknowledgement.acknowledged"));
            return true;
        }

        String action = args[0];

        switch (action) {
            case "migration" -> {
                plugin.getStorageProvider().addAcknowledgement(p.getUniqueId(),
                        "migration_v" + plugin.getConfigManager().getLatestVersion());
                plugin.getMessageManager().sendMessage(p, lm.getMessage("acknowledgement.acknowledged"));
            }
            case "disable_hiding" -> {
                if (!p.hasPermission("vanishpp.config")) {
                    plugin.getMessageManager().sendMessage(p, lm.getMessage("unknown-command"));
                    return true;
                }
                plugin.getConfigManager().setAndSave("hide-announcements.hide-from-plugin-list", false);
                plugin.getMessageManager().sendMessage(p, lm.getMessage("acknowledgement.hiding-visible"));
            }
            case "acknowledge_hiding" -> {
                plugin.getStorageProvider().addAcknowledgement(p.getUniqueId(),
                        "hiding_v" + plugin.getConfigManager().getLatestVersion());
                plugin.getMessageManager().sendMessage(p, lm.getMessage("acknowledgement.hiding-dismissed"));
            }
            case "proxy_config" -> {
                plugin.getStorageProvider().addAcknowledgement(p.getUniqueId(),
                        "proxy_config_v" + plugin.getConfigManager().getLatestVersion());
                plugin.getMessageManager().sendMessage(p, lm.getMessage("acknowledgement.acknowledged"));
            }
            case "apply_proxy" -> {
                var bridge = plugin.getProxyBridge();
                if (bridge == null || !bridge.isProxyDetected()) {
                    plugin.getMessageManager().sendMessage(p, lm.getMessage("config.proxy-not-connected"));
                    return true;
                }
                java.util.Map<String, String> nonDefaults = plugin.getConfigManager().getNonDefaultValues();
                if (nonDefaults.isEmpty()) {
                    plugin.getMessageManager().sendMessage(p, lm.getMessage("config.proxy-nothing-to-sync"));
                    return true;
                }
                bridge.sendConfigSync(nonDefaults);
                // Also dismiss the warning
                plugin.getStorageProvider().addAcknowledgement(p.getUniqueId(),
                        "proxy_config_v" + plugin.getConfigManager().getLatestVersion());
                plugin.getMessageManager().sendMessage(p,
                        lm.getMessage("config.proxy-applied").replace("%count%", String.valueOf(nonDefaults.size())));
            }
            default -> plugin.getMessageManager().sendMessage(p, lm.getMessage("acknowledgement.acknowledged"));
        }

        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        return Collections.emptyList();
    }
}
