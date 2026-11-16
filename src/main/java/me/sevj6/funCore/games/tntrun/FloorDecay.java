package me.sevj6.funCore.games.tntrun;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The floor blocks players have stepped on, each going a fixed number of ticks later. Blocks are kept by position
 * ({@link #key}); one that's already going isn't counted again, so standing still doesn't keep it up.
 */
final class FloorDecay {

    /** A block's position and the tick it goes. Due ticks only grow (the delay is the same for all), so it's a queue. */
    private record Due(long key, long tick) {
    }

    private final ArrayDeque<Due> queue = new ArrayDeque<>();
    private final Set<Long> marked = new HashSet<>();

    /** Marks a block to go at {@code tick}; {@code false} if it was already marked. */
    boolean mark(long key, long tick) {
        if (!marked.add(key)) return false;
        queue.add(new Due(key, tick));
        return true;
    }

    /** The blocks due by {@code now}, in the order they were marked; they're no longer marked after this. */
    List<Long> due(long now) {
        List<Long> keys = new ArrayList<>();
        while (!queue.isEmpty() && queue.peekFirst().tick() <= now) {
            Due next = queue.removeFirst();
            marked.remove(next.key());
            keys.add(next.key());
        }
        return keys;
    }

    int size() {
        return queue.size();
    }

    void clear() {
        queue.clear();
        marked.clear();
    }

    // ---- positions ----

    /** A block position as one number (x and z within ±33 million, y within ±2048, like Minecraft's own). */
    static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    static int x(long key) {
        return (int) (key >> 38);
    }

    static int y(long key) {
        return (int) (key << 52 >> 52);
    }

    static int z(long key) {
        return (int) (key << 26 >> 38);
    }

    /**
     * The blocks a player is standing (or jumping) on: the ones under each corner of their hitbox, one block below
     * their feet. Mid-jump that's still the block they left, so jumping on the spot doesn't save it; only near the top
     * of a jump is it the air above. Up to 4 blocks (a player straddling an edge or corner stands on all of them).
     *
     * @param halfWidth half the hitbox's width (0.3 for a player)
     */
    static Set<Long> under(double x, double feetY, double z, double halfWidth) {
        // A hair inside the hitbox, so a player exactly lined up with a block edge isn't counted on the next block too;
        // and a hair up, so feet resting exactly on a block (y = n.0) count that block.
        double inset = 1.0E-3;
        int y = (int) Math.floor(feetY - 1 + inset);
        int minX = (int) Math.floor(x - halfWidth + inset), maxX = (int) Math.floor(x + halfWidth - inset);
        int minZ = (int) Math.floor(z - halfWidth + inset), maxZ = (int) Math.floor(z + halfWidth - inset);
        Set<Long> keys = new LinkedHashSet<>();
        for (int bx = minX; bx <= maxX; bx++) {
            for (int bz = minZ; bz <= maxZ; bz++) keys.add(key(bx, y, bz));
        }
        return keys;
    }
}
