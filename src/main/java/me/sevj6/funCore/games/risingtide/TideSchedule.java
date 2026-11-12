package me.sevj6.funCore.games.risingtide;

/**
 * When the lava rises: nothing for a start delay, then one layer straight away and another every interval.
 *
 * @param startDelayTicks match ticks before the first layer
 * @param intervalTicks   ticks between layers (at least 1)
 */
record TideSchedule(long startDelayTicks, long intervalTicks) {

    TideSchedule {
        startDelayTicks = Math.max(0, startDelayTicks);
        intervalTicks = Math.max(1, intervalTicks);
    }

    /** How many layers should have risen by this match tick (not capped by the arena's height). */
    int layersBy(long tick) {
        if (tick < startDelayTicks) return 0;
        long layers = 1 + (tick - startDelayTicks) / intervalTicks;
        return (int) Math.min(Integer.MAX_VALUE, layers);
    }

    /** Ticks from this match tick until the next layer rises (at least 1). */
    long ticksUntilNext(long tick) {
        if (tick < startDelayTicks) return startDelayTicks - tick;
        return intervalTicks - (tick - startDelayTicks) % intervalTicks;
    }

    /** Whether players get a refill when this layer (1 = the first) rises; {@code every} 0 means never. */
    static boolean refillsAt(int layer, int every) {
        return every > 0 && layer > 0 && layer % every == 0;
    }
}
