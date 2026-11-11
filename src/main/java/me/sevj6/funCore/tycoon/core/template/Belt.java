package me.sevj6.funCore.tycoon.core.template;

import java.util.List;

/**
 * A conveyor's path: waypoints an item visits in order, at {@code speed} blocks a second. After the last waypoint it
 * either goes into a collector or joins another belt at one of that belt's waypoints and carries on along it.
 *
 * @param path  belt blocks (items ride on their top faces); at least one
 * @param speed blocks per second
 */
public record Belt(String id, double speed, List<Offset> path, End end) {

    public Belt {
        path = List.copyOf(path);
    }

    /** Where a belt sends items after its last waypoint. */
    public sealed interface End permits ToCollector, Merge {
    }

    /** Into the collector with this id. */
    public record ToCollector(String collector) implements End {
    }

    /** Onto {@code belt}, at its waypoint {@code waypoint} (0-based), carrying on from there. */
    public record Merge(String belt, int waypoint) implements End {
    }
}
