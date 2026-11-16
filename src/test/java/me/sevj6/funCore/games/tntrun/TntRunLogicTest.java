package me.sevj6.funCore.games.tntrun;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TntRunLogicTest {

    // ---- arena sizes ----

    @Test
    void autoSizeSplitsTheArenaPackWhicheverWayTheRegionIsDrawn() {
        // 25x25 floor, with the glass ring, with all the decoration; then the same for 50x50.
        for (int width : new int[]{25, 27, 43}) assertTrue(ArenaSizes.isSmall("auto", width, width), width + " is small");
        for (int width : new int[]{50, 52, 68}) assertFalse(ArenaSizes.isSmall("auto", width, width), width + " is large");
        assertFalse(ArenaSizes.isSmall("auto", 25, 50), "the wider side decides");
        assertTrue(ArenaSizes.isSmall("small", 68, 68), "set by hand");
        assertFalse(ArenaSizes.isSmall("large", 25, 25), "set by hand");
    }

    @Test
    void checksTheSizeOption() {
        assertEquals("small", ArenaSizes.check("Small"));
        assertThrows(IllegalArgumentException.class, () -> ArenaSizes.check("medium"));
    }

    private static final List<String> READY = List.of("small1", "small2", "large1", "large2");

    private static boolean small(String arena) {
        return arena.startsWith("small");
    }

    @Test
    void picksTheWantedSize() {
        assertEquals("small2", ArenaSizes.pick(READY, List.of("small2", "large1"), TntRunLogicTest::small, true, List::getFirst));
        assertEquals("large1", ArenaSizes.pick(READY, List.of("small2", "large1"), TntRunLogicTest::small, false, List::getFirst));
    }

    @Test
    void waitsWhenEveryArenaOfTheSizeIsBusy() {
        assertNull(ArenaSizes.pick(READY, List.of("large1", "large2"), TntRunLogicTest::small, true, List::getFirst),
                "2 players never get a large arena");
        assertNull(ArenaSizes.pick(READY, List.of(), TntRunLogicTest::small, false, List::getFirst));
    }

    @Test
    void anySizeWhenTheGameHasNoneOfThatSize() {
        List<String> onlyLarge = List.of("large1", "large2");
        assertEquals("large2", ArenaSizes.pick(onlyLarge, List.of("large2"), TntRunLogicTest::small, true, List::getFirst));
    }

    // ---- the floor ----

    @Test
    void keysRoundTrip() {
        for (int[] at : new int[][]{{0, 0, 0}, {-342, -60, 447}, {29_999_999, 319, -29_999_999}, {-1, -1, -1}}) {
            long key = FloorDecay.key(at[0], at[1], at[2]);
            assertEquals(at[0], FloorDecay.x(key));
            assertEquals(at[1], FloorDecay.y(key));
            assertEquals(at[2], FloorDecay.z(key));
        }
    }

    @Test
    void underIsTheBlockBelowTheFeet() {
        // Standing in the middle of block (10, 63, 20): feet at y 64.0.
        assertEquals(Set.of(FloorDecay.key(10, 63, 20)), FloorDecay.under(10.5, 64.0, 20.5, 0.3));
        // Mid-jump, it's still the block they left.
        assertEquals(Set.of(FloorDecay.key(10, 63, 20)), FloorDecay.under(10.5, 64.9, 20.5, 0.3));
        // Near the top of a jump, it's the air above it.
        assertEquals(Set.of(FloorDecay.key(10, 64, 20)), FloorDecay.under(10.5, 65.1, 20.5, 0.3));
    }

    @Test
    void straddlingAnEdgeOrCornerCountsEveryBlockUnderTheHitbox() {
        assertEquals(2, FloorDecay.under(11.0, 64.0, 20.5, 0.3).size(), "across an edge");
        assertEquals(Set.of(FloorDecay.key(10, 63, 20), FloorDecay.key(11, 63, 20), FloorDecay.key(10, 63, 21), FloorDecay.key(11, 63, 21)),
                FloorDecay.under(11.0, 64.0, 21.0, 0.3), "on a corner");
        assertEquals(1, FloorDecay.under(10.3, 64.0, 20.7, 0.3).size(), "lined up exactly with the block's sides");
        assertEquals(Set.of(FloorDecay.key(-1, 63, -1)), FloorDecay.under(-0.5, 64.0, -0.5, 0.3), "negative coordinates");
    }

    @Test
    void blocksGoAfterTheirDelayOnce() {
        FloorDecay decay = new FloorDecay();
        long a = FloorDecay.key(1, 1, 1), b = FloorDecay.key(2, 1, 1);
        assertTrue(decay.mark(a, 8));
        assertFalse(decay.mark(a, 9), "standing still doesn't put it off");
        assertTrue(decay.mark(b, 9));
        assertEquals(List.of(), decay.due(7));
        assertEquals(List.of(a), decay.due(8));
        assertEquals(List.of(b), decay.due(20));
        assertEquals(0, decay.size());
        assertTrue(decay.mark(a, 30), "a block that went can be marked again (e.g. after a reset)");
    }

    @Test
    void prettyNames() {
        assertEquals("Neon Arcade", TntRunGame.prettyName("neon_arcade"));
        assertEquals("Lava1", TntRunGame.prettyName("lava1"));
    }
}
