package me.sevj6.funCore.games.clutch;

import java.util.ArrayList;
import java.util.List;

/**
 * Clutch's stopwatch arithmetic, Bukkit-free so it's unit-tested. Runs are timed in real time
 * ({@link System#nanoTime()}), to the millisecond, from the start of a player's run to the moment they landed their
 * last level, plus the {@code landed-seconds} they then have to stay still (it was always part of a run's time).
 *
 * <p>The moment of a landing is when the movement that brought them to rest reached the server: the server only
 * checks for stillness once a tick (every 50 ms), but movement arrives in between. If none arrived in the tick before
 * the check (riding something, a pearl), the tick's own time is used.
 */
final class RunTimer {

    /** "No movement seen since the last tick." */
    static final long NONE = Long.MIN_VALUE;

    /** A tick is 50 ms. */
    static final long NANOS_PER_TICK = 50_000_000L;

    /** Leaderboard score version: 1 was {@code [deaths, ticks]}, 2 is {@code [deaths, milliseconds]}. */
    static final int SCORE_VERSION = 2;

    private RunTimer() {
    }

    /**
     * When the movement seen moving on this tick happened: the last movement that reached the server since the
     * previous tick, if there was one in that window, else this tick's own time.
     *
     * @param moveNanos         the last movement since the previous tick, or {@link #NONE}
     * @param previousTickNanos when the previous tick ran
     * @param tickNanos         when this tick runs
     */
    static long movedAt(long moveNanos, long previousTickNanos, long tickNanos) {
        if (moveNanos == NONE) return tickNanos;
        return moveNanos - previousTickNanos > 0 && tickNanos - moveNanos >= 0 ? moveNanos : tickNanos;
    }

    /** Whole milliseconds from one {@link System#nanoTime()} to a later one (rounded down; never negative). */
    static long millis(long fromNanos, long toNanos) {
        return Math.max(0, Math.floorDiv(toNanos - fromNanos, 1_000_000L));
    }

    /** {@code ticks} as nanoseconds (the {@code landed-seconds} wait, added to a landing). */
    static long ticksToNanos(long ticks) {
        return ticks * NANOS_PER_TICK;
    }

    /** Upgrades a leaderboard score saved by an older version: {@code [deaths, ticks]} to {@code [deaths, ms]}. */
    static List<Long> upgradeScore(int savedVersion, List<Long> score) {
        List<Long> upgraded = new ArrayList<>(score);
        if (savedVersion < 2 && upgraded.size() >= 2) upgraded.set(1, upgraded.get(1) * 50);
        return upgraded;
    }
}
