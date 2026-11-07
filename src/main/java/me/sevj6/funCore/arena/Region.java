package me.sevj6.funCore.arena;

import org.bukkit.util.BoundingBox;

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
