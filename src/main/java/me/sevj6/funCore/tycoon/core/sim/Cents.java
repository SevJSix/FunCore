package me.sevj6.funCore.tycoon.core.sim;

/**
 * Tycoon money math that never overflows. The tycoon has its own balance (not the server's economy), in {@code long}
 * cents up to {@link #MAX}. Values while simulating are {@code double} cents (multipliers make fractions; a double is
 * exact to about 9 x 10^15, and close enough beyond); turning them into {@code long} cents saturates.
 */
public final class Cents {

    /** The most any tycoon balance or price can be: $90 quadrillion, safely inside a {@code long}. */
    public static final long MAX = 9_000_000_000_000_000_000L;

    private Cents() {
    }

    /** Whole cents, rounded down, between 0 and {@link #MAX}. NaN and negatives are 0. */
    public static long whole(double cents) {
        if (!(cents > 0)) return 0;
        if (cents >= MAX) return MAX;
        return (long) Math.floor(cents);
    }

    /** {@code a + b}, never past {@link #MAX} (both are taken as at least 0). */
    public static long add(long a, long b) {
        long x = Math.max(0, a), y = Math.max(0, b);
        return x > MAX - y ? MAX : x + y;
    }
}
