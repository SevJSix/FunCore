package me.sevj6.funCore.tycoon.core.template;

import java.util.ArrayList;
import java.util.List;

/**
 * Stand-in looks for components that don't list their own parts, so the whole loop is playable before any real
 * builds exist. They're placed from a component's own positions only (never from what's around it on the map):
 * <ul>
 *     <li>conveyor: a thin magenta glazed terracotta surface on each bed block, its arrows pointing the way items go</li>
 *     <li>dropper: a dropper facing down just above the spout, under a short coloured post</li>
 *     <li>upgrader: an arch over its waypoint: two posts two blocks either side of the belt, and a glass bar across</li>
 *     <li>wall: nothing (a wall is only its parts)</li>
 * </ul>
 */
public final class Placeholders {

    /** How thick a placeholder belt surface is. Items ride on top of it. */
    public static final double BELT_SURFACE = 0.0625;

    /** Post colours for placeholder droppers, by the dropper's position among the template's components. */
    private static final List<String> COLOURS = List.of("brown", "orange", "light_gray", "yellow", "red", "blue", "cyan",
            "lime", "black");

    private Placeholders() {
    }

    /**
     * How {@code component} looks: its own parts, or placeholder parts if it lists none. {@code index} is its
     * position in the template (for placeholder colours).
     */
    public static List<Part> of(Template template, Component component, int index) {
        if (!component.common().parts().isEmpty()) return component.common().parts();
        return switch (component) {
            case Component.Conveyor conveyor -> belt(template, conveyor.belt());
            case Component.Dropper dropper -> dropper(dropper, index);
            case Component.Upgrader upgrader -> upgrader(template, upgrader);
            case Component.Wall ignored -> List.of();
        };
    }

    /** A belt's surface: each block of its path, and the run to where it ends (that block itself excluded). */
    public static List<Part> belt(Template template, Belt belt) {
        List<Part> parts = new ArrayList<>();
        List<Offset> path = belt.path();
        for (int i = 0; i + 1 < path.size(); i++) line(parts, path.get(i), path.get(i + 1), false);
        Offset last = path.getLast();
        Offset end = switch (belt.end()) {
            case Belt.ToCollector to -> template.collectors().get(to.collector());
            case Belt.Merge merge -> {
                Belt next = template.belt(merge.belt());
                yield next == null ? null : next.path().get(merge.waypoint());
            }
        };
        if (end != null && !end.equals(last)) line(parts, last, end, false);
        else surface(parts, last, "east");
        return parts;
    }

    /** Surface blocks from {@code from} up to (not including) {@code to}, unless {@code includeEnd}. */
    private static void line(List<Part> parts, Offset from, Offset to, boolean includeEnd) {
        int dx = to.x() - from.x(), dy = to.y() - from.y(), dz = to.z() - from.z();
        int steps = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
        String travel = Math.abs(dx) >= Math.abs(dz) ? (dx >= 0 ? "east" : "west") : (dz >= 0 ? "south" : "north");
        int last = includeEnd ? steps : steps - 1;
        for (int i = 0; i <= last; i++) {
            double t = steps == 0 ? 0 : (double) i / steps;
            surface(parts, new Offset(from.x() + (int) Math.round(dx * t), from.y() + (int) Math.round(dy * t),
                    from.z() + (int) Math.round(dz * t)), travel);
        }
    }

    /** A thin surface on top of a bed block. Glazed terracotta's arrow points away from the way the block faces. */
    private static void surface(List<Part> parts, Offset bed, String travel) {
        parts.add(Part.block("minecraft:magenta_glazed_terracotta[facing=" + opposite(travel) + "]",
                bed.x(), bed.y() + 1, bed.z(), 1, BELT_SURFACE, 1));
    }

    private static String opposite(String direction) {
        return switch (direction) {
            case "east" -> "west";
            case "west" -> "east";
            case "north" -> "south";
            default -> "north";
        };
    }

    private static List<Part> dropper(Component.Dropper dropper, int index) {
        Offset spout = dropper.spout();
        String colour = "minecraft:" + COLOURS.get(Math.floorMod(index, COLOURS.size())) + "_concrete";
        return List.of(
                Part.block("minecraft:dropper[facing=down]", spout.x(), spout.y() + 1, spout.z(), 1, 1, 1),
                Part.block(colour, spout.x() + 0.25, spout.y() + 2, spout.z() + 0.25, 0.5, 2, 0.5));
    }

    private static List<Part> upgrader(Template template, Component.Upgrader upgrader) {
        Belt belt = template.belt(upgrader.belt());
        if (belt == null || upgrader.waypoint() < 0 || upgrader.waypoint() >= belt.path().size()) return List.of();
        Offset at = belt.path().get(upgrader.waypoint());
        String post = "minecraft:light_gray_concrete", bar = "minecraft:light_blue_stained_glass";
        return List.of(
                Part.block(post, at.x() + 0.25, at.y() + 1, at.z() - 2 + 0.25, 0.5, 2.5, 0.5),
                Part.block(post, at.x() + 0.25, at.y() + 1, at.z() + 2 + 0.25, 0.5, 2.5, 0.5),
                Part.block(bar, at.x(), at.y() + 3.5, at.z() - 2, 1, 0.5, 5));
    }
}
