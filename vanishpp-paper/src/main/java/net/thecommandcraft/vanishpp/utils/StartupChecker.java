package net.thecommandcraft.vanishpp.utils;

import net.thecommandcraft.vanishpp.Vanishpp;
import net.thecommandcraft.vanishpp.config.ConfigManager;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs on startup to detect common misconfigurations (missing plugins for
 * enabled hooks, PlaceholderAPI placeholders without PAPI installed, etc.) and
 * collects human-readable warnings that are shown to staff on join.
 */
public class StartupChecker {

    /** A single startup warning with optional action metadata for interactive display. */
    public static class Warning {
        public final String message;
        /** Config path to fix, or null if not applicable. */
        public final String configPath;
        /** Value to set at configPath, or null if not applicable. */
        public final String fixValue;
        /** Plugin download URL to open, or null if not applicable. */
        public final String installUrl;
        /**
         * Newline-separated list of features that are disabled without this plugin.
         * When non-null a "Disabled Features" hover button is shown next to [Install Plugin].
         */
        public final String featureList;

        public Warning(String message, String configPath, String fixValue, String installUrl, String featureList) {
            this.message = message;
            this.configPath = configPath;
            this.fixValue = fixValue;
            this.installUrl = installUrl;
            this.featureList = featureList;
        }

        public static Warning configFix(String msg, String path, String value) {
            return new Warning(msg, path, value, null, null);
        }

        public static Warning pluginInstall(String msg, String url) {
            return new Warning(msg, null, null, url, null);
        }

        public static Warning pluginInstallWithFeatures(String msg, String url, String featureList) {
            return new Warning(msg, null, null, url, featureList);
        }
    }

    /**
     * VPP's own internal replacement tokens — these are NOT PlaceholderAPI
     * placeholders and must not trigger the missing-PAPI warning.
     */
    private static final Set<String> VPP_INTERNAL_TOKENS = Set.of(
            "player", "displayname", "staff", "prefix", "message",
            "rule", "perm", "status", "version", "action", "count",
            "usage", "color", "seconds", "path", "value"
    );

    /** Matches %something% patterns that PlaceholderAPI would process. */
    private static final Pattern PAPI_PATTERN = Pattern.compile("%([a-z][a-z0-9_]*)%");

    private final Vanishpp plugin;

    public StartupChecker(Vanishpp plugin) {
        this.plugin = plugin;
    }

    /**
     * Runs all checks and returns a list of warnings with optional action metadata.
     * An empty list means no issues were found.
     */
    public List<Warning> run() {
        List<Warning> warnings = new ArrayList<>();
        checkProtocolLib(warnings);
        checkVoiceChat(warnings);
        checkEssentials(warnings);
        checkPapi(warnings);
        return warnings;
    }

    // -------------------------------------------------------------------------
    // Individual checks
    // -------------------------------------------------------------------------

    private static final String PROTOCOLLIB_FEATURES =
            "• Tab-complete scrubbing — vanished names hidden\n"
            + "  from non-staff's /tab suggestions\n"
            + "• Entity packet filtering — movement, animations,\n"
            + "  equipment, effects blocked for non-staff\n"
            + "• Ghost-proof spawning — spawn packets suppressed\n"
            + "  so vanished players never render client-side\n"
            + "• Scoreboard team scrubbing — nametag prefix\n"
            + "  hidden from non-staff\n"
            + "• Server list count — vanished players excluded\n"
            + "  from the displayed online count\n"
            + "• Silent chests — lid animations & sounds\n"
            + "  suppressed when opening silently\n"
            + "• Staff glow — glowing outline shown to staff\n"
            + "  so they can spot vanished players easily";

    private void checkProtocolLib(List<Warning> warnings) {
        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") != null) return;
        warnings.add(Warning.pluginInstallWithFeatures(
                "ProtocolLib is NOT installed. Several advanced vanish features are disabled.",
                "https://github.com/dmulloy2/ProtocolLib/releases/latest",
                PROTOCOLLIB_FEATURES));
    }

    private void checkVoiceChat(List<Warning> warnings) {
        // Plugin registers under "voicechat" (bukkit jar name), fallback to "SimpleVoiceChat"
        if (plugin.getConfigManager().voiceChatEnabled
                && Bukkit.getPluginManager().getPlugin("voicechat") == null
                && Bukkit.getPluginManager().getPlugin("SimpleVoiceChat") == null) {
            warnings.add(Warning.configFix(
                    "hooks.simple-voice-chat.enabled is 'true' but SimpleVoiceChat is NOT installed. "
                    + "Voice isolation will not work. Set the option to 'false' or install the plugin.",
                    "hooks.simple-voice-chat.enabled", "false"));
        }
    }

    private void checkEssentials(List<Warning> warnings) {
        if (plugin.getConfigManager().simulateEssentialsMessages
                && Bukkit.getPluginManager().getPlugin("Essentials") == null) {
            warnings.add(Warning.configFix(
                    "hooks.essentials.simulate-join-leave is 'true' but EssentialsX is NOT installed. "
                    + "This setting has no effect. Set to 'false' or install EssentialsX.",
                    "hooks.essentials.simulate-join-leave", "false"));
        }
    }

    private void checkPapi(List<Warning> warnings) {
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return; // PAPI present — placeholders will work fine
        }

        ConfigManager cm = plugin.getConfigManager();
        String[] stringsToCheck = {
                cm.actionBarText,
                cm.vanishTabPrefix,
                cm.vanishNametagPrefix,
                cm.vanishedPlayerFormat,
                cm.fakeJoinMessage,
                cm.fakeQuitMessage,
                cm.staffVanishMessage,
                cm.staffUnvanishMessage
        };

        for (String s : stringsToCheck) {
            if (s == null || s.isEmpty()) continue;
            Matcher m = PAPI_PATTERN.matcher(s);
            while (m.find()) {
                String token = m.group(1);
                if (!VPP_INTERNAL_TOKENS.contains(token)) {
                    warnings.add(Warning.pluginInstall(
                            "A config string contains a PlaceholderAPI placeholder '%" + token + "%' "
                            + "but PlaceholderAPI is NOT installed. It will appear as raw text.",
                            "https://modrinth.com/plugin/placeholderapi"));
                    return; // one warning for PAPI is enough
                }
            }
        }
    }
}
