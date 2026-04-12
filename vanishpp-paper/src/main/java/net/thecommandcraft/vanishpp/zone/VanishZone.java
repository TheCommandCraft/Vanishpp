package net.thecommandcraft.vanishpp.zone;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * Represents a no-vanish zone centered on a location with a configurable radius.
 * Any vanished player entering the radius is automatically unvanished.
 */
public final class VanishZone {

    private final String name;
    private final String worldName;
    private final double x;
    private final double y;
    private final double z;
    private final double radius;
    private final boolean forceUnvanish;

    public VanishZone(String name, Location center, double radius, boolean forceUnvanish) {
        this.name = name;
        this.worldName = center.getWorld() != null ? center.getWorld().getName() : "world";
        this.x = center.getX();
        this.y = center.getY();
        this.z = center.getZ();
        this.radius = radius;
        this.forceUnvanish = forceUnvanish;
    }

    /** Deserialization constructor. */
    public VanishZone(String name, String worldName, double x, double y, double z,
                      double radius, boolean forceUnvanish) {
        this.name = name;
        this.worldName = worldName;
        this.x = x;
        this.y = y;
        this.z = z;
        this.radius = radius;
        this.forceUnvanish = forceUnvanish;
    }

    public boolean contains(Location loc) {
        if (loc.getWorld() == null) return false;
        if (!loc.getWorld().getName().equals(worldName)) return false;
        double dx = loc.getX() - x;
        double dz = loc.getZ() - z;
        return (dx * dx + dz * dz) <= (radius * radius);
    }

    public String getName()        { return name; }
    public String getWorldName()   { return worldName; }
    public double getX()           { return x; }
    public double getY()           { return y; }
    public double getZ()           { return z; }
    public double getRadius()      { return radius; }
    public boolean isForceUnvanish() { return forceUnvanish; }
}
