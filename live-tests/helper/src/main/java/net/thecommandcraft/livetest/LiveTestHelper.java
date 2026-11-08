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
