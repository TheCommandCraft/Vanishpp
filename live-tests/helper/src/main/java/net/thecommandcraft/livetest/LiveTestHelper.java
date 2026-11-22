package net.thecommandcraft.livetest;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.stream.Collectors;

/** Test-only plugin used by the live bot tests. */
public final class LiveTestHelper extends JavaPlugin {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equals("ltnames")) return true;
        // ltprobe targets: prints every mob's current target, one line each.
        if (args.length == 1 && args[0].equals("targets")) {
            for (var world : Bukkit.getWorlds()) {
                for (Mob mob : world.getEntitiesByClass(Mob.class)) {
                    var target = mob.getTarget();
                    getLogger().info("LTPROBE target " + mob.getType().name().toLowerCase() + " " + mob.getUniqueId()
                            + " -> " + (target instanceof Player p ? p.getName() : target == null ? "none" : target.getType().name().toLowerCase()));
                }
            }
            getLogger().info("LTPROBE end");
            return true;
        }
        // ltprobe state <player>: one line with everything the bot reset must restore.
        if (args.length == 2 && args[0].equals("state")) {
            Player p = Bukkit.getPlayerExact(args[1]);
            if (p == null) { getLogger().info("LTPROBE state " + args[1] + " offline"); return true; }
            boolean vanished = p.getMetadata("vanished").stream().anyMatch(v -> v.asBoolean());
            int items = 0;
            for (var stack : p.getInventory().getContents()) if (stack != null && !stack.getType().isAir()) items++;
            getLogger().info("LTPROBE state " + p.getName() + " vanished=" + vanished + " op=" + p.isOp()
                    + " health=" + (int) Math.ceil(p.getHealth()) + " effects=" + p.getActivePotionEffects().size()
                    + " items=" + items + " gamemode=" + p.getGameMode().name().toLowerCase()
                    + " sleepingIgnored=" + p.isSleepingIgnored());
            return true;
        }
        // ltprobe plugin <disable|enable> <name>: what /reload and plugin managers do to one plugin at runtime.
        if (args.length == 3 && args[0].equals("plugin")) {
            var target = Bukkit.getPluginManager().getPlugin(args[2]);
            if (target == null) { getLogger().info("LTPROBE plugin " + args[2] + " missing"); return true; }
            if (args[1].equals("disable")) Bukkit.getPluginManager().disablePlugin(target);
            else Bukkit.getPluginManager().enablePlugin(target);
            getLogger().info("LTPROBE plugin " + args[2] + " enabled=" + target.isEnabled());
            return true;
        }
        // ltprobe listname <player> [text|reset]: sets the tab-list name like another plugin would (EssentialsX
        // nicknames, tab plugins), or prints the server's current one.
        if (args.length >= 2 && args[0].equals("listname")) {
            Player p = Bukkit.getPlayerExact(args[1]);
            if (p == null) { getLogger().info("LTPROBE listname " + args[1] + " offline"); return true; }
            if (args.length >= 3) {
                String text = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
                p.playerListName(text.equals("reset") ? null
                        : net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(text));
            }
            getLogger().info("LTPROBE listname " + p.getName() + " = "
                    + net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(p.playerListName()));
            return true;
        }
        // ltprobe unack <player> <id>: removes a stored Vanish++ acknowledgement so a test can be repeated.
        // Reaches into Vanish++ by reflection on purpose: this is test-only and never shipped.
        if (args.length == 3 && args[0].equals("unack")) {
            try {
                var vanishpp = Bukkit.getPluginManager().getPlugin("Vanishpp");
                Object storage = vanishpp.getClass().getMethod("getStorageProvider").invoke(vanishpp);
                var remove = Class.forName("net.thecommandcraft.vanishpp.storage.StorageProvider", true, vanishpp.getClass().getClassLoader())
                        .getMethod("removeAcknowledgement", java.util.UUID.class, String.class);
                remove.invoke(storage, Bukkit.getOfflinePlayer(args[1]).getUniqueId(), args[2]);
                getLogger().info("LTPROBE unack " + args[1] + " " + args[2] + " done");
            } catch (ReflectiveOperationException e) {
                getLogger().warning("LTPROBE unack " + args[1] + " " + args[2] + " failed: " + e);
            }
            return true;
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equals("ltnames") || args.length != 1) return List.of();
        // Deliberately ignores Player#canSee: this is what many plugins do.
        return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                .filter(n -> n.toLowerCase().startsWith(args[0].toLowerCase())).collect(Collectors.toList());
    }
}
