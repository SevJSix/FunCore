package me.sevj6.funCore.tycoon.core.sim;

import me.sevj6.funCore.tycoon.core.template.Vec;
import org.jetbrains.annotations.Nullable;

/**
 * One straight move of an item, taking {@code ticks} ticks. Its view slides from {@code from} to {@code to} on its
 * own (the client interpolates), so the server only acts at the end of each segment.
 *
 * @param stop what the item reaches at the end, or {@code null} partway along a long straight (split so that no
 *             segment is longer than display entities can interpolate)
 */
public record Segment(Vec from, Vec to, int ticks, @Nullable Stop stop) {

    /** What's at the end of a segment. */
    public sealed interface Stop permits Waypoint, Collector {
    }

    /** Waypoint {@code index} of belt {@code belt}: where upgraders sit. */
    public record Waypoint(String belt, int index) implements Stop {
    }

    /** The item is cashed in here. */
    public record Collector(String collector) implements Stop {
    }
}
