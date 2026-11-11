package me.sevj6.funCore.tycoon.core.sim;

import me.sevj6.funCore.tycoon.core.TestTemplates;
import me.sevj6.funCore.tycoon.core.template.Component;
import me.sevj6.funCore.tycoon.core.template.Template;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutesTest {

    @Test
    void dropThenAlongTheBeltIntoTheCollector() {
        Template template = TestTemplates.valid(TestTemplates.simple(""));
        List<Segment> route = Routes.of(template, (Component.Dropper) template.component("dropper"));
        // drop, waypoint 0 -> 1, 1 -> 2, 2 -> collector
        assertEquals(4, route.size());
        assertEquals(new Segment.Waypoint("belt", 0), route.get(0).stop());
        assertEquals(new Segment.Waypoint("belt", 1), route.get(1).stop());
        assertEquals(new Segment.Waypoint("belt", 2), route.get(2).stop());
        assertEquals(new Segment.Collector("main"), route.get(3).stop());
        // 4 blocks at 2 blocks a second = 40 ticks
        assertEquals(40, route.get(1).ticks());
        // the drop: from the spout's centre (y 3.5) to the belt's top (y 2), at the drop speed, but at least the minimum
        assertEquals(Math.max(Routes.MIN_DROP_TICKS, Routes.ticks(1.5, Routes.DROP_SPEED)), route.get(0).ticks());
    }

    @Test
    void longStraightsAreSplitForInterpolation() {
        Template template = TestTemplates.valid("""
                size: [64, 8, 64]
                base:
                  spawn: [1, 1, 1]
                  collectors: { main: [0, 1, 50] }
                start-with: [belt, dropper]
                components:
                  belt:
                    type: conveyor
                    speed: 1
                    path: [[0, 1, 0], [0, 1, 40]]
                    collector: main
                  dropper:
                    type: dropper
                    requires: [belt]
                    spout: [0, 3, 0]
                    interval: 20
                    value: 1
                    route: { belt: belt, waypoint: 0 }
                """);
        List<Segment> route = Routes.of(template, (Component.Dropper) template.component("dropper"));
        int total = 0;
        for (Segment segment : route.subList(1, route.size())) {
            assertTrue(segment.ticks() <= Routes.MAX_SEGMENT_TICKS, "segment of " + segment.ticks() + " ticks");
            total += segment.ticks();
        }
        // 40 blocks + 10 to the collector at 1 block a second = 1000 ticks, however it's split
        assertEquals(1000, total);
        // only the last piece of each straight carries the stop
        assertNull(route.get(1).stop());
        assertInstanceOf(Segment.Collector.class, route.getLast().stop());
    }

    @Test
    void mergeCarriesOnAlongTheOtherBelt() {
        Template template = TestTemplates.valid(TestTemplates.simple("""
                side:
                  type: conveyor
                  requires: [belt]
                  pad: [5, 1, 5]
                  speed: 2
                  path: [[4, 1, 8], [4, 1, 2]]
                  merge: { belt: belt, waypoint: 1 }
                fed:
                  type: dropper
                  requires: [side]
                  pad: [6, 1, 5]
                  spout: [4, 3, 8]
                  interval: 20
                  value: 1
                  route: { belt: side, waypoint: 0 }
                """));
        List<Segment> route = Routes.of(template, (Component.Dropper) template.component("fed"));
        // (the 6-block straight takes 60 ticks, so it is split in two; the split point has no stop)
        List<Segment.Stop> stops = route.stream().map(Segment::stop).filter(java.util.Objects::nonNull).toList();
        assertEquals(List.of(new Segment.Waypoint("side", 0), new Segment.Waypoint("side", 1),
                new Segment.Waypoint("belt", 1), new Segment.Waypoint("belt", 2), new Segment.Collector("main")), stops);
    }
}
