package net.thecommandcraft.vanishpp.storage;

import java.util.Set;
import java.util.UUID;
import java.util.Map;

public interface StorageProvider {

    void init() throws Exception;

    void shutdown();

    // Vanish State
    boolean isVanished(UUID uuid);

    void setVanished(UUID uuid, boolean vanished);

    Set<UUID> getVanishedPlayers();

    // Rules
    boolean getRule(UUID uuid, String rule, boolean defaultValue);

    void setRule(UUID uuid, String rule, Object value);

    Map<String, Object> getRules(UUID uuid);

    // Metadata / Acknowledgements
    boolean hasAcknowledged(UUID uuid, String notificationId);

    void addAcknowledgement(UUID uuid, String notificationId);

    // Permission Levels (Advanced Tracking)
    int getVanishLevel(UUID uuid);

    void setVanishLevel(UUID uuid, int level);

    // Cleanup
    void removePlayerData(UUID uuid);
}
