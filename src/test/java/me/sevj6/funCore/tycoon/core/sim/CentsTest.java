package me.sevj6.funCore.tycoon.core.sim;

import me.sevj6.funCore.economy.Cash;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CentsTest {

    @Test
    void wholeRoundsDownAndSaturates() {
        assertEquals(0, Cents.whole(-5));
        assertEquals(0, Cents.whole(Double.NaN));
        assertEquals(12, Cents.whole(12.99));
        assertEquals(Cash.MAX, Cents.whole(1e300));
        assertEquals(Cash.MAX, Cents.whole(Double.POSITIVE_INFINITY));
    }

    @Test
    void headroomNeverGoesNegative() {
        assertEquals(Cash.MAX, Cents.headroom(0));
        assertEquals(0, Cents.headroom(Cash.MAX));
        assertEquals(0, Cents.headroom(Long.MAX_VALUE));
        assertEquals(5, Cents.headroom(Cash.MAX - 5));
    }
}
