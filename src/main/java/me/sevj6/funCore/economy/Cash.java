package me.sevj6.funCore.economy;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Cash amounts. Internally every amount is a {@code long} number of <b>cents</b> ({@code 1250} is $12.50), so there
 * are no rounding errors and no fractions of a cent.
 */
public final class Cash {

    /** The most any single balance or amount can be: $1 trillion. */
    public static final long MAX = 1_000_000_000_000_00L;

    /**
     * What players may type: an optional {@code $}, digits (optionally with properly placed thousands commas), and up
     * to two decimal places. For example {@code 5}, {@code 12.5}, {@code $1,000.99}.
     */
    private static final Pattern INPUT = Pattern.compile("\\$?(\\d{1,3}(,\\d{3})+|\\d+)(\\.\\d{1,2})?");

    private Cash() {
    }

    /** Formats cents as dollars, hiding the cents on whole amounts: {@code 500} is "$5", {@code 1250} is "$12.50". */
    public static String format(long cents) {
        long abs = Math.abs(cents);
        long dollars = abs / 100;
        long remainder = abs % 100;
        String text = "$" + String.format(Locale.US, "%,d", dollars)
                + (remainder == 0 ? "" : String.format(Locale.US, ".%02d", remainder));
        return cents < 0 ? "-" + text : text;
    }

    /**
     * Parses an amount typed by a player into cents.
     *
     * @throws IllegalArgumentException with a message that can be shown to the player, if the text isn't a valid
     *                                  amount or is over {@link #MAX}
     */
    public static long parse(String input) {
        String text = input.trim();
        if (!INPUT.matcher(text).matches()) {
            throw new IllegalArgumentException("'" + input + "' isn't a valid amount. Use a number like 5, 12.50 or 1,000.");
        }
        BigDecimal dollars = new BigDecimal(text.replace("$", "").replace(",", ""));
        BigDecimal cents = dollars.movePointRight(2);
        if (cents.compareTo(BigDecimal.valueOf(MAX)) > 0) {
            throw new IllegalArgumentException("That's too much. The most allowed is " + format(MAX) + ".");
        }
        return cents.longValueExact();
    }

    /** Converts whole dollars to cents, e.g. for prices in code: {@code Cash.dollars(5)}. */
    public static long dollars(long dollars) {
        return Math.multiplyExact(dollars, 100L);
    }
}
