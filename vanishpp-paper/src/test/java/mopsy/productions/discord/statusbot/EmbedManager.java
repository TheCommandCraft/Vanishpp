package mopsy.productions.discord.statusbot;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public class EmbedManager {
    static final Map<String, Function<IStatusbotMain, String>> varSuppliers = new HashMap<>();

    public static void regVarSupplier(String varName, Function<IStatusbotMain, String> varSupplier) {
        varSuppliers.put(varName, varSupplier);
    }

    public static String resolve(String varName, IStatusbotMain main) {
        return varSuppliers.get(varName).apply(main);
    }
}
