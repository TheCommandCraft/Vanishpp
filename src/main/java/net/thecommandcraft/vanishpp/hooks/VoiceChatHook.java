package net.thecommandcraft.vanishpp.hooks;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.RegisteredServiceProvider;

public class VoiceChatHook implements Listener {

    private final Vanishpp plugin;
    private VoicechatServerApi api;

    public VoiceChatHook(Vanishpp plugin) {
        this.plugin = plugin;

        // Use ServicesManager to get the API registration
        RegisteredServiceProvider<BukkitVoicechatService> provider = Bukkit.getServicesManager()
                .getRegistration(BukkitVoicechatService.class);
        if (provider != null) {
            BukkitVoicechatService service = provider.getProvider();
            service.registerPlugin(new VoiceChatPlugin(this));
        }
    }

    public void setApi(VoicechatServerApi api) {
        this.api = api;
    }

    public void updateVanishState(Player player, boolean isVanished) {
        if (api == null)
            return;
        if (!plugin.getConfigManager().voiceChatIsolate)
            return;

        VoicechatConnection connection = api.getConnectionOf(player.getUniqueId());
        if (connection != null) {
            connection.setDisabled(isVanished);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.getVanishScheduler().runLaterGlobal(() -> {
            if (plugin.isVanished(event.getPlayer())) {
                updateVanishState(event.getPlayer(), true);
            }
        }, 20L);
    }

    private static class VoiceChatPlugin implements de.maxhenkel.voicechat.api.VoicechatPlugin {
        private final VoiceChatHook hook;

        public VoiceChatPlugin(VoiceChatHook hook) {
            this.hook = hook;
        }

        @Override
        public String getPluginId() {
            return "vanishpp";
        }

        @Override
        public void registerEvents(de.maxhenkel.voicechat.api.events.EventRegistration registration) {
            registration.registerEvent(de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent.class, e -> {
                hook.setApi(e.getVoicechat());
            });
        }
    }
}