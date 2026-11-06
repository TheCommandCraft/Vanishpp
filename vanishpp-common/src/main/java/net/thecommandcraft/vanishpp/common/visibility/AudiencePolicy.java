package net.thecommandcraft.vanishpp.common.visibility;

import java.util.Collection;
import java.util.UUID;
import java.util.function.Predicate;

public final class AudiencePolicy {
    private AudiencePolicy() {}

    public static boolean canSee(UUID viewer, UUID subject, boolean vanished, boolean permission,
                                 boolean layered, int seeLevel, int vanishLevel) {
        if (!vanished) return true;
        if (viewer == null) return false;
        if (viewer.equals(subject)) return true;
        return permission && (!layered || seeLevel >= vanishLevel);
    }

    /** Anonymous listings contain only the supplied online population. */
    public static <T> int publicCount(Collection<T> online, Predicate<T> vanished) {
        return (int) online.stream().filter(vanished.negate()).count();
    }
}
