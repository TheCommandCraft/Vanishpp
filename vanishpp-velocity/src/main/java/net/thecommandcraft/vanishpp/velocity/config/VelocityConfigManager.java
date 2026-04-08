package net.thecommandcraft.vanishpp.velocity.config;

import net.thecommandcraft.vanishpp.common.config.ProxyConfigSnapshot;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Loads and exposes the Velocity-side config.yml.
 * Populates a {@link ProxyConfigSnapshot} from the YAML file,
 * including the {@code servers:} per-server override section.
 */
public class VelocityConfigManager {

    private final Path dataDirectory;
    private final Logger logger;
    private Map<String, Object> rawConfig = new LinkedHashMap<>();
    private ProxyConfigSnapshot snapshot = new ProxyConfigSnapshot();

    public VelocityConfigManager(Path dataDirectory, Logger logger) {
        this.dataDirectory = dataDirectory;
        this.logger = logger;
    }

    public void load() {
        Path configFile = dataDirectory.resolve("config.yml");

        // Extract default config from JAR on first run
        if (!Files.exists(configFile)) {
            try {
                Files.createDirectories(dataDirectory);
                try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
                    if (in != null) {
                        Files.copy(in, configFile);
                        logger.info("Created default config.yml");
                    }
                }
            } catch (IOException e) {
                logger.error("Failed to create default config.yml", e);
            }
        }

        // Load YAML
        try (InputStream in = Files.newInputStream(configFile)) {
            Yaml yaml = new Yaml();
            Object loaded = yaml.load(in);
            if (loaded instanceof Map<?, ?> map) {
                rawConfig = castMap(map);
            }
        } catch (IOException e) {
            logger.error("Failed to read config.yml", e);
            rawConfig = new LinkedHashMap<>();
        }

        buildSnapshot();
    }

    public void reload() {
        load();
        logger.info("Config reloaded.");
    }

    public ProxyConfigSnapshot getSnapshot() {
        return snapshot;
    }

    /** Returns the raw YAML config map (for direct key lookups). */
    public Map<String, Object> getRawConfig() {
        return rawConfig;
    }

    // ── Snapshot construction ─────────────────────────────────────────────────

    private void buildSnapshot() {
        ProxyConfigSnapshot s = new ProxyConfigSnapshot();

        // Appearance
        s.vanishTabPrefix  = str("vanish-appearance.tab-prefix", "&7[VANISHED] ");
        s.vanishNametagPrefix = str("vanish-appearance.nametag-prefix", "");
        s.actionBarEnabled = bool("vanish-appearance.action-bar.enabled", true);
        s.adjustServerListCount = bool("vanish-appearance.adjust-server-list", true);
        s.staffGlowEnabled = bool("vanish-appearance.staff-glow", true);
        s.hideFromServerList = bool("vanish-effects.hide-from-server-list", true);

        // Vanish Effects
        s.hideRealQuit = bool("vanish-effects.hide-real-quit-messages", true);
        s.hideRealJoin = bool("vanish-effects.hide-real-join-messages", true);
        s.broadcastFakeQuit = bool("vanish-effects.broadcast-fake-quit", true);
        s.broadcastFakeJoin = bool("vanish-effects.broadcast-fake-join", true);
        s.disableBlockTriggering = bool("vanish-effects.disable-block-triggering", true);

        // Messages
        s.fakeJoinMessage = str("messages.fake-join", "");
        s.fakeQuitMessage = str("messages.fake-quit", "");
        s.staffNotifyEnabled = bool("messages.staff-notify.enabled", true);
        s.simulateEssentialsMessages = bool("hooks.essentials.simulate-join-leave", false);

        // Hide Announcements
        s.hideDeathMessages = bool("hide-announcements.death-messages", true);
        s.hideAdvancements  = bool("hide-announcements.advancements", true);
        s.hideFromPluginList = bool("hide-announcements.hide-from-plugin-list", true);

        // Invisibility Features
        s.enableNightVision    = bool("invisibility-features.night-vision", true);
        s.disableMobTarget     = bool("invisibility-features.disable-mob-targeting", true);
        s.disableHunger        = bool("invisibility-features.disable-hunger", true);
        s.silentChests         = bool("invisibility-features.silent-chests", true);
        s.ignoreProjectiles    = bool("invisibility-features.ignore-projectiles", true);
        s.preventRaid          = bool("invisibility-features.prevent-raid-trigger", true);
        s.preventSculk         = bool("invisibility-features.prevent-sculk-sensors", true);
        s.preventTrample       = bool("invisibility-features.prevent-trample", true);
        s.hideTabComplete      = bool("invisibility-features.hide-from-tab-complete", true);
        s.preventSleeping      = bool("invisibility-features.prevent-sleeping", true);
        s.preventEntityInteract= bool("invisibility-features.prevent-entity-interact", true);
        s.preventAccidentalChat= bool("invisibility-features.prevent-accidental-chat", true);
        s.godMode              = bool("invisibility-features.god-mode", true);
        s.preventPotions       = bool("invisibility-features.prevent-potion-effects", false);

        // Gamemodes / Flight
        s.vanishGamemodesEnabled = bool("vanish-gamemodes.enabled", true);
        s.enableFly            = bool("flight-control.vanish-enable-fly", true);
        s.disableFlyOnUnvanish = bool("flight-control.unvanish-disable-fly", true);

        // Hooks
        s.voiceChatEnabled = bool("hooks.simple-voice-chat.enabled", true);
        s.voiceChatIsolate = bool("hooks.simple-voice-chat.isolate-vanished-players", true);

        // Permissions / Layered
        s.layeredPermsEnabled = bool("permissions.layered-permissions-enabled", true);
        s.defaultVanishLevel  = intVal("permissions.default-vanish-level", 1);
        s.defaultSeeLevel     = intVal("permissions.default-see-level", 1);
        s.maxLevel            = intVal("permissions.max-level", 100);

        // Update checker
        s.updateCheckerEnabled = bool("update-checker.enabled", true);
        s.updateCheckerMode    = str("update-checker.notify-mode", "PERMISSION");
        s.updateCheckerId      = str("update-checker.modrinth-id", "vanishpp");

        // Scoreboard
        s.scoreboardEnabled  = bool("scoreboard.enabled", true);
        s.scoreboardAutoShow = bool("scoreboard.auto-show-on-vanish", true);

        // Default Rules
        Map<String, Object> rulesSection = section("default-rules");
        s.defaultRules.clear();
        for (Map.Entry<String, Object> e : rulesSection.entrySet()) {
            if (e.getValue() instanceof Boolean b) s.defaultRules.put(e.getKey(), b);
        }

        // Proxy-specific
        s.crossServerList = bool("proxy.cross-server-list", true);

        // Per-server overrides
        s.serverOverrides = buildServerOverrides();

        this.snapshot = s;
    }

    /**
     * Parses the {@code servers:} section into a flat-key override map.
     * The outer key is the server name (or "all" or "lobby, hub").
     * The inner map uses dotted config-key paths mapped to values.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> buildServerOverrides() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        Object serversObj = rawConfig.get("servers");
        if (!(serversObj instanceof Map<?, ?> serversMap)) return result;

        for (Map.Entry<?, ?> entry : serversMap.entrySet()) {
            String serverKey = String.valueOf(entry.getKey());
            if (!(entry.getValue() instanceof Map<?, ?> overrideMap)) continue;

            Map<String, Object> flat = new LinkedHashMap<>();
            flattenMap("", castMap(overrideMap), flat);
            result.put(serverKey, flat);
        }
        return result;
    }

    /** Recursively flattens a nested map into dot-separated keys. */
    private void flattenMap(String prefix, Map<String, Object> map, Map<String, Object> result) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof Map<?, ?> nested) {
                flattenMap(key, castMap(nested), result);
            } else {
                result.put(key, entry.getValue());
            }
        }
    }

    // ── YAML value helpers ────────────────────────────────────────────────────

    private boolean bool(String dotPath, boolean defaultValue) {
        Object val = resolve(dotPath);
        if (val instanceof Boolean b) return b;
        if (val instanceof String s) return Boolean.parseBoolean(s);
        return defaultValue;
    }

    private int intVal(String dotPath, int defaultValue) {
        Object val = resolve(dotPath);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) { try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {} }
        return defaultValue;
    }

    private String str(String dotPath, String defaultValue) {
        Object val = resolve(dotPath);
        return val != null ? val.toString() : defaultValue;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> section(String dotPath) {
        Object val = resolve(dotPath);
        if (val instanceof Map<?, ?> m) return castMap(m);
        return new LinkedHashMap<>();
    }

    /** Resolves a dot-separated path in the raw YAML map. */
    @SuppressWarnings("unchecked")
    private Object resolve(String dotPath) {
        String[] parts = dotPath.split("\\.");
        Map<String, Object> current = rawConfig;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = current.get(parts[i]);
            if (!(next instanceof Map<?, ?> m)) return null;
            current = castMap(m);
        }
        return current.get(parts[parts.length - 1]);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) result.put(String.valueOf(e.getKey()), e.getValue());
        return result;
    }

    // ── Storage config accessors (used by ProxySqlStorage) ────────────────────

    public String getStorageType() { return str("storage.type", "YAML").toUpperCase(); }
    public String getDbHost()      { return str("storage.mysql.host", "localhost"); }
    public int    getDbPort()      { return intVal("storage.mysql.port", 3306); }
    public String getDbName()      { return str("storage.mysql.database", "vanishpp"); }
    public String getDbUser()      { return str("storage.mysql.username", "root"); }
    public String getDbPass()      { return str("storage.mysql.password", ""); }
    public boolean getDbSsl()      { return bool("storage.mysql.use-ssl", false); }
    public int    getPoolSize()    { return intVal("storage.mysql.pool-size", 10); }
}
