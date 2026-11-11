package me.sevj6.funCore.tycoon.core;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Short money text for tycoon signs and messages: {@code $12}, {@code $12.50}, {@code $1.2K}, {@code $3.4M}, ... */
public final class Money {

    private static final String[] SUFFIXES = {"K", "M", "B", "T", "Qa", "Qi"};
    private static final String[] WORDS = {"THOUSAND", "MILLION", "BILLION", "TRILLION", "QUADRILLION", "QUINTILLION"};

    private Money() {
    }

    /**
     * Cents as short dollars. Under $1,000 it's exact (cents shown only when there are any); from $1,000 it's one
     * decimal and a suffix, rounded down so a sign never shows more than the real amount ($1,999 is $1.9K), with a
     * trailing ".0" dropped ($1K).
     */
    public static String format(long cents) {
        if (cents < 0) return "-" + format(-Math.max(cents, -Long.MAX_VALUE));
        long dollars = cents / 100;
        if (dollars < 1000) {
            long rest = cents % 100;
            return "$" + dollars + (rest == 0 ? "" : String.format(".%02d", rest));
        }
        BigDecimal value = BigDecimal.valueOf(dollars);
        int tier = -1;
        while (value.compareTo(BigDecimal.valueOf(1000)) >= 0 && tier + 1 < SUFFIXES.length) {
            value = value.movePointLeft(3);
            tier++;
        }
        BigDecimal shown = value.setScale(1, RoundingMode.DOWN).stripTrailingZeros();
        return "$" + shown.toPlainString() + SUFFIXES[tier];
    }

    /**
     * Cents in big words, for a sign: {@code 1 TRILLION DOLLARS}, {@code 2.5 BILLION DOLLARS}. Under a thousand it's
     * the plain amount ({@code 500 DOLLARS}); like {@link #format}, it's rounded down to one decimal.
     */
    public static String words(long cents) {
        long dollars = Math.max(0, cents) / 100;
        BigDecimal value = BigDecimal.valueOf(dollars);
        int tier = -1;
        while (value.compareTo(BigDecimal.valueOf(1000)) >= 0 && tier + 1 < WORDS.length) {
            value = value.movePointLeft(3);
            tier++;
        }
        String shown = value.setScale(tier < 0 ? 0 : 1, RoundingMode.DOWN).stripTrailingZeros().toPlainString();
        return shown + (tier < 0 ? "" : " " + WORDS[tier]) + (dollars == 1 ? " DOLLAR" : " DOLLARS");
    }
}
