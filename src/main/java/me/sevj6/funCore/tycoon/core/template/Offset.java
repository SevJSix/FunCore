package me.sevj6.funCore.tycoon.core.template;

/**
 * A block position relative to a plot's origin. Nothing in the tycoon's config or saved data uses world coordinates:
 * every position is one of these, so the same template works at every plot.
 */
public record Offset(int x, int y, int z) {

    public static final Offset ZERO = new Offset(0, 0, 0);

    public Offset add(int dx, int dy, int dz) {
        return new Offset(x + dx, y + dy, z + dz);
    }

    /** The point at the middle of this block's top face: where an item rides on a belt block, for example. */
    public Vec top() {
        return new Vec(x + 0.5, y + 1.0, z + 0.5);
    }

    /** The point at the middle of this block. */
    public Vec center() {
        return new Vec(x + 0.5, y + 0.5, z + 0.5);
    }

    @Override
    public String toString() {
        return "[" + x + ", " + y + ", " + z + "]";
    }
}
