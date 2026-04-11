package net.thecommandcraft.vanishpp.common.protocol;

/**
 * All message types exchanged over the vanishpp:proxy plugin messaging channel.
 * The ordinal of each constant is used as the 1-byte type prefix in every packet.
 * Do NOT reorder or remove entries — ordinal values must remain stable.
 */
public enum VppMessage {
    // ── Paper → Proxy ───────────────────────────────────────────────────────
    /** Paper announces itself to the proxy. Payload: { serverName, pluginVersion } */
    HELLO,
    /** A player vanished or unvanished on this server. Payload: { uuid, playerName, vanished, vanishLevel } */
    VANISH_EVENT,
    /** Paper requests the full vanished-player set from the proxy. Payload: {} */
    STATE_QUERY,
    /** Paper requests an up-to-date config push from the proxy. Payload: {} */
    CONFIG_REQUEST,
    /** /vanishreload on a Paper server — proxy re-reads its config and pushes to all. Payload: { requestedBy } */
    RELOAD_REQUEST,
    /** Paper requests the cross-server vanished player list (for /vanishlist). Payload: { requestId } */
    PLAYER_LIST_QUERY,

    // ── Proxy → Paper ───────────────────────────────────────────────────────
    /** Proxy confirms it is a VanishPP proxy (response to HELLO). Payload: { proxyVersion } */
    PONG,
    /** Proxy pushes merged config (global + server-specific overrides) to a Paper server. Payload: { config } */
    CONFIG_PUSH,
    /** Proxy broadcasts a vanish state change to all Paper servers except the origin. Payload: { uuid, vanished, serverName } */
    VANISH_SYNC,
    /** Proxy responds to STATE_QUERY with the full set of currently vanished players. Payload: { vanished: [...] } */
    STATE_RESPONSE,
    /** Proxy responds to PLAYER_LIST_QUERY. Payload: { requestId, players: [...] } */
    PLAYER_LIST_RESPONSE,
    /**
     * Proxy notifies Paper servers that the Velocity proxy plugin has an update available.
     * Sent to all servers on startup (if update known) and immediately after the update check completes.
     * Payload: { currentVersion, latestVersion, downloadUrl }
     */
    PROXY_UPDATE_NOTIFY,
    /**
     * Paper sends a map of config key → value pairs to apply on the proxy (and push to all servers).
     * Used when /vconfig is run in proxy mode, or when "Apply to Proxy" is clicked.
     * Payload: { entries: { "dotted.key": "value", ... } }
     */
    CONFIG_SYNC,
    /**
     * Paper asks the proxy to deliver a chat message to a specific player by UUID, on whatever
     * server they are currently connected to. Used for cross-server notifications such as
     * timed rule expiry messages when the target has switched servers since the timer was scheduled.
     * Payload: { uuid, message }
     */
    PLAYER_MESSAGE,
}
