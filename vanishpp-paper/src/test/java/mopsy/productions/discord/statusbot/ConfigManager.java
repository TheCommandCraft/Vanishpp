package mopsy.productions.discord.statusbot;

public class ConfigManager {
    public static String getStr(String key) {
        return key.equals("embed_player_separator_text") ? ", " : "token";
    }
}
