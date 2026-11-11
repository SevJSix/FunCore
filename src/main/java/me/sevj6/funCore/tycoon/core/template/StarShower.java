package me.sevj6.funCore.tycoon.core.template;

import java.util.ArrayList;
import java.util.List;

/**
 * Nether stars that fall from the sky onto the plot's open floor (within {@code areas}) for the owner to pick up, each
 * worth money. Bought a level at a time on its pad: each level makes stars worth more and fall more often. Stars
 * never despawn, but no more than {@code cap} lie on a plot at once.
 *
 * @param areas      where stars may land: the blocks just above the floor (spots with anything solid are skipped)
 * @param dropHeight how far above the floor a star appears
 */
public record StarShower(String name, Offset pad, int cap, int dropHeight, List<Box> areas, List<Level> levels) {

    /**
     * One level.
     *
     * @param price  cents
     * @param value  cents a star is worth
     * @param every  ticks between stars
     */
    public record Level(long price, double value, int every) {
    }

    public StarShower {
        areas = List.copyOf(areas);
        levels = List.copyOf(levels);
    }

    /** The pad's ladder: each level's price, and a star's worth at it. */
    public Ladder ladder() {
        List<Ladder.Step> steps = new ArrayList<>();
        for (Level level : levels) steps.add(new Ladder.Step(level.price(), level.value()));
        return new Ladder(name, pad, steps);
    }

    /** The level a player at {@code level} has, or {@code null} at level 0 (no stars yet). */
    public Level at(int level) {
        return level <= 0 ? null : levels.get(Math.min(level, levels.size()) - 1);
    }
}
