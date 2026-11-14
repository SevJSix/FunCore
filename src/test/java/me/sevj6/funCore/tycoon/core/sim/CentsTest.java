package me.sevj6.funCore.tycoon.core.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CentsTest {

    @Test
    void wholeRoundsDownAndSaturates() {
        assertEquals(0, Cents.whole(-5));
        assertEquals(0, Cents.whole(Double.NaN));
        assertEquals(12, Cents.whole(12.99));
        assertEquals(1e300, Cents.whole(1e300), "far past the old $90 quadrillion cap");
        assertEquals(Cents.MAX, Cents.whole(Double.POSITIVE_INFINITY));
    }

    @Test
    void addSaturatesAtTheCap() {
        assertEquals(5, Cents.add(2, 3));
        assertEquals(Cents.MAX, Cents.add(Cents.MAX, 10));
        assertEquals(Cents.MAX, Cents.add(Cents.MAX, Cents.MAX), "never infinity");
        assertEquals(3, Cents.add(-7, 3), "negatives count as 0");
        assertEquals(3, Cents.add(Double.NaN, 3), "NaN counts as 0");
        assertEquals(Cents.MAX, Cents.add(1, Double.POSITIVE_INFINITY));
    }

    @Test
    void clampKeepsAmountsInRange() {
        assertEquals(0, Cents.clamp(Double.NaN));
        assertEquals(0, Cents.clamp(-1));
        assertEquals(12.5, Cents.clamp(12.5), "no rounding");
        assertEquals(Cents.MAX, Cents.clamp(Double.POSITIVE_INFINITY));
    }

    @Test
    void theCapIsTheLargestDouble() {
        // About $1.8 x 10^306: the old cap was $90 quadrillion (9 x 10^18 cents in a long).
        assertEquals(Double.MAX_VALUE, Cents.MAX);
        assertTrue(Cents.MAX > 9e18 * 1e280);
        // Whole cents are exact up to about $90 trillion.
        assertEquals(Cents.EXACT, Cents.EXACT + 1, "past it, a single cent rounds away");
        assertEquals(Cents.EXACT - 1, Cents.whole(Cents.EXACT - 1));
    }
}
