package me.sevj6.funCore.games.tntrun;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Small and large TNT Run arenas, and which one a match gets: small for a match of {@code small-arena-players} (2) or
 * fewer, large otherwise.
 */
final class ArenaSizes {

    static final String AUTO = "auto", SMALL = "small", LARGE = "large";
    /**
     * With {@code arena-size: auto}, the widest region (blocks, both across and along) that counts as small. Whether
     * the region is drawn around just the floor or the whole build, 25x25 arenas come out at 25-43 blocks and 50x50
     * ones at 50-68, so this splits them either way.
     */
    static final int AUTO_SMALL_MAX_WIDTH = 46;

    private ArenaSizes() {
    }

    /** Checks the {@code arena-size} option. */
    static String check(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (List.of(AUTO, SMALL, LARGE).contains(lower)) return lower;
        throw new IllegalArgumentException("arena-size is auto, small or large.");
    }

    /** Whether an arena is small: as its {@code arena-size} says, or by its region's width when that's auto. */
    static boolean isSmall(String setting, int sizeX, int sizeZ) {
        return switch (setting.toLowerCase(Locale.ROOT)) {
            case SMALL -> true;
            case LARGE -> false;
            default -> Math.max(sizeX, sizeZ) <= AUTO_SMALL_MAX_WIDTH;
        };
    }

    /**
     * The arena a match gets: a free one of the size it wants. If the game has no ready arena of that size at all,
     * any free one; if it has some but they're all in use, none (the match waits for one).
     *
     * @param ready     every ready arena of the game, in use or not
     * @param free      the ready ones nobody is using
     * @param small     which arenas are small
     * @param wantSmall whether the match wants a small one
     * @param chooser   picks one of the candidates (e.g. at random)
     */
    static <T> @Nullable T pick(List<T> ready, List<T> free, Predicate<T> small, boolean wantSmall,
                                Function<List<T>, @Nullable T> chooser) {
        boolean anyOfSize = ready.stream().anyMatch(arena -> small.test(arena) == wantSmall);
        List<T> candidates = anyOfSize ? free.stream().filter(arena -> small.test(arena) == wantSmall).toList() : free;
        return candidates.isEmpty() ? null : chooser.apply(candidates);
    }
}
