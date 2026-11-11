package me.sevj6.funCore.tycoon.core;

import me.sevj6.funCore.tycoon.core.sim.Cents;

/**
 * What a plot earns while its owner is away: what it was earning by itself when they left (droppers, upgraders,
 * earning cosmetics; not clicking or stars), for the time they were gone, up to a cap, so being away can't stand in for
 * playing through the upgrades.
 */
public final class AwayEarnings {

    private AwayEarnings() {
    }

    /**
     * @param awayRate  cents a second the plot earned by itself when they left
     * @param awayMs    how long they were gone, in milliseconds
     * @param share     how much of that rate counts (1 = all of it)
     * @param maxHours  the most time that counts
     * @return whole cents earned
     */
    public static long earned(double awayRate, long awayMs, double share, double maxHours) {
        if (!(awayRate > 0) || awayMs <= 0 || !(share > 0) || !(maxHours > 0)) return 0;
        double seconds = Math.min(awayMs / 1000.0, maxHours * 3600.0);
        return Cents.whole(awayRate * share * seconds);
    }
}
