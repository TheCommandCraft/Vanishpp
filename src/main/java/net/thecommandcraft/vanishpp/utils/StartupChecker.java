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
     * Runs all checks and returns a list of warning strings (plain-text, no colour
     * codes). An empty list means no issues were found.
     */
    public List<String> run() {
        List<String> warnings = new ArrayList<>();
        checkVoiceChat(warnings);
        checkEssentials(warnings);
        checkPapi(warnings);
        return warnings;
    }

    // -------------------------------------------------------------------------
    // Individual checks
    // -------------------------------------------------------------------------

    private void checkVoiceChat(List<String> warnings) {
        if (plugin.getConfigManager().voiceChatEnabled
                && Bukkit.getPluginManager().getPlugin("SimpleVoiceChat") == null) {
            warnings.add(
                    "hooks.simple-voice-chat.enabled is 'true' in config.yml but SimpleVoiceChat is NOT installed. "
                    + "Voice isolation will not work. Install the plugin or set the option to 'false'.");
        }
    }

    private void checkEssentials(List<String> warnings) {
        if (plugin.getConfigManager().simulateEssentialsMessages
                && Bukkit.getPluginManager().getPlugin("Essentials") == null) {
            warnings.add(
                    "hooks.essentials.simulate-join-leave is 'true' in config.yml but EssentialsX is NOT installed. "
                    + "This setting has no effect. Install EssentialsX or set the option to 'false'.");
        }
    }

    private void checkPapi(List<String> warnings) {
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
                    warnings.add(
                            "A config/message string contains a PlaceholderAPI placeholder '%" + token + "%' "
                            + "but PlaceholderAPI is NOT installed. The placeholder will appear as raw text. "
                            + "Install PlaceholderAPI or remove the placeholder from your messages.");
                    return; // one warning for PAPI is enough
                }
            }
        }
    }
}
