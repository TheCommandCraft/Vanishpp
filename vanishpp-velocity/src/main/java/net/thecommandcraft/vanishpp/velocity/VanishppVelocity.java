package net.thecommandcraft.vanishpp.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import net.thecommandcraft.vanishpp.common.protocol.VppChannel;
import net.thecommandcraft.vanishpp.velocity.commands.VanishReloadCommand;
import net.thecommandcraft.vanishpp.velocity.config.VelocityConfigManager;
import net.thecommandcraft.vanishpp.velocity.listener.VelocityPlayerListener;
import net.thecommandcraft.vanishpp.velocity.messaging.PaperChannelDispatcher;
import net.thecommandcraft.vanishpp.velocity.messaging.PaperChannelListener;
import org.slf4j.Logger;

import java.nio.file.Path;

@Plugin(
    id = "vanishpp-velocity",
    name = "Vanish++ Velocity",
    version = "${project.version}",
    description = "Velocity proxy component for Vanish++. Provides cross-server vanish sync and centralized config management.",
    authors = {"TheCommandCraft"}
)
public class VanishppVelocity {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private VelocityConfigManager configManager;
    private ProxyStateManager stateManager;
    private PaperChannelDispatcher dispatcher;

    @Inject
    public VanishppVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        // 1. Config
        configManager = new VelocityConfigManager(dataDirectory, logger);
        configManager.load();

        // 2. State (DB connection)
        stateManager = new ProxyStateManager(this, configManager);
        stateManager.init();

        // 3. Messaging
        dispatcher = new PaperChannelDispatcher(proxy);

        // Register plugin messaging channel
        MinecraftChannelIdentifier channel = MinecraftChannelIdentifier.from(VppChannel.CHANNEL);
        proxy.getChannelRegistrar().register(channel);

        // 4. Event listeners
        proxy.getEventManager().register(this, new PaperChannelListener(this, stateManager, dispatcher, configManager));
        proxy.getEventManager().register(this, new VelocityPlayerListener(this, stateManager, dispatcher));

        // 5. Commands
        var meta = proxy.getCommandManager().metaBuilder("vanishreload")
                .aliases("vreload")
                .plugin(this)
                .build();
        proxy.getCommandManager().register(meta, new VanishReloadCommand(this, dispatcher));

        logger.info("Vanish++ Velocity {} enabled. Channel: {}", proxy.getVersion().getVersion(), VppChannel.CHANNEL);
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (stateManager != null) stateManager.shutdown();
        logger.info("Vanish++ Velocity disabled.");
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public ProxyServer getProxy()                  { return proxy; }
    public Logger getLogger()                      { return logger; }
    public VelocityConfigManager getConfigManager(){ return configManager; }
    public ProxyStateManager getStateManager()     { return stateManager; }
    public PaperChannelDispatcher getDispatcher()  { return dispatcher; }
}
