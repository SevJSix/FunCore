package me.sevj6.funCore.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TimeFormatTest {

    @Test
    void showsMilliseconds() {
        assertEquals("0:00.000", TimeFormat.withMillis(0));
        assertEquals("0:00.004", TimeFormat.withMillis(4));
        assertEquals("0:05.250", TimeFormat.withMillis(5_250));
        assertEquals("1:30.450", TimeFormat.withMillis(90_450));
        assertEquals("59:59.999", TimeFormat.withMillis(3_599_999));
        assertEquals("1:00:00.000", TimeFormat.withMillis(3_600_000));
        assertEquals("1:02:03.004", TimeFormat.withMillis(3_723_004));
        assertEquals("0:00.000", TimeFormat.withMillis(-5), "negative is 0");
    }

    @Test
    void ticksAreFiftyMillisecondsEach() {
        assertEquals("0:00.050", TimeFormat.ticksWithMillis(1));
        assertEquals("1:30.450", TimeFormat.ticksWithMillis(1_809));
        assertEquals("0:00.000", TimeFormat.ticksWithMillis(-1));
        // Never overflows into a negative time.
        assertEquals(TimeFormat.withMillis(Long.MAX_VALUE / 50 * 50), TimeFormat.ticksWithMillis(Long.MAX_VALUE));
    }
}
