package me.sevj6.funCore.tycoon.core;

import me.sevj6.funCore.tycoon.core.sim.Cents;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoneyTest {

    @Test
    void smallAmountsAreExact() {
        assertEquals("$0", Money.format(0));
        assertEquals("$5", Money.format(500));
        assertEquals("$12.50", Money.format(1250));
        assertEquals("$999.99", Money.format(99999));
    }

    @Test
    void largeAmountsAreSpelledOutAndRoundedDown() {
        assertEquals("$1 Thousand", Money.format(1000_00));
        assertEquals("$1.2 Thousand", Money.format(1250_00));
        assertEquals("$1.9 Thousand", Money.format(1999_00), "never shows more than the real amount");
        assertEquals("$240 Thousand", Money.format(240_000_00));
        assertEquals("$3.4 Million", Money.format(3_456_789_00L));
        assertEquals("$5.6 Billion", Money.format(5_600_000_000_00L));
        assertEquals("$1 Trillion", Money.format(1_000_000_000_000_00L));
        assertEquals("$90 Quadrillion", Money.format(9e18), "the old cap (9 x 10^18 cents)");
        assertEquals("$1 Quintillion", Money.format(1e20), "past the old cap");
        assertEquals("$1 Sextillion", Money.format(1e23));
        assertEquals("$1 Duotrigintillion", Money.format(Money.parse("1DTg")));
    }

    @Test
    void everyPowerOfAThousandHasAName() {
        Set<String> seen = new HashSet<>();
        for (int tier = 1; tier <= Money.TOP_TIER; tier++) {
            String suffix = Money.suffix(tier);
            String name = Money.name(tier);
            assertTrue(seen.add(suffix.toLowerCase()), "short names are unique (any case): " + suffix);
            assertTrue(seen.add(name.toLowerCase()), "names are unique, and never a short name: " + name);
            // Written with either name or in scientific notation, a round amount shows back exactly.
            assertEquals("$1 " + name, Money.format(Money.parse("1" + suffix)), "tier " + tier);
            assertEquals("$1 " + name, Money.format(Money.parse("1" + name.toLowerCase())), "tier " + tier);
            assertEquals("$1 " + name, Money.format(Money.parse("1 " + name)), "tier " + tier);
            assertEquals("$1 " + name, Money.format(Money.parse("1e" + 3 * tier)), "tier " + tier);
            if (tier < Money.TOP_TIER) {
                assertEquals("$1.5 " + name, Money.format(Money.parse("1.5" + suffix)), "tier " + tier);
                assertEquals("$2.5 " + name, Money.format(Money.parse("2.5e" + 3 * tier)), "tier " + tier);
                assertEquals("$999.9 " + name, Money.format(Money.parse("999.99" + suffix)), "rounded down, tier " + tier);
            }
        }
        assertEquals("Dc", Money.suffix(11));
        assertEquals("UDc", Money.suffix(12));
        assertEquals("Vg", Money.suffix(21));
        assertEquals("DTg", Money.suffix(33));
        assertEquals("Ce", Money.suffix(101));
        assertEquals("UCe", Money.suffix(102));
        assertEquals("Thousand", Money.name(1));
        assertEquals("Decillion", Money.name(11));
        assertEquals("Duotrigintillion", Money.name(33));
        assertEquals("Quattuorquinquagintillion", Money.name(55), "the longest");
        assertEquals("Uncentillion", Money.name(102));
    }

    @Test
    void theLargestBalanceStillFormats() {
        assertEquals("$1.7 Uncentillion", Money.format(Cents.MAX));
        assertEquals("1.7 UNCENTILLION DOLLARS", Money.words(Cents.MAX));
        assertEquals("$1.7 Uncentillion", Money.format(Double.POSITIVE_INFINITY), "shown as the cap");
        assertEquals("$0", Money.format(Double.NaN));
    }

    @Test
    void words() {
        assertEquals("1 TRILLION DOLLARS", Money.words(1_000_000_000_000_00L));
        assertEquals("2.5 BILLION DOLLARS", Money.words(2_590_000_000_00L));
        assertEquals("500 DOLLARS", Money.words(500_00));
        assertEquals("1 DOLLAR", Money.words(1_00));
        assertEquals("1 SEXTILLION DOLLARS", Money.words(Money.parse("1Sx")));
        assertEquals("3 DECILLION DOLLARS", Money.words(Money.parse("3e33")));
        assertEquals("1 SEPTENDECILLION DOLLARS", Money.words(Money.parse("1e54")));
        assertEquals("1 VIGINTILLION DOLLARS", Money.words(Money.parse("1e63")));
        assertEquals("1 CENTILLION DOLLARS", Money.words(Money.parse("1e303")));
    }

    @Test
    void negative() {
        assertEquals("-$1.5 Thousand", Money.format(-1500_00));
    }

    @Test
    void parsesAmounts() {
        assertEquals(1500_00, Money.parse("1500"));
        assertEquals(1250, Money.parse("12.50"));
        assertEquals(1250, Money.parse("$12.5"));
        assertEquals(1_000_000_00, Money.parse("1_000_000"));
        assertEquals(1_000_000_00, Money.parse("1,000,000"));
        assertEquals(1500_00, Money.parse("1.5k"), "any case");
        assertEquals(2.5e12 * 100, Money.parse("2.5T"));
        assertEquals(1e42, Money.parse("1e40"));
        assertEquals(1e42, Money.parse("1.0E40"), "how Java writes a double");
        assertEquals(1e42, Money.parse("1.0E+40"));
        assertEquals(13, Money.parse("0.125"), "rounded to whole cents");
        assertEquals(0, Money.parse("0"));
    }

    @Test
    void rejectsWhatIsNotAnAmount() {
        assertTrue(Double.isNaN(Money.parse("")));
        assertTrue(Double.isNaN(Money.parse("abc")));
        assertTrue(Double.isNaN(Money.parse("-5")), "no negatives");
        assertTrue(Double.isNaN(Money.parse("5X")), "unknown name");
        assertTrue(Double.isNaN(Money.parse("NaN")));
        assertTrue(Double.isNaN(Money.parse("Infinity")));
        assertEquals(Double.POSITIVE_INFINITY, Money.parse("1e400"), "more than any balance");
        assertEquals(Double.POSITIVE_INFINITY, Money.parse("1000UCe"));
        assertEquals(Double.POSITIVE_INFINITY, Money.parse("1e999999999"));
    }
}
