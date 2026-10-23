package mopsy.productions.discord.statusbot;

public class BotManager {
    public static Object jda;

    public static void regBot(String botToken, String status, IStatusbotMain main) {
        Calls.presence.add(status);
    }
}
