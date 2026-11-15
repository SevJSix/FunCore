package me.sevj6.funCore.games.clutch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RunTimerTest {

    private static final long PREVIOUS_TICK = 1_000_000_000L;
    private static final long TICK = PREVIOUS_TICK + RunTimer.NANOS_PER_TICK;

    @Test
    void aLandingIsWhenTheMovementArrived() {
        long arrived = PREVIOUS_TICK + 17_300_000L; // 17.3 ms into the tick
        assertEquals(arrived, RunTimer.movedAt(arrived, PREVIOUS_TICK, TICK));
        assertEquals(TICK, RunTimer.movedAt(TICK, PREVIOUS_TICK, TICK), "right on the tick counts");
    }

    @Test
    void withoutMovementInTheTickItsTheTicksTime() {
        assertEquals(TICK, RunTimer.movedAt(RunTimer.NONE, PREVIOUS_TICK, TICK), "riding something, or a pearl");
        assertEquals(TICK, RunTimer.movedAt(PREVIOUS_TICK, PREVIOUS_TICK, TICK), "from the tick before: stale");
        assertEquals(TICK, RunTimer.movedAt(PREVIOUS_TICK - 5_000_000L, PREVIOUS_TICK, TICK), "stale");
        assertEquals(TICK, RunTimer.movedAt(TICK + 1, PREVIOUS_TICK, TICK), "never after the tick that saw it");
    }

    @Test
    void worksWhereverNanoTimeIs() {
        // System.nanoTime() can be negative, or wrap around.
        long previous = Long.MAX_VALUE - 10_000_000L;
        long tick = previous + RunTimer.NANOS_PER_TICK; // wrapped past Long.MAX_VALUE
        long arrived = previous + 20_000_000L;
        assertEquals(arrived, RunTimer.movedAt(arrived, previous, tick));
        assertEquals(20, RunTimer.millis(previous, arrived));
    }

    @Test
    void millisecondsRoundDownAndNeverGoBackwards() {
        assertEquals(0, RunTimer.millis(0, 999_999));
        assertEquals(1, RunTimer.millis(0, 1_000_000));
        assertEquals(44_123, RunTimer.millis(5_000_000_000L, 5_000_000_000L + 44_123_456_789L));
        assertEquals(0, RunTimer.millis(10, 0));
    }

    @Test
    void theWaitToStayStillIsAddedInTicks() {
        assertEquals(1_000_000_000L, RunTimer.ticksToNanos(20), "landed-seconds 1.0");
    }

    @Test
    void oldTickScoresBecomeMilliseconds() {
        assertEquals(List.of(0L, 44_000L), RunTimer.upgradeScore(1, List.of(0L, 880L)));
        assertEquals(List.of(1L, 45_950L), RunTimer.upgradeScore(1, List.of(1L, 919L)));
        assertEquals(List.of(0L, 44_123L), RunTimer.upgradeScore(2, List.of(0L, 44_123L)), "already milliseconds");
    }

    @Test
    void recordsShowTheMilliseconds() {
        assertEquals("0 deaths, 0:44.123", ClutchGame.recordText(List.of(0L, 44_123L)));
        assertEquals("1 death, 0:49.800", ClutchGame.recordText(List.of(1L, 49_800L)));
    }
}
