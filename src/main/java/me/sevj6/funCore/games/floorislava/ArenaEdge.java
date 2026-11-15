package me.sevj6.funCore.games.floorislava;

import me.sevj6.funCore.arena.Region;
import org.jetbrains.annotations.Nullable;

/**
 * The sides of a Floor Is Lava arena, which nobody may camp: players too close to one are knocked back towards the
 * middle, and blocks can't be placed right against one. Only the four sides count (x and z), not the floor or the top.
 * Bukkit-free, so it's unit-tested.
 */
final class ArenaEdge {

    /** Which way to push a player, horizontally: a unit vector pointing away from the sides they're too close to. */
    record Push(double x, double z) {
    }

    private ArenaEdge() {
    }

    /**
     * How far a spot is from the nearest side of the region, in blocks (0 on the region's outer face; negative outside
     * it). Height doesn't matter.
     */
    static double distance(Region region, double x, double z) {
        return Math.min(Math.min(x - region.minX(), region.maxX() + 1 - x), Math.min(z - region.minZ(), region.maxZ() + 1 - z));
    }

    /**
     * Which way to knock a player at {@code (x, z)} back, if they're closer than {@code within} blocks to a side: away
     * from every side that close (diagonally in a corner). {@code null} if they aren't, or {@code within} is 0 or less.
     */
    static @Nullable Push push(Region region, double x, double z, double within) {
        if (within <= 0) return null;
        double dx = 0, dz = 0;
        if (x - region.minX() < within) dx += 1;
        if (region.maxX() + 1 - x < within) dx -= 1;
        if (z - region.minZ() < within) dz += 1;
        if (region.maxZ() + 1 - z < within) dz -= 1;
        double length = Math.sqrt(dx * dx + dz * dz);
        // Close to two opposite sides at once (an arena narrower than twice the distance): no way to push.
        if (length == 0) return null;
        return new Push(dx / length, dz / length);
    }

    /**
     * Whether a block is in the ring along the region's sides that can't be built on: within {@code gap} blocks of a
     * side (1 = the outermost blocks, the ones touching it). 0 or less = none.
     */
    static boolean inBuildGap(Region region, int blockX, int blockZ, int gap) {
        if (gap <= 0) return false;
        return blockX < region.minX() + gap || blockX > region.maxX() - gap
                || blockZ < region.minZ() + gap || blockZ > region.maxZ() - gap;
    }
}
