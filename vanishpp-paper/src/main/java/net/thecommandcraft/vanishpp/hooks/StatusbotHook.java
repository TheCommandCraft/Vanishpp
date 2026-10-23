package net.thecommandcraft.vanishpp.hooks;

import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Hides vanished players from mopsy14's Statusbot (Discord bot status, embeds and join/leave messages).
 *
 * <p>Statusbot has no API and reads {@code getOnlinePlayers()} directly, so this hooks its internals
 * via reflection: its own join/quit handlers are replaced with vanish-aware ones and the
 * {@code amount-of-players}/{@code player-list} embed variables are overridden. Tested against
 * Statusbot 24.0 - if a later version renames anything, {@link #register()} throws and the hook
 * is simply not loaded; a failure at runtime disables the hook and restores Statusbot's defaults.
 */
public class StatusbotHook implements Listener {

    private static final String PKG = "mopsy.productions.discord.statusbot.";

    private final Vanishpp plugin;
    private final Plugin statusbot;

    private Method createStatusMessage;
    private Method createJoinMessage;
    private Method createLeaveMessage;
    private Method onPlayerJoined;
    private Method onPlayerLeft;
    private Method regVarSupplier;
    private Method regBot;
    private Method getStr;
    private Method regDefaultEmbedVarProviders;
    private Field jda;

    private boolean active;

    public StatusbotHook(Vanishpp plugin, Plugin statusbot) {
        this.plugin = plugin;
        this.statusbot = statusbot;
    }

    public void register() throws ReflectiveOperationException {
        ClassLoader cl = statusbot.getClass().getClassLoader();
        Class<?> parser = Class.forName(PKG + "Parser", true, cl);
        Class<?> main = Class.forName(PKG + "IStatusbotMain", true, cl);
        Class<?> embedManager = Class.forName(PKG + "EmbedManager", true, cl);
        Class<?> botManager = Class.forName(PKG + "BotManager", true, cl);
        Class<?> configManager = Class.forName(PKG + "ConfigManager", true, cl);

        if (!main.isInstance(statusbot) || !(statusbot instanceof Listener))
            throw new ClassNotFoundException("unexpected Statusbot main class " + statusbot.getClass().getName());

        createStatusMessage = parser.getDeclaredMethod("createStatusMessage", Supplier.class, int.class);
        createJoinMessage = parser.getDeclaredMethod("createJoinMessage", Supplier.class, String.class, int.class);
        createLeaveMessage = parser.getDeclaredMethod("createLeaveMessage", Supplier.class, String.class, int.class);
        createStatusMessage.setAccessible(true);
        createJoinMessage.setAccessible(true);
        createLeaveMessage.setAccessible(true);
        onPlayerJoined = main.getMethod("onPlayerJoined", String.class, String.class);
        onPlayerLeft = main.getMethod("onPlayerLeft", String.class, String.class);
        regDefaultEmbedVarProviders = main.getMethod("regDefaultEmbedVarProviders");
        regVarSupplier = embedManager.getMethod("regVarSupplier", String.class, Function.class);
        regBot = botManager.getMethod("regBot", String.class, String.class, main);
        jda = botManager.getField("jda");
        getStr = configManager.getMethod("getStr", String.class);

        // Statusbot registers itself as its only listener - drop just its join/quit handlers
        PlayerJoinEvent.getHandlerList().unregister((Listener) statusbot);
        PlayerQuitEvent.getHandlerList().unregister((Listener) statusbot);
        Bukkit.getPluginManager().registerEvents(this, plugin);

        Function<Object, String> amount = m -> statusbot.isEnabled() ? String.valueOf(visibleNames(null, null).size()) : "0";
        Function<Object, String> list = m -> statusbot.isEnabled()
                ? String.join(str("embed_player_separator_text", ", "), visibleNames(null, null)) : "";
        regVarSupplier.invoke(null, "amount-of-players", amount);
        regVarSupplier.invoke(null, "player-list", list);
        active = true;
        refreshStatus();
    }

    public void unregister() {
        if (!active)
            return;
        active = false;
        HandlerList.unregisterAll(this);
        if (!statusbot.isEnabled())
            return;
        try {
            Bukkit.getPluginManager().registerEvents((Listener) statusbot, statusbot);
            regDefaultEmbedVarProviders.invoke(statusbot);
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to restore Statusbot defaults: " + t.getMessage());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (plugin.isVanished(player))
            return;
        List<String> names = visibleNames(null, null);
        call(onPlayerJoined, status(names), invoke(createJoinMessage, supplier(names), player.getName(), names.size()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (plugin.isVanished(player))
            return;
        List<String> names = visibleNames(player, null);
        call(onPlayerLeft, status(names), invoke(createLeaveMessage, supplier(names), player.getName(), names.size()));
    }

    /** Announces a fake leave for a player who just vanished. */
    public void sendFakeQuit(Player player) {
        List<String> names = visibleNames(player, null);
        call(onPlayerLeft, status(names), invoke(createLeaveMessage, supplier(names), player.getName(), names.size()));
    }

    /** Announces a fake join for a player who is about to unvanish. */
    public void sendFakeJoin(Player player) {
        List<String> names = visibleNames(null, player);
        call(onPlayerJoined, status(names), invoke(createJoinMessage, supplier(names), player.getName(), names.size()));
    }

    /** Updates the bot's activity text after a vanish state change without sending a message. */
    public void refreshStatus() {
        if (!active)
            return;
        try {
            // regBot logs in when no bot is running yet - only update an existing one
            if (jda.get(null) == null)
                return;
            regBot.invoke(null, str("bot_token", null), status(visibleNames(null, null)), statusbot);
        } catch (Throwable t) {
            fail(t);
        }
    }

    /**
     * Online player names that aren't vanished.
     *
     * @param exclude player to leave out even if visible (quitting / just vanished), or null
     * @param include player to add even if vanished (about to unvanish), or null
     */
    private List<String> visibleNames(Player exclude, Player include) {
        return Bukkit.getOnlinePlayers().stream()
                .filter(p -> !p.equals(exclude))
                .filter(p -> p.equals(include) || !plugin.isVanished(p))
                .map(Player::getName)
                .toList();
    }

    private static Supplier<List<String>> supplier(List<String> names) {
        return () -> names;
    }

    private String status(List<String> names) {
        return invoke(createStatusMessage, supplier(names), names.size());
    }

    private String str(String key, String def) {
        String value = invoke(getStr, key);
        return value != null ? value : def;
    }

    private String invoke(Method method, Object... args) {
        if (!active)
            return null;
        try {
            return (String) method.invoke(null, args);
        } catch (Throwable t) {
            fail(t);
            return null;
        }
    }

    private void call(Method method, String status, String message) {
        if (!active)
            return;
        try {
            method.invoke(statusbot, status, message);
        } catch (Throwable t) {
            fail(t);
        }
    }

    private void fail(Throwable t) {
        if (t instanceof InvocationTargetException ite && ite.getCause() != null)
            t = ite.getCause();
        plugin.getLogger().warning("Statusbot hook failed, disabling it (unsupported Statusbot version?): " + t);
        unregister();
    }
}
