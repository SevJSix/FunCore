package me.sevj6.funCore.games.risingtide;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TideScheduleTest {

    private final TideSchedule schedule = new TideSchedule(400, 100); // 20 s to build, then a layer every 5 s

    @Test
    void nothingRisesDuringTheStartDelay() {
        assertEquals(0, schedule.layersBy(0));
        assertEquals(0, schedule.layersBy(399));
        assertEquals(400, schedule.ticksUntilNext(0));
        assertEquals(1, schedule.ticksUntilNext(399));
    }

    @Test
    void theFirstLayerRisesRightAtTheEndOfTheDelayThenOneEveryInterval() {
        assertEquals(1, schedule.layersBy(400));
        assertEquals(1, schedule.layersBy(499));
        assertEquals(2, schedule.layersBy(500));
        assertEquals(11, schedule.layersBy(1400));
        assertEquals(100, schedule.ticksUntilNext(400));
        assertEquals(1, schedule.ticksUntilNext(499));
    }

    @Test
    void noDelayAndTinyIntervalsAreSafe() {
        TideSchedule fast = new TideSchedule(-5, 0);
        assertEquals(1, fast.layersBy(0));
        assertEquals(3, fast.layersBy(2));
        assertEquals(1, fast.ticksUntilNext(7));
    }

    @Test
    void refillsComeEveryNthLayerAndNeverWhenOff() {
        assertFalse(TideSchedule.refillsAt(0, 4));
        assertFalse(TideSchedule.refillsAt(3, 4));
        assertTrue(TideSchedule.refillsAt(4, 4));
        assertTrue(TideSchedule.refillsAt(8, 4));
        assertFalse(TideSchedule.refillsAt(4, 0));
    }
}
