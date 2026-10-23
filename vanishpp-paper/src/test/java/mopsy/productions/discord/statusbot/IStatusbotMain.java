package mopsy.productions.discord.statusbot;

public interface IStatusbotMain {
    default void onPlayerJoined(String status, String joinMessage) {
        Calls.joined.add(status + " | " + joinMessage);
    }

    default void onPlayerLeft(String status, String leftMessage) {
        Calls.left.add(status + " | " + leftMessage);
    }

    void regDefaultEmbedVarProviders();
}
