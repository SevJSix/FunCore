package me.sevj6.funCore.tycoon.core.template;

import me.sevj6.funCore.tycoon.core.TestTemplates;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaceholdersTest {

    private static List<Part> moving(List<Part> parts) {
        return parts.stream().filter(part -> part.slide() != null).toList();
    }

    private static List<Part> fixed(List<Part> parts) {
        return parts.stream().filter(part -> part.slide() == null).toList();
    }

    @Test
    void beltSurfaceRunsFlushIntoTheCollector() {
        // belt: [0,1,0] -> [4,1,0] -> [8,1,0], then into the collector at [10,1,0]
        Template template = TestTemplates.valid(TestTemplates.simple(""));
        List<Part> parts = Placeholders.belt(template, template.belt("belt"));
        // x 0 to 10, the collector's block included so the belt meets it flush
        List<Part> moving = moving(parts);
        assertEquals(10, moving.size(), "x 0 to 9 slide (each into the block ahead)");
        for (int i = 0; i < moving.size(); i++) {
            Part part = moving.get(i);
            assertEquals(new Vec(i, 2, 0), part.at(), "on top of the bed block");
            assertEquals(Placeholders.BELT_SURFACE, part.size().y());
            assertEquals(new Vec(2, 0, 0), part.slide(), "east, at the belt's 2 blocks a second");
            // travelling east: glazed terracotta faces west so its arrow points east
            assertTrue(part.data().contains("facing=west"), part.data());
        }
        // fixed pieces only at the start (hiding where tiles come from) and at the collector (hiding where they go)
        List<Part> fixed = fixed(parts);
        assertEquals(List.of(new Vec(0, 2, 0), new Vec(10, 2, 0)), fixed.stream().map(Part::at).toList());
        fixed.forEach(part -> assertEquals(Placeholders.BELT_SURFACE + Placeholders.CAP_RAISE, part.size().y(), 1e-9,
                "a hair above the moving surface, so they never share a plane"));
    }

    /** A plot with a 'main' belt x 4..8 into a collector at x 10 (z 0), plus {@code extra} components. */
    private static Template plot(String extra) {
        return TestTemplates.valid("""
                size: [32, 16, 32]
                base:
                  spawn: [1, 1, 20]
                  collectors: { main: [10, 1, 0], side: [24, 1, 8] }
                start-with: [main]
                components:
                  main:
                    type: conveyor
                    speed: 2
                    path: [[4, 1, 0], [8, 1, 0]]
                    collector: main
                """ + extra.stripIndent().indent(2));
    }

    @Test
    void stretchesInALineFlowIntoEachOther() {
        // 'feed' runs x 0..2 into 'main' at its start (x 4 is main's first waypoint; x 3 is feed's run into it)
        Template template = plot("""
                feed:
                  type: conveyor
                  requires: [main]
                  pad: [5, 1, 5]
                  speed: 2
                  path: [[0, 1, 0], [2, 1, 0]]
                  merge: { belt: main, waypoint: 0 }
                """);
        List<Part> together = Placeholders.surfaces(template, List.of(template.belt("feed"), template.belt("main")));
        assertEquals(List.of(new Vec(0, 2, 0), new Vec(10, 2, 0)), fixed(together).stream().map(Part::at).toList(),
                "only the very start and the collector: no fixed piece where the two meet");
        // on its own, 'main' starts at x 4 again
        assertEquals(List.of(new Vec(4, 2, 0), new Vec(10, 2, 0)),
                fixed(Placeholders.belt(template, template.belt("main"))).stream().map(Part::at).toList());
    }

    @Test
    void cornersHaveAFixedPiece() {
        Template template = plot("""
                bend:
                  type: conveyor
                  pad: [5, 1, 5]
                  speed: 2
                  path: [[20, 1, 0], [24, 1, 0], [24, 1, 4]]
                  collector: side
                """);
        List<Part> parts = Placeholders.belt(template, template.belt("bend"));
        // the start, the corner (where it turns south) and the collector
        assertEquals(List.of(new Vec(20, 2, 0), new Vec(24, 2, 0), new Vec(24, 2, 8)),
                fixed(parts).stream().map(Part::at).toList());
        // the tile before the corner still slides east, under the corner's fixed piece
        assertTrue(moving(parts).stream().anyMatch(p -> p.at().equals(new Vec(23, 2, 0)) && p.slide().x() > 0));
    }

    @Test
    void aBeltJoiningFromTheSideEndsInAFixedPiece() {
        // 'side' comes south down x 6 and joins 'main' (going east) at its waypoint x 8: it mustn't slide into main's tiles
        Template template = plot("""
                joiner:
                  type: conveyor
                  pad: [5, 1, 5]
                  speed: 2
                  path: [[8, 1, 6], [8, 1, 2]]
                  merge: { belt: main, waypoint: 1 }
                """);
        List<Part> parts = Placeholders.surfaces(template, List.of(template.belt("main"), template.belt("joiner")));
        // joiner's tiles run north x 8, z 6 down to z 1 (z 0 is main's); its last one, at z 1, stays put
        assertTrue(fixed(parts).stream().anyMatch(p -> p.at().equals(new Vec(8, 2, 1))));
        assertTrue(moving(parts).stream().noneMatch(p -> p.at().equals(new Vec(8, 2, 1))));
    }

    @Test
    void dropperSitsAboveItsSpout() {
        Template template = TestTemplates.valid(TestTemplates.simple(""));
        List<Part> parts = Placeholders.of(template, template.component("dropper"), 0);
        // spout [0, 3, 0]: the dropper block is the block above it, facing down
        assertEquals(new Vec(0, 4, 0), parts.getFirst().at());
        assertTrue(parts.getFirst().data().startsWith("minecraft:dropper[facing=down]"));
    }

    @Test
    void upgraderArchesOverItsWaypoint() {
        Template template = TestTemplates.valid(TestTemplates.simple("""
                arch:
                  type: upgrader
                  requires: [belt]
                  pad: [5, 1, 5]
                  at: { belt: belt, waypoint: 1 }
                  multiplier: 2
                """));
        List<Part> parts = Placeholders.of(template, template.component("arch"), 0);
        assertEquals(3, parts.size());
        // waypoint 1 is [4, 1, 0]: posts two blocks either side, the bar above the items' path
        assertEquals(new Vec(4.25, 2, -1.75), parts.get(0).at());
        assertEquals(new Vec(4.25, 2, 2.25), parts.get(1).at());
        assertTrue(parts.get(2).at().y() >= 4, "the bar is clear of items riding at dy 1");
    }

    @Test
    void readsTheVaultDoor() {
        Template template = TestTemplates.sample().template();
        Template.VaultDoor door = template.vaultDoor();
        assertEquals(new Offset(19, 1, 12), door.min());
        assertEquals(new Offset(21, 3, 12), door.max());
        assertEquals(9, door.blocks().size());
        assertEquals(1, door.unlockRebirths());
    }
}
