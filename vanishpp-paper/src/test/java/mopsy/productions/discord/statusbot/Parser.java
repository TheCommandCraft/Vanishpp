package mopsy.productions.discord.statusbot;

import java.util.List;
import java.util.function.Supplier;

public class Parser {
    static String createStatusMessage(Supplier<List<String>> playersSupplier, int playerAmount) {
        if (Calls.parserBroken) throw new IllegalStateException("broken");
        return playerAmount + " online: " + String.join(",", playersSupplier.get());
    }

    static String createJoinMessage(Supplier<List<String>> playersSupplier, String player, int playerAmount) {
        return player + " joined";
    }

    static String createLeaveMessage(Supplier<List<String>> playersSupplier, String player, int playerAmount) {
        return player + " left";
    }
}
