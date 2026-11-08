package net.thecommandcraft.vanishpp.common.update;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How important an update is. A release declares it with a line such as {@code Priority: security} (Markdown
 * bold allowed) in its changelog. Releases without the line count as {@link #FEATURE}. Admins choose the lowest
 * priority they want to be notified about with {@code update-checker.notify-level}.
 */
public enum UpdatePriority {
    FEATURE, SECURITY, CRITICAL;

    private static final Pattern LINE = Pattern.compile(
            "^[\\s>*_-]*priority[\\s*_]*:[\\s*_]*(feature|security|critical)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    /** The priority declared in a changelog, or FEATURE if it declares none. */
    public static UpdatePriority fromChangelog(String changelog) {
        if (changelog == null) return FEATURE;
        Matcher m = LINE.matcher(changelog);
        return m.find() ? valueOf(m.group(1).toUpperCase(Locale.ROOT)) : FEATURE;
    }

    /** Parses a config value; unknown values fall back to FEATURE so every update is still announced. */
    public static UpdatePriority fromConfig(String value) {
        if (value == null) return FEATURE;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return FEATURE;
        }
    }

    public boolean atLeast(UpdatePriority other) { return compareTo(other) >= 0; }

    public static UpdatePriority max(UpdatePriority a, UpdatePriority b) { return a.compareTo(b) >= 0 ? a : b; }

    public String id() { return name().toLowerCase(Locale.ROOT); }
}
