package net.thecommandcraft.vanishpp.common.state;

import java.util.UUID;

/**
 * Represents a vanished player's state as seen across the proxy network.
 */
public record NetworkVanishState(UUID uuid, String playerName, String serverName, int vanishLevel) {

    /** Convenience: create from raw strings (UUID parsed from string). */
    public static NetworkVanishState of(String uuid, String playerName, String serverName, int vanishLevel) {
        return new NetworkVanishState(UUID.fromString(uuid), playerName, serverName, vanishLevel);
    }
}
