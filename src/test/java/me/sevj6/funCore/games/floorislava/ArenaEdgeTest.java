package me.sevj6.funCore.games.floorislava;

import me.sevj6.funCore.arena.Region;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArenaEdgeTest {

    /** The live lava1 arena: x -342..-276, z -447..-380 (blocks, both ends included). */
    private static final Region LAVA1 = new Region(-342, 170, -447, -276, 222, -380);

    @Test
    void distanceIsToTheNearestSide() {
        assertEquals(0.5, ArenaEdge.distance(LAVA1, -341.5, -400), 1e-9, "middle of the westmost block");
        assertEquals(0.5, ArenaEdge.distance(LAVA1, -275.5, -400), 1e-9, "middle of the eastmost block (-276 spans -276..-275)");
        assertEquals(3.5, ArenaEdge.distance(LAVA1, -337.5, -443.5), 1e-9, "lava1's closest spawn");
        assertTrue(ArenaEdge.distance(LAVA1, -343, -400) < 0, "outside");
    }

    @Test
    void pushesAwayFromTheSide() {
        assertEquals(new ArenaEdge.Push(1, 0), ArenaEdge.push(LAVA1, -341.5, -400, 2), "west side: pushed east");
        assertEquals(new ArenaEdge.Push(-1, 0), ArenaEdge.push(LAVA1, -276.2, -400, 2), "east side: pushed west");
        assertEquals(new ArenaEdge.Push(0, 1), ArenaEdge.push(LAVA1, -300, -446, 2), "north side: pushed south");
        assertEquals(new ArenaEdge.Push(0, -1), ArenaEdge.push(LAVA1, -300, -379.5, 2), "south side: pushed north");
    }

    @Test
    void cornersPushDiagonally() {
        ArenaEdge.Push push = ArenaEdge.push(LAVA1, -341.5, -446.5, 2);
        double half = Math.sqrt(0.5);
        assertEquals(half, push.x(), 1e-9);
        assertEquals(half, push.z(), 1e-9);
    }

    @Test
    void farFromTheSidesNothingHappens() {
        assertNull(ArenaEdge.push(LAVA1, -339.9, -400, 2), "2.1 blocks in");
        assertNull(ArenaEdge.push(LAVA1, -337.5, -443.5, 2), "no spawn of lava1 is pushed");
        assertNull(ArenaEdge.push(LAVA1, -341.5, -400, 0), "0 turns it off");
    }

    @Test
    void outsideTheRegionStillPushesBackIn() {
        assertEquals(new ArenaEdge.Push(1, 0), ArenaEdge.push(LAVA1, -345, -400, 2));
    }

    @Test
    void tooNarrowToPushEitherWay() {
        Region strip = new Region(0, 0, 0, 2, 10, 100); // 3 wide: the middle is within 2 of both long sides
        assertNull(ArenaEdge.push(strip, 1.5, 50, 2));
    }

    @Test
    void theOutermostRingCantBeBuiltOn() {
        assertTrue(ArenaEdge.inBuildGap(LAVA1, -342, -400, 1), "westmost column");
        assertTrue(ArenaEdge.inBuildGap(LAVA1, -276, -400, 1), "eastmost column");
        assertTrue(ArenaEdge.inBuildGap(LAVA1, -300, -447, 1), "northmost row");
        assertTrue(ArenaEdge.inBuildGap(LAVA1, -300, -380, 1), "southmost row");
        assertFalse(ArenaEdge.inBuildGap(LAVA1, -341, -446, 1), "one block in is fine");
        assertTrue(ArenaEdge.inBuildGap(LAVA1, -341, -400, 2), "a gap of 2 takes the next one too");
        assertFalse(ArenaEdge.inBuildGap(LAVA1, -342, -400, 0), "0 turns it off");
    }
}
