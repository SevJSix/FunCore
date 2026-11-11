package me.sevj6.funCore.tycoon.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MoneyTest {

    @Test
    void smallAmountsAreExact() {
        assertEquals("$0", Money.format(0));
        assertEquals("$5", Money.format(500));
        assertEquals("$12.50", Money.format(1250));
        assertEquals("$999.99", Money.format(99999));
    }

    @Test
    void largeAmountsAreShortAndRoundedDown() {
        assertEquals("$1K", Money.format(1000_00));
        assertEquals("$1.2K", Money.format(1250_00));
        assertEquals("$1.9K", Money.format(1999_00), "never shows more than the real amount");
        assertEquals("$240K", Money.format(240_000_00));
        assertEquals("$3.4M", Money.format(3_456_789_00L));
        assertEquals("$5.6B", Money.format(5_600_000_000_00L));
        assertEquals("$1T", Money.format(1_000_000_000_000_00L));
    }

    @Test
    void words() {
        assertEquals("1 TRILLION DOLLARS", Money.words(1_000_000_000_000_00L));
        assertEquals("2.5 BILLION DOLLARS", Money.words(2_590_000_000_00L));
        assertEquals("500 DOLLARS", Money.words(500_00));
        assertEquals("1 DOLLAR", Money.words(1_00));
    }

    @Test
    void negative() {
        assertEquals("-$1.5K", Money.format(-1500_00));
    }
}
