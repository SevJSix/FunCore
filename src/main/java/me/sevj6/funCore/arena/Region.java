package me.sevj6.funCore.arena;

import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** A cuboid of blocks, both corners included. */
public record Region(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public Region {
        if (minX > maxX || minY > maxY || minZ > maxZ) throw new IllegalArgumentException("min corner is above max corner");
    }

    /** The region between two corners, given in any order. */
    public static Region of(BlockPos a, BlockPos b) {
        return new Region(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z()),
                Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z()));
    }

    public int sizeX() {
        return maxX - minX + 1;
    }

    public int sizeY() {
        return maxY - minY + 1;
    }

    public int sizeZ() {
        return maxZ - minZ + 1;
    }

    public long volume() {
        return (long) sizeX() * sizeY() * sizeZ();
    }

    /** Whether a point (e.g. a player's feet) is inside any of the region's blocks. */
    public boolean contains(double x, double y, double z) {
        return x >= minX && x < maxX + 1 && y >= minY && y < maxY + 1 && z >= minZ && z < maxZ + 1;
    }

    /** The blocks both regions share, or {@code null} if they don't overlap. */
    public @Nullable Region intersection(Region other) {
        int x1 = Math.max(minX, other.minX), y1 = Math.max(minY, other.minY), z1 = Math.max(minZ, other.minZ);
        int x2 = Math.min(maxX, other.maxX), y2 = Math.min(maxY, other.maxY), z2 = Math.min(maxZ, other.maxZ);
        return x1 > x2 || y1 > y2 || z1 > z2 ? null : new Region(x1, y1, z1, x2, y2, z2);
    }

    /** Whether the other region lies entirely inside this one. */
    public boolean contains(Region other) {
        return other.minX >= minX && other.minY >= minY && other.minZ >= minZ
                && other.maxX <= maxX && other.maxY <= maxY && other.maxZ <= maxZ;
    }

    /** The same region moved by the given number of blocks. */
    public Region offset(int dx, int dy, int dz) {
        return new Region(minX + dx, minY + dy, minZ + dz, maxX + dx, maxY + dy, maxZ + dz);
    }

    /** Whether both regions have the same size (one could be an exact copy of the other). */
    public boolean sameSize(Region other) {
        return sizeX() == other.sizeX() && sizeY() == other.sizeY() && sizeZ() == other.sizeZ();
    }

    public BoundingBox toBoundingBox() {
        return new BoundingBox(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
    }

    List<Integer> toList() {
        return List.of(minX, minY, minZ, maxX, maxY, maxZ);
    }

    static Region fromList(List<Integer> values) {
        if (values.size() != 6) throw new IllegalArgumentException("a region needs 6 numbers");
        return new Region(values.get(0), values.get(1), values.get(2), values.get(3), values.get(4), values.get(5));
    }

    @Override
    public String toString() {
        return "(" + minX + ", " + minY + ", " + minZ + ") to (" + maxX + ", " + maxY + ", " + maxZ + ")";
    }
}
