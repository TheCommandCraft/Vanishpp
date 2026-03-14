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

        public Warning(String message, String configPath, String fixValue, String installUrl) {
            this.message = message;
            this.configPath = configPath;
            this.fixValue = fixValue;
            this.installUrl = installUrl;
        }

        public static Warning configFix(String msg, String path, String value) {
            return new Warning(msg, path, value, null);
        }

        public static Warning pluginInstall(String msg, String url) {
            return new Warning(msg, null, null, url);
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
        checkVoiceChat(warnings);
        checkEssentials(warnings);
        checkPapi(warnings);
        return warnings;
    }

    // -------------------------------------------------------------------------
    // Individual checks
    // -------------------------------------------------------------------------

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
