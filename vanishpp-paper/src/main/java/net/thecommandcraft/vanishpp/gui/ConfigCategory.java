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
 *
 * <p><b>Every key below was individually cross-checked against {@code config.yml} and
 * {@code ConfigManager} at the time of writing</b> — this class previously listed 23 keys,
 * of which only 3 were ever actually read anywhere; the rest silently wrote to unused
 * top-level YAML keys with zero effect on plugin behavior (clicking played the success
 * sound but did nothing real). Phantom keys were removed rather than backfilled with new
 * functionality; wrong-but-fixable paths were corrected to their real equivalent.
 * Settings whose real path falls under {@link net.thecommandcraft.vanishpp.commands.VanishConfigCommand}'s
 * {@code SENSITIVE_PREFIXES} (currently {@code storage.type}, {@code storage.mysql.*},
 * {@code storage.redis.*}, {@code permissions.layered-permissions-enabled}) are
 * deliberately excluded here too — the text command gates those behind an explicit
 * {@code --confirm}, and this GUI has no equivalent confirmation flow, so exposing them as
 * a single accidental click would bypass a safety gate that exists on purpose. Edit those
 * via {@code /vconfig <path> <value> --confirm} instead.
 */
public enum ConfigCategory {

    GENERAL(new String[]{
            "vanish-appearance.action-bar.enabled"
    }),

    VISIBILITY(new String[]{
            "vanish-gamemodes.enabled",
            "vanish-gamemodes.default-spectator"
    }),

    SPECTATOR(new String[]{
            // No spectator-mode setting exists in config.yml beyond the two now listed
            // under VISIBILITY (vanish-gamemodes.*, which config.yml itself files under a
            // "SPECTATOR MODE" heading) — the four settings previously here
            // (follow-smooth-damping, follow-speed-multiplier, aggressive-follow-velocity,
            // los-detection-range) were phantom, read nowhere in the codebase. Left
            // deliberately empty rather than removing the category/tab itself, per the
            // "leave category boundaries as-is" scope for this pass.
    }),

    STORAGE(new String[]{
            // storage.type and every storage.redis.*/storage.mysql.* key are all
            // SENSITIVE_PREFIXES-gated (see class javadoc) — none are safe to expose here.
    }),

    PERMISSIONS(new String[]{
            "permissions.default-vanish-level",
            "permissions.default-see-level",
            "permissions.max-level"
    }),

    FEATURES(new String[]{
            "flight-control.vanish-enable-fly",
            "flight-control.unvanish-disable-fly",
            "invisibility-features.prevent-sleeping"
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
     * Add new keys here with their type, bounds, and description — and make sure the key
     * is a real, live config.yml path that ConfigManager actually reads, and isn't covered
     * by VanishConfigCommand's SENSITIVE_PREFIXES (see class javadoc).
     */
    private ConfigValue createConfigValue(String key) {
        return switch (key) {
            // GENERAL
            case "vanish-appearance.action-bar.enabled" -> new ConfigValue("vanish-appearance.action-bar.enabled",
                    ConfigType.BOOLEAN, true, 0, 0);

            // VISIBILITY (config.yml files these under "SPECTATOR MODE", but they're the
            // closest real settings to what this category originally intended)
            case "vanish-gamemodes.enabled" -> new ConfigValue("vanish-gamemodes.enabled",
                    ConfigType.BOOLEAN, true, 0, 0);

            case "vanish-gamemodes.default-spectator" -> new ConfigValue("vanish-gamemodes.default-spectator",
                    ConfigType.BOOLEAN, true, 0, 0);

            // PERMISSIONS
            case "permissions.default-vanish-level" -> new ConfigValue("permissions.default-vanish-level",
                    ConfigType.NUMERIC, 1, 0, 100);

            case "permissions.default-see-level" -> new ConfigValue("permissions.default-see-level",
                    ConfigType.NUMERIC, 1, 0, 100);

            case "permissions.max-level" -> new ConfigValue("permissions.max-level",
                    ConfigType.NUMERIC, 100, 1, 1000);

            // FEATURES
            case "flight-control.vanish-enable-fly" -> new ConfigValue("flight-control.vanish-enable-fly",
                    ConfigType.BOOLEAN, true, 0, 0);

            case "flight-control.unvanish-disable-fly" -> new ConfigValue("flight-control.unvanish-disable-fly",
                    ConfigType.BOOLEAN, true, 0, 0);

            case "invisibility-features.prevent-sleeping" -> new ConfigValue("invisibility-features.prevent-sleeping",
                    ConfigType.BOOLEAN, true, 0, 0);

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
