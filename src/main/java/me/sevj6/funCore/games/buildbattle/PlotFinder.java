package me.sevj6.funCore.games.buildbattle;

import me.sevj6.funCore.arena.Region;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Works out a Build Battle plot from a spawn standing in it, so maps need no plot coordinates: the plot is the
 * rectangle of floor around the spawn made of the same block as the one under it (a plot's floor is one block type, its
 * border another), from the floor up {@code height} blocks. Pure logic, so it can be tested without a world.
 */
final class PlotFinder {

    /** How far a plot may reach from its spawn in each direction. */
    static final int MAX_REACH = 64;

    /** Whether the floor at (x, z), on the spawn's floor layer, is the same block as under the spawn. */
    @FunctionalInterface
    interface Floor {
        boolean same(int x, int z);
    }

    private PlotFinder() {
    }

    /**
     * The plot around a spawn: as far as the floor goes along the spawn's row and column (plots are rectangles), and
     * from the floor up {@code height} blocks, all inside {@code bounds}.
     *
     * @param x      the spawn's block x
     * @param floorY the y of the block under the spawn
     * @param z      the spawn's block z
     */
    static Region find(int x, int floorY, int z, int height, Region bounds, Floor floor) {
        int minX = x, maxX = x, minZ = z, maxZ = z;
        while (minX - 1 >= bounds.minX() && x - (minX - 1) <= MAX_REACH && floor.same(minX - 1, z)) minX--;
        while (maxX + 1 <= bounds.maxX() && (maxX + 1) - x <= MAX_REACH && floor.same(maxX + 1, z)) maxX++;
        while (minZ - 1 >= bounds.minZ() && z - (minZ - 1) <= MAX_REACH && floor.same(x, minZ - 1)) minZ--;
        while (maxZ + 1 <= bounds.maxZ() && (maxZ + 1) - z <= MAX_REACH && floor.same(x, maxZ + 1)) maxZ++;
        return new Region(minX, floorY, minZ, maxX, Math.min(floorY + height, bounds.maxY()), maxZ);
    }

    /** Whether two plots share any column (their floors overlap, whatever their heights). */
    static boolean overlap(Region a, Region b) {
        return a.minX() <= b.maxX() && b.minX() <= a.maxX() && a.minZ() <= b.maxZ() && b.minZ() <= a.maxZ();
    }

    /** A side of a plot: {@code dx}/{@code dz} point outwards. */
    enum Side {
        SOUTH(0, 1), NORTH(0, -1), EAST(1, 0), WEST(-1, 0);

        final int dx, dz;

        Side(int dx, int dz) {
            this.dx = dx;
            this.dz = dz;
        }
    }

    /**
     * The side of a plot that faces open space inside the arena (e.g. the walkway between two rows of plots), to view
     * it from: going out from the middle of that side, the next {@code reach} blocks are inside the arena and in no
     * other plot. {@code null} if no side does.
     */
    static @Nullable Side openSide(Region plot, List<Region> others, Region arena, int reach) {
        double cx = (plot.minX() + plot.maxX()) / 2.0, cz = (plot.minZ() + plot.maxZ()) / 2.0;
        for (Side side : Side.values()) {
            int edgeX = side.dx > 0 ? plot.maxX() : side.dx < 0 ? plot.minX() : (int) Math.floor(cx);
            int edgeZ = side.dz > 0 ? plot.maxZ() : side.dz < 0 ? plot.minZ() : (int) Math.floor(cz);
            boolean open = true;
            for (int k = 1; k <= reach && open; k++) {
                int px = edgeX + side.dx * k, pz = edgeZ + side.dz * k;
                if (px < arena.minX() || px > arena.maxX() || pz < arena.minZ() || pz > arena.maxZ()) open = false;
                for (Region other : others) {
                    if (other != plot && px >= other.minX() && px <= other.maxX() && pz >= other.minZ() && pz <= other.maxZ()) {
                        open = false;
                        break;
                    }
                }
            }
            if (open) return side;
        }
        return null;
    }
}
