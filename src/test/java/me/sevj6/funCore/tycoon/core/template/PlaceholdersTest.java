package me.sevj6.funCore.tycoon.core.template;

import me.sevj6.funCore.tycoon.core.TestTemplates;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaceholdersTest {

    @Test
    void beltSurfaceCoversEveryBedBlockUpToTheCollector() {
        // belt: [0,1,0] -> [4,1,0] -> [8,1,0], then into the collector at [10,1,0]
        Template template = TestTemplates.valid(TestTemplates.simple(""));
        List<Part> parts = Placeholders.of(template, template.component("belt"), 0);
        // x 0 to 9: the collector block itself is left alone
        assertEquals(10, parts.size());
        for (int i = 0; i < parts.size(); i++) {
            Part part = parts.get(i);
            assertEquals(new Vec(i, 2, 0), part.at(), "on top of the bed block");
            assertEquals(Placeholders.BELT_SURFACE, part.size().y());
            // travelling east: glazed terracotta faces west so its arrow points east
            assertTrue(part.data().contains("facing=west"), part.data());
        }
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
