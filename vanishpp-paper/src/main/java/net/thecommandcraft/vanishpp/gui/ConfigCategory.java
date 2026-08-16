package net.thecommandcraft.vanishpp.gui;

import java.util.*;

/**
 * Categorizes all config keys for the ConfigGUI.
 * Each category maps to a set of editable config values with type and bounds.
 *
 * <p>Display names and per-setting descriptions are <b>not</b> stored here — an enum
 * constant is instantiated exactly once for the life of the JVM, so any text baked into
 * the constructor would never pick up a {@code /vconfig reload} and could even run
 * before {@code LanguageManager} has loaded. Instead this class only holds the stable
 * category/setting <i>keys</i>; {@link ConfigRenderer} resolves the live display text
 * from {@code messages.yml} (under {@code gui.config.category.*} /
 * {@code gui.config.setting-description.*}) fresh on every render.
 */
public enum ConfigCategory {

    GENERAL(new String[]{
            "vanish-delay-ticks",
            "double-shift-window",
            "default-hide-commands",
            "vanish-message.enabled",
            "action-bar-enabled"
    }),

    VISIBILITY(new String[]{
            "vanish-gamemodes.scoreboard-team",
            "vanish-gamemodes.collision-rule",
            "vanish-gamemodes.name-tag-visibility"
    }),

    SPECTATOR(new String[]{
            "spectator.follow-smooth-damping",
            "spectator.follow-speed-multiplier",
            "spectator.aggressive-follow-velocity",
            "spectator.los-detection-range"
    }),

    STORAGE(new String[]{
            "storage.type",
            "cross-server.enabled",
            "redis.host",
            "redis.port"
    }),

    PERMISSIONS(new String[]{
            "permissions.layered-permissions-enabled",
            "permissions.default-permission-tier"
    }),

    FEATURES(new String[]{
            "flight-control.enable-flight",
            "flight-control.unvanish-disable-fly",
            "prevent-sleeping",
            "prevent-eating",
            "tab-plugin-hook-enabled",
            "integration-hook-tab-enabled"
    });

    private final String[] keys;
    private final Map<String, ConfigValue> settings;

    ConfigCategory(String[] keys) {
        this.keys = keys;
        this.settings = new LinkedHashMap<>();
        initializeSettings();
    }

    /** Lowercase key used to look up this category's display name in messages.yml (gui.config.category.&lt;key&gt;). */
    public String getCategoryKey() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Initialize setting metadata for each key in this category.
     */
    private void initializeSettings() {
        for (String key : keys) {
            ConfigValue value = createConfigValue(key);
            if (value != null) {
                settings.put(key, value);
            }
        }
    }

    /**
     * Create a ConfigValue with metadata based on the key.
     * Add new keys here with their type, bounds, and description.
     */
    private ConfigValue createConfigValue(String key) {
        return switch (key) {
            // GENERAL
            case "vanish-delay-ticks" -> new ConfigValue("vanish-delay-ticks", ConfigType.NUMERIC, 0, 0, 200);

            case "double-shift-window" -> new ConfigValue("double-shift-window", ConfigType.NUMERIC, 150, 50, 1000);

            case "default-hide-commands" -> new ConfigValue("default-hide-commands", ConfigType.BOOLEAN, false, 0, 0);

            case "vanish-message.enabled" -> new ConfigValue("vanish-message.enabled", ConfigType.BOOLEAN, true, 0, 0);

            case "action-bar-enabled" -> new ConfigValue("action-bar-enabled", ConfigType.BOOLEAN, true, 0, 0);

            // VISIBILITY
            case "vanish-gamemodes.scoreboard-team" -> new ConfigValue("vanish-gamemodes.scoreboard-team", ConfigType.BOOLEAN, true, 0, 0);

            case "vanish-gamemodes.collision-rule" -> new ConfigValue("vanish-gamemodes.collision-rule", ConfigType.BOOLEAN, true, 0, 0);

            case "vanish-gamemodes.name-tag-visibility" -> new ConfigValue("vanish-gamemodes.name-tag-visibility", ConfigType.BOOLEAN, true, 0, 0);

            // SPECTATOR
            case "spectator.follow-smooth-damping" -> new ConfigValue("spectator.follow-smooth-damping", ConfigType.NUMERIC, 8, 1, 20);

            case "spectator.follow-speed-multiplier" -> new ConfigValue("spectator.follow-speed-multiplier", ConfigType.NUMERIC, 100, 50, 300);

            case "spectator.aggressive-follow-velocity" -> new ConfigValue("spectator.aggressive-follow-velocity", ConfigType.NUMERIC, 1, 0, 3);

            case "spectator.los-detection-range" -> new ConfigValue("spectator.los-detection-range", ConfigType.NUMERIC, 64, 16, 256);

            // STORAGE
            case "storage.type" -> new ConfigValue("storage.type", ConfigType.STRING, "yaml", 0, 0);

            case "cross-server.enabled" -> new ConfigValue("cross-server.enabled", ConfigType.BOOLEAN, false, 0, 0);

            case "redis.host" -> new ConfigValue("redis.host", ConfigType.STRING, "localhost", 0, 0);

            case "redis.port" -> new ConfigValue("redis.port", ConfigType.NUMERIC, 6379, 1, 65535);

            // PERMISSIONS
            case "permissions.layered-permissions-enabled" -> new ConfigValue("permissions.layered-permissions-enabled", ConfigType.BOOLEAN, false, 0, 0);

            case "permissions.default-permission-tier" -> new ConfigValue("permissions.default-permission-tier", ConfigType.NUMERIC, 0, 0, 5);

            // FEATURES
            case "flight-control.enable-flight" -> new ConfigValue("flight-control.enable-flight", ConfigType.BOOLEAN, true, 0, 0);

            case "flight-control.unvanish-disable-fly" -> new ConfigValue("flight-control.unvanish-disable-fly", ConfigType.BOOLEAN, true, 0, 0);

            case "prevent-sleeping" -> new ConfigValue("prevent-sleeping", ConfigType.BOOLEAN, true, 0, 0);

            case "prevent-eating" -> new ConfigValue("prevent-eating", ConfigType.BOOLEAN, false, 0, 0);

            case "tab-plugin-hook-enabled" -> new ConfigValue("tab-plugin-hook-enabled", ConfigType.BOOLEAN, true, 0, 0);

            case "integration-hook-tab-enabled" -> new ConfigValue("integration-hook-tab-enabled", ConfigType.BOOLEAN, true, 0, 0);

            default -> null;
        };
    }

    public String[] getKeys() {
        return keys;
    }

    public Map<String, ConfigValue> getSettings() {
        return new LinkedHashMap<>(settings);
    }

    public ConfigValue getSetting(String key) {
        return settings.get(key);
    }

    public int getSettingCount() {
        return settings.size();
    }

    /**
     * Represents a single configuration value with metadata.
     */
    public static class ConfigValue {
        public final String key;
        public final ConfigType type;
        public final Object defaultValue;
        public final int minBound;
        public final int maxBound;

        public ConfigValue(String key, ConfigType type, Object defaultValue,
                          int minBound, int maxBound) {
            this.key = key;
            this.type = type;
            this.defaultValue = defaultValue;
            this.minBound = minBound;
            this.maxBound = maxBound;
        }

        @Override
        public String toString() {
            return key + " (" + type + ")";
        }
    }

    /**
     * Config value types for rendering and validation.
     */
    public enum ConfigType {
        BOOLEAN,   // Toggle true/false with single button
        NUMERIC,   // Integer with ±1 and ±10 adjustment buttons
        STRING;    // Text (display-only in current implementation)

        public boolean isNumeric() {
            return this == NUMERIC;
        }

        public boolean isBoolean() {
            return this == BOOLEAN;
        }
    }
}
