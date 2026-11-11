package me.sevj6.funCore.tycoon.core.template;

import java.util.List;

/**
 * An upgrade bought again and again on one pad, a level at a time (the pickaxe, belt speed). Level 0 is what a run
 * starts with; buying level {@code n} costs {@code steps[n - 1].price} and gives {@code steps[n - 1].amount}.
 *
 * @param name  what the pad calls it ("Pickaxe" shows as "Pickaxe II")
 * @param pad   where its pad goes
 */
public record Ladder(String name, Offset pad, List<Step> steps) {

    /**
     * One level.
     *
     * @param price  cents
     * @param amount what the level gives (cents a click, a speed multiplier, ...)
     */
    public record Step(long price, double amount) {
    }

    public Ladder {
        steps = List.copyOf(steps);
    }

    public int maxLevel() {
        return steps.size();
    }

    /** The step that buys level {@code level + 1} (what's on the pad at {@code level}), or {@code null} when maxed. */
    public Step next(int level) {
        return level >= 0 && level < steps.size() ? steps.get(level) : null;
    }

    /** What level {@code level} gives, or {@code start} at level 0. */
    public double amount(int level, double start) {
        if (level <= 0) return start;
        return steps.get(Math.min(level, steps.size()) - 1).amount();
    }

    /** "II", "IX", ...: level numbers as pads show them. */
    public static String roman(int number) {
        String[] ones = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX"};
        String[] tens = {"", "X", "XX", "XXX", "XL", "L", "LX", "LXX", "LXXX", "XC"};
        return number > 0 && number < 100 ? tens[number / 10] + ones[number % 10] : String.valueOf(number);
    }
}
