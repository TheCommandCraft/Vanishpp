package net.thecommandcraft.vanishpp.velocity.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.thecommandcraft.vanishpp.common.config.ProxyConfigSnapshot;
import net.thecommandcraft.vanishpp.velocity.VanishppVelocity;
import net.thecommandcraft.vanishpp.velocity.messaging.PaperChannelDispatcher;

import java.util.HashMap;
import java.util.Map;

/**
 * Velocity-side /vanishreload command.
 * Reloads the proxy config and pushes it to all connected Paper servers.
 */
public class VanishReloadCommand implements SimpleCommand {

    private final VanishppVelocity plugin;
    private final PaperChannelDispatcher dispatcher;

    public VanishReloadCommand(VanishppVelocity plugin, PaperChannelDispatcher dispatcher) {
        this.plugin = plugin;
        this.dispatcher = dispatcher;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();

        if (!source.hasPermission("vanishpp.reload")) {
            source.sendMessage(Component.text("You don't have permission to use this command.", NamedTextColor.RED));
            return;
        }

        plugin.getConfigManager().reload();

        Map<String, ProxyConfigSnapshot> perServer = new HashMap<>();
        for (var server : plugin.getProxy().getAllServers()) {
            String name = server.getServerInfo().getName();
            perServer.put(name, plugin.getConfigManager().getSnapshot().applyOverride(name));
        }
        dispatcher.pushConfigToAll(plugin.getProxy(), perServer);

        source.sendMessage(Component.text("[Vanish++ Proxy] Config reloaded and pushed to all servers.", NamedTextColor.GREEN));
        plugin.getLogger().info("Config reloaded via command by {}.", source);
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("vanishpp.reload");
    }
}
