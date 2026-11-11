package me.sevj6.funCore.tycoon.core.sim;

import me.sevj6.funCore.economy.Cash;

/**
 * Money math that never overflows. Values while simulating are {@code double} cents (multipliers make fractions; a
 * double is exact to well past {@link Cash#MAX}); turning them into the economy's {@code long} cents saturates.
 */
public final class Cents {

    private Cents() {
    }

    /** Whole cents, rounded down, between 0 and {@link Cash#MAX}. NaN and negatives are 0. */
    public static long whole(double cents) {
        if (!(cents > 0)) return 0;
        if (cents >= Cash.MAX) return Cash.MAX;
        return (long) Math.floor(cents);
    }

    /** How much more a balance can take before it hits {@link Cash#MAX}. */
    public static long headroom(long balance) {
        return Math.max(0, Cash.MAX - Math.max(0, balance));
    }
}
