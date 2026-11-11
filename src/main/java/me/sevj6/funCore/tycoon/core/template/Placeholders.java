package me.sevj6.funCore.tycoon.core.template;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

/**
 * Stand-in looks for components that don't list their own parts, so the whole loop is playable before any real
 * builds exist. They're placed from a component's own positions only (never from what's around it on the map):
 * <ul>
 *     <li>conveyor: a thin magenta glazed terracotta surface on each bed block, its arrows pointing the way items go,
 *     running in a loop; laid out for all of a plot's belts together (see {@link #surfaces})</li>
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
            case Component.Conveyor ignored -> List.of(); // its surface is laid out with the plot's other belts (surfaces)
            case Component.Dropper dropper -> dropper(dropper, index);
            case Component.Upgrader upgrader -> upgrader(template, upgrader);
            case Component.Wall ignored -> List.of();
        };
    }

    /**
     * How much higher a belt's fixed end pieces sit than its moving surface, so moving tiles slide under them instead
     * of sharing a plane with them (which would flicker).
     */
    public static final double CAP_RAISE = 0.01;

    /** One tile of a belt's surface: the bed block it covers and the way items go there. */
    private record Tile(Offset bed, String travel) {

        Offset ahead() {
            return step(1);
        }

        Offset behind() {
            return step(-1);
        }

        private Offset step(int by) {
            return switch (travel) {
                case "east" -> bed.add(by, 0, 0);
                case "west" -> bed.add(-by, 0, 0);
                case "south" -> bed.add(0, 0, by);
                default -> bed.add(0, 0, -by);
            };
        }
    }

    /** One belt's tiles: each block of its path and the run to where it ends (a collector's block included). */
    private static List<Tile> tiles(Template template, Belt belt) {
        List<Tile> tiles = new ArrayList<>();
        List<Offset> path = belt.path();
        for (int i = 0; i + 1 < path.size(); i++) line(tiles, path.get(i), path.get(i + 1), false);
        Offset last = path.getLast();
        boolean intoCollector = belt.end() instanceof Belt.ToCollector;
        Offset end = switch (belt.end()) {
            case Belt.ToCollector to -> template.collectors().get(to.collector());
            case Belt.Merge merge -> {
                Belt next = template.belt(merge.belt());
                yield next == null ? null : next.path().get(merge.waypoint());
            }
        };
        if (end != null && !end.equals(last)) {
            line(tiles, last, end, intoCollector);
        } else {
            tiles.add(new Tile(last, tiles.isEmpty() ? "east" : tiles.getLast().travel()));
        }
        return tiles;
    }

    /** One belt's surface on its own (as if nothing fed into it). */
    public static List<Part> belt(Template template, Belt belt) {
        return surfaces(template, List.of(belt));
    }

    /**
     * The running surface of several belts laid out together (a plot's base belts and the conveyors it owns), so
     * stretches that meet in a straight line flow into each other seamlessly.
     *
     * <p>Each tile slides forward from its own block into the next, in a loop (see {@link Part#slide()}); the tiles
     * along a line are identical, so the loop can't be seen. Where the surface really starts, turns or ends, a fixed
     * piece sits a hair higher and hides the edge: a sliding tile appears from under the piece at a start or corner,
     * and disappears under the piece at an end. A belt reaching a collector meets it flush.
     */
    public static List<Part> surfaces(Template template, Collection<Belt> belts) {
        Map<Offset, Tile> tiles = new LinkedHashMap<>();
        Map<Offset, Double> speeds = new HashMap<>();
        for (Belt belt : belts) {
            for (Tile tile : tiles(template, belt)) {
                tiles.putIfAbsent(tile.bed(), tile);
                speeds.putIfAbsent(tile.bed(), belt.speed());
            }
        }
        List<Part> parts = new ArrayList<>();
        for (Tile tile : tiles.values()) {
            boolean moving = moves(tile, tiles);
            if (moving) parts.add(surface(tile, speeds.get(tile.bed()), false));
            if (starts(tile, tiles) || !moving) parts.add(surface(tile, 0, true));
        }
        return parts;
    }

    /** Nothing feeds this tile from behind going the same way: the surface starts (or turns) here. */
    private static boolean starts(Tile tile, Map<Offset, Tile> tiles) {
        Tile behind = tiles.get(tile.behind());
        return behind == null || !behind.travel().equals(tile.travel());
    }

    /**
     * A tile slides only into a block that hides or continues it: the next tile going the same way, or the fixed
     * piece where the surface turns. Otherwise (the end, or the side of another belt) it stays put as a fixed piece.
     */
    private static boolean moves(Tile tile, Map<Offset, Tile> tiles) {
        Tile ahead = tiles.get(tile.ahead());
        return ahead != null && (ahead.travel().equals(tile.travel()) || starts(ahead, tiles));
    }

    /** Tiles from {@code from} toward {@code to}, {@code to} itself only if {@code includeEnd}. */
    private static void line(List<Tile> tiles, Offset from, Offset to, boolean includeEnd) {
        int dx = to.x() - from.x(), dz = to.z() - from.z();
        String travel = Math.abs(dx) >= Math.abs(dz) ? (dx >= 0 ? "east" : "west") : (dz >= 0 ? "south" : "north");
        for (Offset block : Box.line(from, to, includeEnd)) tiles.add(new Tile(block, travel));
    }

    /**
     * A thin surface on a bed block: a moving tile sliding the way items go at {@code speed}, or a fixed piece
     * {@link #CAP_RAISE} higher. Glazed terracotta's arrow points away from the way the block faces.
     */
    private static Part surface(Tile tile, double speed, boolean fixed) {
        Offset bed = tile.bed();
        String data = "minecraft:magenta_glazed_terracotta[facing=" + opposite(tile.travel()) + "]";
        Vec at = new Vec(bed.x(), bed.y() + 1, bed.z());
        if (fixed) return new Part(true, data, at, new Vec(1, BELT_SURFACE + CAP_RAISE, 1), null);
        Vec slide = switch (tile.travel()) {
            case "east" -> new Vec(speed, 0, 0);
            case "west" -> new Vec(-speed, 0, 0);
            case "south" -> new Vec(0, 0, speed);
            default -> new Vec(0, 0, -speed);
        };
        return new Part(true, data, at, new Vec(1, BELT_SURFACE, 1), slide);
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
