package me.sevj6.funCore.tycoon.core.sim;

/**
 * Tycoon money math that never breaks. Balances and prices are {@code double} cents, from 0 up to {@link #MAX} (about
 * $1.8 x 10^306), so there's no practical cap. A double holds whole cents exactly up to about $90 trillion; past that,
 * amounts are kept to about 16 significant digits (tiny additions to a huge balance round away). Nothing here ever
 * gives NaN, infinity or a negative amount: those come out as 0 or {@link #MAX}.
 */
public final class Cents {

    /** The most any tycoon balance or price can be: the largest finite {@code double} (about $1.8 x 10^306). */
    public static final double MAX = Double.MAX_VALUE;

    /** Below this, a double holds every whole number of cents exactly (2^53, about $90 trillion). */
    public static final double EXACT = 9_007_199_254_740_992.0;

    private Cents() {
    }

    /** Whole cents, rounded down, between 0 and {@link #MAX}. NaN and negatives are 0, infinity is {@link #MAX}. */
    public static double whole(double cents) {
        if (!(cents > 0)) return 0;
        if (cents >= MAX) return MAX;
        return Math.floor(cents);
    }

    /** An amount kept between 0 and {@link #MAX}, without rounding. NaN and negatives are 0. */
    public static double clamp(double cents) {
        if (!(cents > 0)) return 0;
        return Math.min(cents, MAX);
    }

    /** {@code a + b}, never past {@link #MAX} (both are taken as at least 0; NaN counts as 0). */
    public static double add(double a, double b) {
        return clamp(clamp(a) + clamp(b));
    }
}
