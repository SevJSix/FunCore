package me.sevj6.funCore.tycoon.core.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CentsTest {

    @Test
    void wholeRoundsDownAndSaturates() {
        assertEquals(0, Cents.whole(-5));
        assertEquals(0, Cents.whole(Double.NaN));
        assertEquals(12, Cents.whole(12.99));
        assertEquals(Cents.MAX, Cents.whole(1e300));
        assertEquals(Cents.MAX, Cents.whole(Double.POSITIVE_INFINITY));
    }

    @Test
    void addSaturatesAtTheCap() {
        assertEquals(5, Cents.add(2, 3));
        assertEquals(Cents.MAX, Cents.add(Cents.MAX - 1, 10));
        assertEquals(Cents.MAX, Cents.add(Cents.MAX, Cents.MAX));
        assertEquals(3, Cents.add(-7, 3), "negatives count as 0");
    }

    @Test
    void theCapIsFarAboveTheServerEconomy() {
        // $90 quadrillion: well past the global $1T limit, and still safe to add to without overflowing a long.
        assertEquals(9_000_000_000_000_000_000L, Cents.MAX);
        assertEquals(Cents.MAX, Cents.add(Cents.MAX, Long.MAX_VALUE));
    }
}
