package me.sevj6.funCore.tycoon.core.sim;

import me.sevj6.funCore.tycoon.core.template.Belt;
import me.sevj6.funCore.tycoon.core.template.Component;
import me.sevj6.funCore.tycoon.core.template.Offset;
import me.sevj6.funCore.tycoon.core.template.Template;
import me.sevj6.funCore.tycoon.core.template.Vec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Works out the path a dropper's items take: the drop from the spout onto the belt, then along each belt (and each
 * one it merges into) to a collector, as a list of {@link Segment}s.
 */
public final class Routes {

    /** The longest a display entity's teleport can be interpolated over, in ticks (Minecraft's limit). */
    public static final int MAX_SEGMENT_TICKS = 59;
    /** How fast items fall from the spout onto the belt, in blocks per second. */
    public static final double DROP_SPEED = 8.0;
    /** The shortest drop, in ticks, so it still reads as a drop. */
    public static final int MIN_DROP_TICKS = 3;

    private Routes() {
    }

    /** The dropper's route. The template must be valid (every belt and waypoint it names exists). */
    public static List<Segment> of(Template template, Component.Dropper dropper) {
        List<Segment> route = new ArrayList<>();
        Belt belt = template.belt(dropper.belt());
        if (belt == null) throw new IllegalArgumentException("unknown belt " + dropper.belt());
        int index = dropper.waypoint();

        Vec spout = dropper.spout().center();
        Vec landing = belt.path().get(index).top();
        add(route, spout, landing, Math.max(MIN_DROP_TICKS, ticks(spout.distance(landing), DROP_SPEED)),
                new Segment.Waypoint(belt.id(), index));

        Set<String> visited = new HashSet<>();
        while (visited.add(belt.id())) {
            List<Offset> path = belt.path();
            for (int i = index; i + 1 < path.size(); i++) {
                Vec from = path.get(i).top(), to = path.get(i + 1).top();
                add(route, from, to, ticks(from.distance(to), belt.speed()), new Segment.Waypoint(belt.id(), i + 1));
            }
            Vec last = path.getLast().top();
            switch (belt.end()) {
                case Belt.ToCollector to -> {
                    Vec collector = template.collectors().get(to.collector()).top();
                    add(route, last, collector, ticks(last.distance(collector), belt.speed()), new Segment.Collector(to.collector()));
                    return route;
                }
                case Belt.Merge merge -> {
                    Belt next = template.belt(merge.belt());
                    if (next == null) throw new IllegalArgumentException("unknown belt " + merge.belt());
                    Vec join = next.path().get(merge.waypoint()).top();
                    add(route, last, join, ticks(last.distance(join), belt.speed()), new Segment.Waypoint(next.id(), merge.waypoint()));
                    belt = next;
                    index = merge.waypoint();
                }
            }
        }
        throw new IllegalArgumentException("belt " + belt.id() + " merges round in a loop");
    }

    /** Ticks to cover {@code distance} blocks at {@code speed} blocks a second; at least 1. */
    static int ticks(double distance, double speed) {
        return Math.max(1, (int) Math.round(distance / speed * 20.0));
    }

    /** Adds a move, split into equal parts if it's too long to interpolate in one go. */
    private static void add(List<Segment> route, Vec from, Vec to, int ticks, Segment.Stop stop) {
        int parts = (ticks + MAX_SEGMENT_TICKS - 1) / MAX_SEGMENT_TICKS;
        int done = 0;
        for (int part = 1; part <= parts; part++) {
            int until = (int) Math.round((double) ticks * part / parts);
            Vec start = from.lerp(to, (double) (part - 1) / parts);
            Vec end = part == parts ? to : from.lerp(to, (double) part / parts);
            route.add(new Segment(start, end, until - done, part == parts ? stop : null));
            done = until;
        }
    }
}
