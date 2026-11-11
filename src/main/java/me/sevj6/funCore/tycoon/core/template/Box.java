package me.sevj6.funCore.tycoon.core.template;

import java.util.List;

/** Every block between two corners, inclusive (the corners can be given in any order). */
public record Box(Offset min, Offset max) {

    public Box {
        Offset low = new Offset(Math.min(min.x(), max.x()), Math.min(min.y(), max.y()), Math.min(min.z(), max.z()));
        Offset high = new Offset(Math.max(min.x(), max.x()), Math.max(min.y(), max.y()), Math.max(min.z(), max.z()));
        min = low;
        max = high;
    }

    public boolean contains(Offset at) {
        return at.x() >= min.x() && at.x() <= max.x() && at.y() >= min.y() && at.y() <= max.y()
                && at.z() >= min.z() && at.z() <= max.z();
    }

    public List<Offset> blocks() {
        return Offset.box(min, max);
    }

    /** The blocks of a straight run from {@code from} toward {@code to}, {@code to} itself only if {@code includeEnd}. */
    public static List<Offset> line(Offset from, Offset to, boolean includeEnd) {
        int dx = to.x() - from.x(), dy = to.y() - from.y(), dz = to.z() - from.z();
        int steps = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
        int last = includeEnd ? steps : steps - 1;
        List<Offset> blocks = new java.util.ArrayList<>();
        for (int i = 0; i <= last; i++) {
            double t = steps == 0 ? 0 : (double) i / steps;
            blocks.add(new Offset(from.x() + (int) Math.round(dx * t), from.y() + (int) Math.round(dy * t),
                    from.z() + (int) Math.round(dz * t)));
        }
        return blocks;
    }
}
