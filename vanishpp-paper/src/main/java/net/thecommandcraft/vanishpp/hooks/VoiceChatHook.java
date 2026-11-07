package net.thecommandcraft.vanishpp.hooks;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.events.*;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.Bukkit;

public class VoiceChatHook implements de.maxhenkel.voicechat.api.VoicechatPlugin {
    private final Vanishpp plugin;

    public VoiceChatHook(Vanishpp plugin) {
        this.plugin = plugin;
        plugin.voiceAudience.refresh();
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
        var from = event.getSenderConnection();
        var to = event.getReceiverConnection();
        if (from == null) return;
        if (!plugin.voiceAudience.canHear(to == null ? null : to.getPlayer().getUuid(), from.getPlayer().getUuid()))
            event.cancel();
    }
}
