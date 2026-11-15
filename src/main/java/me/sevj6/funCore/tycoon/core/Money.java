package me.sevj6.funCore.tycoon.core;

import me.sevj6.funCore.tycoon.core.sim.Cents;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Money text for the tycoon: amounts with their names spelled out for signs and messages ({@code $12},
 * {@code $12.50}, {@code $1.2 Thousand}, {@code $3.4 Million}, ... {@code $1 Duotrigintillion}), big words for a sign
 * ({@code 1 TRILLION DOLLARS}), and reading amounts typed by admins or written in a template ({@code 1500},
 * {@code 2.5T}, {@code 2.5trillion}, {@code 1e40}).
 *
 * <p>Every power of a thousand up to the largest balance has a name, and a short name for typing: thousand
 * ({@code K}), then the short-scale "-illions": million ({@code M}), billion ({@code B}), trillion ({@code T}),
 * quadrillion ({@code Qa}), quintillion ({@code Qi}), sextillion ({@code Sx}), septillion ({@code Sp}), octillion
 * ({@code Oc}), nonillion ({@code No}), then built from Latin parts: decillion ({@code Dc}), undecillion ({@code UDc}),
 * duodecillion ({@code DDc}), ..., vigintillion ({@code Vg}), trigintillion ({@code Tg}), duotrigintillion
 * ({@code DTg}), ..., centillion ({@code Ce}), uncentillion ({@code UCe}).
 */
public final class Money {

    private static final String[] SHORT_FIRST = {"M", "B", "T", "Qa", "Qi", "Sx", "Sp", "Oc", "No"};
    private static final String[] SHORT_UNITS = {"", "U", "D", "T", "Qa", "Qi", "Sx", "Sp", "Oc", "No"};
    private static final String[] SHORT_TENS = {"", "Dc", "Vg", "Tg", "Qag", "Qig", "Sxg", "Spg", "Ocg", "Nog"};
    private static final String[] WORD_FIRST = {"MILLION", "BILLION", "TRILLION", "QUADRILLION", "QUINTILLION",
            "SEXTILLION", "SEPTILLION", "OCTILLION", "NONILLION"};
    private static final String[] WORD_UNITS = {"", "UN", "DUO", "TRE", "QUATTUOR", "QUIN", "SEX", "SEPTEN", "OCTO", "NOVEM"};
    private static final String[] WORD_TENS = {"", "DEC", "VIGINT", "TRIGINT", "QUADRAGINT", "QUINQUAGINT", "SEXAGINT",
            "SEPTUAGINT", "OCTOGINT", "NONAGINT"};

    /** The highest power of a thousand with a name: uncentillion, 10^306, past the largest balance. */
    static final int TOP_TIER = 102;

    /** Short and full names (lower case) to their power of a thousand, for reading amounts. */
    private static final Map<String, Integer> TIERS = new HashMap<>();

    static {
        for (int tier = 1; tier <= TOP_TIER; tier++) {
            TIERS.put(suffix(tier).toLowerCase(Locale.ROOT), tier);
            TIERS.put(word(tier).toLowerCase(Locale.ROOT), tier);
        }
    }

    private static final Pattern AMOUNT = Pattern.compile("\\$?([0-9][0-9_,]*(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)\\s*([A-Za-z]*)");

    private Money() {
    }

    /**
     * Cents as dollars. Under $1,000 it's exact (cents shown only when there are any); from $1,000 it's one decimal and
     * the name of the power of a thousand, rounded down so a sign never shows more than the real amount ($1,999 is
     * {@code $1.9 Thousand}), with a trailing ".0" dropped ({@code $1 Thousand}).
     */
    public static String format(double cents) {
        if (Double.isNaN(cents)) return "$0";
        if (cents < 0) return "-" + format(-cents);
        BigDecimal dollars = dollars(cents);
        int tier = tier(dollars);
        if (tier == 0) {
            long whole = (long) Math.floor(cents);
            long rest = whole % 100;
            return "$" + whole / 100 + (rest == 0 ? "" : String.format(".%02d", rest));
        }
        return "$" + shown(dollars, tier, 1) + " " + name(tier);
    }

    /**
     * Cents in big words, for a sign: {@code 1 TRILLION DOLLARS}, {@code 2.5 BILLION DOLLARS}. Under a thousand it's
     * the plain amount ({@code 500 DOLLARS}); like {@link #format}, it's rounded down to one decimal.
     */
    public static String words(double cents) {
        BigDecimal dollars = dollars(cents > 0 ? cents : 0);
        int tier = tier(dollars);
        String shown = shown(dollars, tier, tier == 0 ? 0 : 1);
        return shown + (tier == 0 ? "" : " " + word(tier)) + (dollars.compareTo(BigDecimal.ONE) == 0 ? " DOLLAR" : " DOLLARS");
    }

    /**
     * Reads an amount of dollars as cents (rounded to whole cents): a plain number ({@code 1500}, {@code 12.50},
     * {@code 1_000_000}), scientific notation ({@code 2.5e40}) or with a name, short or full ({@code 1.5K}, {@code 3T},
     * {@code 2Dc}, {@code 2decillion}, {@code 2 Decillion}; any case), with or without a leading {@code $}.
     *
     * @return the cents, {@link Double#POSITIVE_INFINITY} if it's more than any balance can be, or {@link Double#NaN}
     *         if it isn't an amount (negative amounts aren't)
     */
    public static double parse(String text) {
        Matcher matcher = AMOUNT.matcher(text.strip());
        if (!matcher.matches()) return Double.NaN;
        int tier = 0;
        if (!matcher.group(2).isEmpty()) {
            Integer named = TIERS.get(matcher.group(2).toLowerCase(Locale.ROOT));
            if (named == null) return Double.NaN;
            tier = named;
        }
        BigDecimal dollars;
        try {
            dollars = new BigDecimal(matcher.group(1).replace("_", "").replace(",", ""));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
        if (dollars.precision() - dollars.scale() + 3 * tier > 320) return Double.POSITIVE_INFINITY;
        double cents = dollars.movePointRight(3 * tier + 2).setScale(0, RoundingMode.HALF_UP).doubleValue();
        return cents > Cents.MAX ? Double.POSITIVE_INFINITY : cents;
    }

    /** The short name of a power of a thousand: 1 is {@code K}, 2 {@code M}, 3 {@code B}, 11 {@code Dc}. */
    static String suffix(int tier) {
        if (tier == 1) return "K";
        int n = tier - 1;
        if (n <= SHORT_FIRST.length) return SHORT_FIRST[n - 1];
        return SHORT_UNITS[n % 10] + SHORT_TENS[n / 10 % 10] + (n >= 100 ? "Ce" : "");
    }

    /** A power of a thousand's name as shown in text: 1 is {@code Thousand}, 2 {@code Million}, 33 {@code Duotrigintillion}. */
    static String name(int tier) {
        String word = word(tier);
        return word.charAt(0) + word.substring(1).toLowerCase(Locale.ROOT);
    }

    /** The name of a power of a thousand: 1 is {@code THOUSAND}, 2 {@code MILLION}, 11 {@code DECILLION}. */
    static String word(int tier) {
        if (tier == 1) return "THOUSAND";
        int n = tier - 1;
        if (n <= WORD_FIRST.length) return WORD_FIRST[n - 1];
        return WORD_UNITS[n % 10] + WORD_TENS[n / 10 % 10] + (n >= 100 ? "CENT" : "") + "ILLION";
    }

    /**
     * Whole dollars, rounded down. Built from the double's shortest decimal form, so an amount written as $1 trillion is
     * exactly 1,000,000,000,000 here rather than a binary neighbour a hair below it.
     */
    private static BigDecimal dollars(double cents) {
        double clamped = Math.min(cents, Cents.MAX);
        return BigDecimal.valueOf(clamped).movePointLeft(2).setScale(0, RoundingMode.DOWN);
    }

    /** Which power of a thousand to show whole dollars in: 0 under $1,000, 1 for thousands, ... */
    private static int tier(BigDecimal dollars) {
        if (dollars.signum() == 0) return 0;
        int digits = dollars.precision() - dollars.scale();
        return Math.min((digits - 1) / 3, TOP_TIER);
    }

    private static String shown(BigDecimal dollars, int tier, int decimals) {
        return dollars.movePointLeft(3 * tier).setScale(decimals, RoundingMode.DOWN).stripTrailingZeros().toPlainString();
    }
}
