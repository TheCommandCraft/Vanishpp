package net.thecommandcraft.vanishpp.velocity.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.thecommandcraft.vanishpp.common.state.NetworkVanishState;
import net.thecommandcraft.vanishpp.velocity.ProxyStateManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Velocity-side /vlist command.
 * Reads directly from ProxyStateManager — works even when backend servers are offline.
 */
public class VanishListCommand implements SimpleCommand {

    private final ProxyStateManager stateManager;

    public VanishListCommand(ProxyStateManager stateManager) {
        this.stateManager = stateManager;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        Set<NetworkVanishState> all = stateManager.getAllVanishedPlayers();

        if (all.isEmpty()) {
            source.sendMessage(Component.text("No players are currently vanished.", NamedTextColor.YELLOW));
            return;
        }

        source.sendMessage(Component.text("Vanished Players (" + all.size() + "):", NamedTextColor.GOLD));

        List<NetworkVanishState> sorted = new ArrayList<>(all);
        sorted.sort(Comparator.comparing(NetworkVanishState::serverName)
                              .thenComparing(NetworkVanishState::playerName));

        Component line = Component.empty();
        for (int i = 0; i < sorted.size(); i++) {
            NetworkVanishState s = sorted.get(i);
            Component entry = Component.text(s.playerName(), NamedTextColor.GRAY)
                    .append(Component.text(" [" + s.serverName() + "]", NamedTextColor.DARK_AQUA));
            line = line.append(entry);
            if (i < sorted.size() - 1) line = line.append(Component.text(", ", NamedTextColor.DARK_GRAY));
        }
        source.sendMessage(line);
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("vanishpp.list");
    }
}
