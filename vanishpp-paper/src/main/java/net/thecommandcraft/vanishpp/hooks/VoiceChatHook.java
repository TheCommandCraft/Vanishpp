package net.thecommandcraft.vanishpp.hooks;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.events.*;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public class VoiceChatHook implements de.maxhenkel.voicechat.api.VoicechatPlugin {
    private final Vanishpp plugin;

    public VoiceChatHook(Vanishpp plugin) {
        this.plugin = plugin;
        var provider = Bukkit.getServicesManager().getRegistration(BukkitVoicechatService.class);
        if (provider != null) provider.getProvider().registerPlugin(this);
    }

    @Override
    public String getPluginId() { return "vanishpp"; }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(LocationalSoundPacketEvent.class, this::filter);
        registration.registerEvent(EntitySoundPacketEvent.class, this::filter);
        registration.registerEvent(StaticSoundPacketEvent.class, this::filter);
    }

    private void filter(SoundPacketEvent<?> event) {
        if (!plugin.isEnabled() || !plugin.getConfigManager().voiceChatIsolate) return;
        var from = event.getSenderConnection();
        var to = event.getReceiverConnection();
        if (from == null) return;
        Player sender = Bukkit.getPlayer(from.getPlayer().getUuid());
        Player receiver = to == null ? null : Bukkit.getPlayer(to.getPlayer().getUuid());
        if (!plugin.getPermissionManager().canSee(receiver, from.getPlayer().getUuid())
                || (receiver != null && plugin.isVanished(receiver)
                    && !plugin.getPermissionManager().canSee(sender, receiver))) event.cancel();
    }
}
