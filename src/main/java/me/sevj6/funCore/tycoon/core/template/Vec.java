package me.sevj6.funCore.tycoon.core.template;

/** A point relative to a plot's origin, in blocks (unlike {@link Offset}, not snapped to a block). */
public record Vec(double x, double y, double z) {

    public double distance(Vec other) {
        double dx = other.x - x, dy = other.y - y, dz = other.z - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** The point {@code t} of the way from here to {@code other} (0 = here, 1 = there). */
    public Vec lerp(Vec other, double t) {
        return new Vec(x + (other.x - x) * t, y + (other.y - y) * t, z + (other.z - z) * t);
    }
}
