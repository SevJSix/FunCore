package me.sevj6.funCore.games.buildbattle;

import me.sevj6.funCore.arena.Region;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlotFinderTest {

    /** Two rows of 25x25 plots with 3-wide borders and a 7-wide walkway between the rows, like the shipped map. */
    private static final Region ARENA = new Region(0, 0, 0, 142, 34, 68);

    private static boolean isFloor(int x, int z) {
        boolean column = x >= 3 && (x - 3) % 28 < 25 && x <= 139;
        boolean row = (z >= 3 && z <= 27) || (z >= 41 && z <= 65);
        return column && row;
    }

    @Test
    void findsTheWholePlotFromAnywhereInIt() {
        Region expected = new Region(31, 1, 41, 55, 33, 65);
        assertEquals(expected, PlotFinder.find(43, 1, 53, 32, ARENA, PlotFinderTest::isFloor));
        assertEquals(expected, PlotFinder.find(31, 1, 41, 32, ARENA, PlotFinderTest::isFloor));
        assertEquals(expected, PlotFinder.find(55, 1, 65, 32, ARENA, PlotFinderTest::isFloor));
    }

    @Test
    void heightStopsAtTheArenaTop() {
        assertEquals(34, PlotFinder.find(10, 1, 10, 100, ARENA, PlotFinderTest::isFloor).maxY());
    }

    @Test
    void overlapIsByColumns() {
        Region a = new Region(0, 0, 0, 10, 5, 10);
        assertTrue(PlotFinder.overlap(a, new Region(10, 50, 10, 20, 60, 20)));
        assertFalse(PlotFinder.overlap(a, new Region(11, 0, 0, 20, 5, 10)));
    }

    @Test
    void viewsFromTheWalkwaySide() {
        List<Region> plots = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            plots.add(new Region(3 + 28 * i, 1, 3, 27 + 28 * i, 33, 27));
            plots.add(new Region(3 + 28 * i, 1, 41, 27 + 28 * i, 33, 65));
        }
        assertEquals(PlotFinder.Side.SOUTH, PlotFinder.openSide(plots.get(0), plots, ARENA, 10));
        assertEquals(PlotFinder.Side.NORTH, PlotFinder.openSide(plots.get(1), plots, ARENA, 10));
        assertEquals(PlotFinder.Side.SOUTH, PlotFinder.openSide(plots.get(8), plots, ARENA, 10));
        // Boxed in on every side: no view.
        assertNull(PlotFinder.openSide(plots.get(0), plots, new Region(3, 0, 3, 27, 34, 27), 10));
    }
}
