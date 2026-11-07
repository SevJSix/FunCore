package me.sevj6.funCore.arena;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.LinkedHashMap;
import java.util.Map;

/** A position and facing inside an arena (a spawn, the lobby, or a named point), without a world. */
public record ArenaPoint(double x, double y, double z, float yaw, float pitch) {

    /**
     * Where the location is, centred on its block horizontally so spawns line up neatly. The height and facing are
     * kept (so a point set while standing on a slab stays on the slab).
     */
    public static ArenaPoint centered(Location location) {
        return new ArenaPoint(location.getBlockX() + 0.5, round(location.getY()), location.getBlockZ() + 0.5,
                (float) round(location.getYaw()), (float) round(location.getPitch()));
    }

    public Location toLocation(World world) {
        return new Location(world, x, y, z, yaw, pitch);
    }

    Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("x", x);
        map.put("y", y);
        map.put("z", z);
        map.put("yaw", (double) yaw);
        map.put("pitch", (double) pitch);
        return map;
    }

    static ArenaPoint fromMap(Map<?, ?> map) {
        Object yaw = map.get("yaw"), pitch = map.get("pitch");
        return new ArenaPoint(require(map, "x").doubleValue(), require(map, "y").doubleValue(), require(map, "z").doubleValue(),
                yaw instanceof Number n ? n.floatValue() : 0f, pitch instanceof Number n ? n.floatValue() : 0f);
    }

    static Number require(Map<?, ?> map, String key) {
        if (map.get(key) instanceof Number number) return number;
        throw new IllegalArgumentException("missing number '" + key + "'");
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }

    @Override
    public String toString() {
        return String.format("%.1f, %.1f, %.1f", x, y, z);
    }
}
