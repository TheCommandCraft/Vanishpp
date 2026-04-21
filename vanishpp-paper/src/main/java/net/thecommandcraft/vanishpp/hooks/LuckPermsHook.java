package net.thecommandcraft.vanishpp.hooks;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.context.ContextCalculator;
import net.luckperms.api.context.ContextConsumer;
import net.luckperms.api.context.ContextSet;
import net.luckperms.api.context.ImmutableContextSet;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Registers {@code vanishpp:vanished=true/false} as a LuckPerms context
 * so that permissions can be conditionally granted while vanished.
 *
 * <p>Requires LuckPerms on the classpath (soft-depend in plugin.yml).
 */
public class LuckPermsHook {

    private static final String CONTEXT_KEY = "vanishpp:vanished";

    private final Vanishpp plugin;
    private LuckPerms luckPerms;
    private ContextCalculator<Player> calculator;

    public LuckPermsHook(Vanishpp plugin) {
        this.plugin = plugin;
    }

    public void load() {
        org.bukkit.plugin.RegisteredServiceProvider<LuckPerms> provider =
                Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        if (provider == null) throw new IllegalStateException("LuckPerms service not found");
        this.luckPerms = provider.getProvider();

        this.calculator = new ContextCalculator<Player>() {
            @Override
            public void calculate(@NotNull Player player, @NotNull ContextConsumer consumer) {
                consumer.accept(CONTEXT_KEY, plugin.isVanished(player) ? "true" : "false");
            }

            @Override
            public @NotNull ContextSet estimatePotentialContexts() {
                return ImmutableContextSet.builder()
                        .add(CONTEXT_KEY, "true")
                        .add(CONTEXT_KEY, "false")
                        .build();
            }
        };
        luckPerms.getContextManager().registerCalculator(this.calculator);
    }

    public void unload() {
        if (luckPerms != null && calculator != null) {
            try { luckPerms.getContextManager().unregisterCalculator(calculator); } catch (Throwable ignored) {}
            calculator = null;
        }
    }

    /**
     * Signal a vanish-state change to LuckPerms so it invalidates context caches
     * for the affected player immediately (rather than waiting for the next query).
     */
    public void setVanished(Player player, boolean vanished) {
        if (luckPerms == null) return;
        try {
            luckPerms.getContextManager().signalContextUpdate(player);
        } catch (Throwable ignored) {
            // LP API version differences — best-effort
        }
    }
}
