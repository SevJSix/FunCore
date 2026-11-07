package me.sevj6.funCore.arena;

import org.bukkit.Location;

import java.util.LinkedHashMap;
import java.util.Map;

/** A block position, without a world (an {@link Arena} knows its world). */
public record BlockPos(int x, int y, int z) {

    public static BlockPos of(Location location) {
        return new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("x", x);
        map.put("y", y);
        map.put("z", z);
        return map;
    }

    static BlockPos fromMap(Map<?, ?> map) {
        return new BlockPos(ArenaPoint.require(map, "x").intValue(), ArenaPoint.require(map, "y").intValue(),
                ArenaPoint.require(map, "z").intValue());
    }

    @Override
    public String toString() {
        return x + ", " + y + ", " + z;
    }
}
