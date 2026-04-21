package net.thecommandcraft.vanishpp.common.config;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.*;

/**
 * A serializable snapshot of all Vanish++ config values.
 *
 * <p>This POJO mirrors every field in ConfigManager exactly.
 * It is serialised to JSON by the Velocity proxy and deserialised by Paper servers.
 * Paper servers never write this to disk; they cache it in ProxyConfigCache.</p>
 *
 * <p>The {@code serverOverrides} map holds per-server flat-key overrides, e.g.
 * {@code {"survival": {"invisibility-features.god-mode": false}}}. Call
 * {@link #applyOverride(String)} to produce a merged copy for a specific server.</p>
 */
public class ProxyConfigSnapshot {

    // ── Appearance ──────────────────────────────────────────────────────────
    public String vanishTabPrefix = "&7[VANISHED] ";
    public String vanishNametagPrefix = "";
    public String actionBarText = "&bYou are currently VANISHED";
    public String vanishedPlayerFormat = "%prefix%&7%player%: %message%";
    public boolean actionBarEnabled = true;
    public boolean adjustServerListCount = true;
    public boolean hideFromServerList = true;
    public boolean staffGlowEnabled = true;

    // ── Vanish Effects ───────────────────────────────────────────────────────
    public boolean hideRealQuit = true;
    public boolean hideRealJoin = true;
    public boolean broadcastFakeQuit = true;
    public boolean broadcastFakeJoin = true;
    public boolean disableBlockTriggering = true;
    public boolean hideDeathMessages = true;
    public boolean hideAdvancements = true;
    public boolean hideFromPluginList = true;
    public boolean hideTabComplete = true;

    // ── Messages ─────────────────────────────────────────────────────────────
    public String fakeJoinMessage = "";
    public String fakeQuitMessage = "";
    public boolean staffNotifyEnabled = true;
    public boolean simulateEssentialsMessages = false;

    // ── Invisibility Features ────────────────────────────────────────────────
    public boolean enableNightVision = true;
    public boolean enableFly = true;
    public boolean disableFlyOnUnvanish = true;
    public boolean disableMobTarget = true;
    public boolean disableHunger = true;
    public boolean silentChests = true;
    public boolean ignoreProjectiles = true;
    public boolean preventRaid = true;
    public boolean preventSculk = true;
    public boolean preventTrample = true;
    public boolean preventSleeping = true;
    public boolean preventEntityInteract = true;
    public boolean preventAccidentalChat = true;
    public boolean godMode = true;
    public boolean preventPotions = false;
    public boolean vanishGamemodesEnabled = true;

    // ── Hooks ────────────────────────────────────────────────────────────────
    public boolean voiceChatEnabled = true;
    public boolean voiceChatIsolate = true;

    // ── Permissions / Layered ────────────────────────────────────────────────
    public boolean layeredPermsEnabled = true;
    public int defaultVanishLevel = 1;
    public int defaultSeeLevel = 1;
    public int maxLevel = 100;

    // ── Update Checker ───────────────────────────────────────────────────────
    public boolean updateCheckerEnabled = true;
    public String updateCheckerMode = "PERMISSION";
    public String updateCheckerId = "vanish++";
    public List<String> updateCheckerList = new ArrayList<>();
    public boolean updateCheckerShowBeta  = true;
    public boolean updateCheckerShowAlpha = true;

    // ── Scoreboard ───────────────────────────────────────────────────────────
    public boolean scoreboardEnabled = true;
    public boolean scoreboardAutoShow = true;

    // ── Language ─────────────────────────────────────────────────────────────
    public String language = "en";

    // ── Default Rules ────────────────────────────────────────────────────────
    public Map<String, Boolean> defaultRules = new LinkedHashMap<>();

    // ── Proxy-specific ───────────────────────────────────────────────────────
    /**
     * Per-server config overrides. Keys are server names (or "all"), values are
     * flat config-key → value maps, e.g. {@code "invisibility-features.god-mode" -> false}.
     * Populated by the Velocity proxy from the {@code servers:} config section.
     * Not written to disk by Paper — used only for merging in {@link #applyOverride}.
     */
    public Map<String, Map<String, Object>> serverOverrides = new LinkedHashMap<>();

    /** Whether /vanishlist should show players from all proxy servers. */
    public boolean crossServerList = true;

    // ── Serialization ────────────────────────────────────────────────────────

    private static final Gson GSON = new GsonBuilder().create();

    public String toJson() {
        return GSON.toJson(this);
    }

    public static ProxyConfigSnapshot fromJson(String json) {
        return GSON.fromJson(json, ProxyConfigSnapshot.class);
    }

    /**
     * Returns a copy of this snapshot with per-server overrides applied for the given server name.
     * Resolution order (lowest to highest priority):
     *   1. Global defaults (this snapshot)
     *   2. "all" overrides
     *   3. Specific server name overrides (including comma-separated entries)
     */
    public ProxyConfigSnapshot applyOverride(String serverName) {
        // Deep-copy via JSON round-trip to avoid mutating the original
        ProxyConfigSnapshot merged = fromJson(toJson());

        // Apply "all" first, then specific server
        List<String> keys = new ArrayList<>();
        keys.add("all");
        keys.add(serverName == null ? "" : serverName.trim().toLowerCase());

        for (String overrideKey : serverOverrides.keySet()) {
            boolean matches = false;
            // Split the key by comma (e.g. "lobby, hub") and check if serverName is in the list
            for (String part : overrideKey.split(",")) {
                String trimmed = part.trim().toLowerCase();
                if (trimmed.equals("all") || (serverName != null && trimmed.equals(serverName.trim().toLowerCase()))) {
                    matches = true;
                    break;
                }
            }
            if (!matches) continue;

            Map<String, Object> overrides = serverOverrides.get(overrideKey);
            if (overrides == null) continue;

            // Priority: "all" before specific; specific server overrides "all"
            // We process in the order: all entries first, then non-all entries second
            // This loop runs twice — once collecting "all" keys, once collecting specific keys
            // Simply applying them in iteration order is fine because serverOverrides is a LinkedHashMap
            // and the proxy inserts "all" before specific server keys.
            applyFlatOverrides(merged, overrides);
        }

        return merged;
    }

    /**
     * Applies a flat map of {@code "config.key" -> value} overrides onto a snapshot.
     * Supports the same dotted key paths used in config.yml.
     */
    private static void applyFlatOverrides(ProxyConfigSnapshot s, Map<String, Object> overrides) {
        for (Map.Entry<String, Object> entry : overrides.entrySet()) {
            String key = entry.getKey();
            Object val = entry.getValue();
            try {
                applyField(s, key, val);
            } catch (Exception ignored) {
                // Unknown key or type mismatch — silently skip
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void applyField(ProxyConfigSnapshot s, String key, Object val) {
        // Map flat config keys (matching config.yml paths) to ProxyConfigSnapshot fields
        switch (key) {
            // Appearance
            case "vanish-appearance.tab-prefix"                     -> s.vanishTabPrefix = str(val);
            case "vanish-appearance.nametag-prefix"                 -> s.vanishNametagPrefix = str(val);
            case "vanish-appearance.staff-glow"                     -> s.staffGlowEnabled = bool(val);
            case "vanish-appearance.action-bar.enabled"             -> s.actionBarEnabled = bool(val);
            case "vanish-appearance.adjust-server-list"             -> s.adjustServerListCount = bool(val);
            case "vanish-appearance.adjust-server-list-count"       -> s.adjustServerListCount = bool(val);
            // Vanish Effects
            case "vanish-effects.hide-from-server-list"             -> s.hideFromServerList = bool(val);
            case "vanish-effects.hide-real-quit-messages"           -> s.hideRealQuit = bool(val);
            case "vanish-effects.hide-real-join-messages"           -> s.hideRealJoin = bool(val);
            case "vanish-effects.broadcast-fake-quit"               -> s.broadcastFakeQuit = bool(val);
            case "vanish-effects.broadcast-fake-join"               -> s.broadcastFakeJoin = bool(val);
            case "vanish-effects.disable-block-triggering"          -> s.disableBlockTriggering = bool(val);
            // Hide Announcements
            case "hide-announcements.death-messages"                -> s.hideDeathMessages = bool(val);
            case "hide-announcements.advancements"                  -> s.hideAdvancements = bool(val);
            case "hide-announcements.hide-from-plugin-list"         -> s.hideFromPluginList = bool(val);
            // Invisibility Features
            case "invisibility-features.night-vision"               -> s.enableNightVision = bool(val);
            case "invisibility-features.disable-mob-targeting"      -> s.disableMobTarget = bool(val);
            case "invisibility-features.disable-hunger"             -> s.disableHunger = bool(val);
            case "invisibility-features.silent-chests"              -> s.silentChests = bool(val);
            case "invisibility-features.ignore-projectiles"         -> s.ignoreProjectiles = bool(val);
            case "invisibility-features.prevent-raid-trigger"       -> s.preventRaid = bool(val);
            case "invisibility-features.prevent-sculk-sensors"      -> s.preventSculk = bool(val);
            case "invisibility-features.prevent-trample"            -> s.preventTrample = bool(val);
            case "invisibility-features.hide-from-tab-complete"     -> s.hideTabComplete = bool(val);
            case "invisibility-features.prevent-sleeping"           -> s.preventSleeping = bool(val);
            case "invisibility-features.prevent-entity-interact"    -> s.preventEntityInteract = bool(val);
            case "invisibility-features.prevent-accidental-chat"    -> s.preventAccidentalChat = bool(val);
            case "invisibility-features.god-mode"                   -> s.godMode = bool(val);
            case "invisibility-features.prevent-potion-effects"     -> s.preventPotions = bool(val);
            // Vanish Gamemodes
            case "vanish-gamemodes.enabled"                         -> s.vanishGamemodesEnabled = bool(val);
            // Flight Control
            case "flight-control.vanish-enable-fly"                 -> s.enableFly = bool(val);
            case "flight-control.unvanish-disable-fly"              -> s.disableFlyOnUnvanish = bool(val);
            // Messages
            case "messages.fake-join"                               -> s.fakeJoinMessage = str(val);
            case "messages.fake-quit"                               -> s.fakeQuitMessage = str(val);
            case "messages.staff-notify.enabled"                    -> s.staffNotifyEnabled = bool(val);
            // Hooks
            case "hooks.simple-voice-chat.enabled"                  -> s.voiceChatEnabled = bool(val);
            case "hooks.simple-voice-chat.isolate-vanished-players" -> s.voiceChatIsolate = bool(val);
            case "hooks.essentials.simulate-join-leave"             -> s.simulateEssentialsMessages = bool(val);
            // Permissions
            case "permissions.layered-permissions-enabled"          -> s.layeredPermsEnabled = bool(val);
            case "permissions.default-vanish-level"                 -> s.defaultVanishLevel = intVal(val);
            case "permissions.default-see-level"                    -> s.defaultSeeLevel = intVal(val);
            case "permissions.max-level"                            -> s.maxLevel = intVal(val);
            // Update Checker
            case "update-checker.enabled"                           -> s.updateCheckerEnabled = bool(val);
            case "update-checker.notify-mode"                       -> s.updateCheckerMode = str(val);
            case "update-checker.modrinth-id"                       -> s.updateCheckerId = str(val);
            case "update-checker.show-beta"                         -> s.updateCheckerShowBeta = bool(val);
            case "update-checker.show-alpha"                        -> s.updateCheckerShowAlpha = bool(val);
            // Scoreboard
            case "scoreboard.enabled"                               -> s.scoreboardEnabled = bool(val);
            case "scoreboard.auto-show-on-vanish"                   -> s.scoreboardAutoShow = bool(val);
            // Language
            case "language"                                         -> s.language = str(val);
            // Proxy
            case "proxy.cross-server-list"                          -> s.crossServerList = bool(val);
            // Default Rules — key format: "default-rules.<rule_name>"
            default -> {
                if (key.startsWith("default-rules.")) {
                    String ruleName = key.substring("default-rules.".length());
                    s.defaultRules.put(ruleName, bool(val));
                }
            }
        }
    }

    // ── Type coercion helpers ─────────────────────────────────────────────────

    private static boolean bool(Object val) {
        if (val instanceof Boolean b) return b;
        if (val instanceof Number n) return n.intValue() != 0;
        if (val instanceof String str) return Boolean.parseBoolean(str);
        // Gson deserialises JSON booleans as JsonPrimitive — handle gracefully
        if (val instanceof JsonPrimitive jp) return jp.getAsBoolean();
        return false;
    }

    private static int intVal(Object val) {
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String str) return Integer.parseInt(str);
        if (val instanceof JsonPrimitive jp) return jp.getAsInt();
        return 0;
    }

    private static String str(Object val) {
        if (val == null) return "";
        if (val instanceof JsonPrimitive jp) return jp.getAsString();
        return val.toString();
    }
}
