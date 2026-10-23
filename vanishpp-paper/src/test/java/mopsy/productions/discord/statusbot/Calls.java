package mopsy.productions.discord.statusbot;

import java.util.ArrayList;
import java.util.List;

public final class Calls {
    public static final List<String> joined = new ArrayList<>();
    public static final List<String> left = new ArrayList<>();
    public static final List<String> rawEvents = new ArrayList<>();
    public static final List<String> presence = new ArrayList<>();
    public static boolean parserBroken;

    public static void reset() {
        joined.clear();
        left.clear();
        rawEvents.clear();
        presence.clear();
        parserBroken = false;
        BotManager.jda = null;
        EmbedManager.varSuppliers.clear();
    }

    private Calls() {}
}
