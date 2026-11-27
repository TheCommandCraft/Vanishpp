package net.thecommandcraft.livetest.conflict;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/** Registers /vanish (alias /v) at runtime through the command map, the way DeluxeHub's command framework does. */
public final class LiveTestConflict extends JavaPlugin {
    @Override
    public void onEnable() {
        Plugin self = this;
        Command vanish = new Command("vanish", "Another plugin's vanish", "/vanish", List.of("v")) {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                sender.sendMessage("LTCONFLICT vanish ran");
                getLogger().info("LTCONFLICT vanish ran for " + sender.getName());
                return true;
            }
        };
        class Owned extends Command implements PluginIdentifiableCommand {
            Owned() { super(vanish.getName(), vanish.getDescription(), vanish.getUsage(), vanish.getAliases()); }
            @Override public boolean execute(CommandSender s, String l, String[] a) { return vanish.execute(s, l, a); }
            @Override public Plugin getPlugin() { return self; }
        }
        boolean got = Bukkit.getCommandMap().register("livetestconflict", new Owned());
        getLogger().info("LTCONFLICT registered /vanish first=" + got);
    }
}
