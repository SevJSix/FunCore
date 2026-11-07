package me.sevj6.funCore.game;

import me.sevj6.funCore.util.Utils;
import net.kyori.adventure.text.Component;

/**
 * How players are grouped in a match: {@link #SOLO} is every player for themselves, the others put players in teams
 * of {@link #teamSize()}. A {@link GameType} lists the modes it supports, and players pick one with
 * {@code /game join <game> [mode]}.
 *
 * <p>This is separate from how many people a game needs: a single-player game is a {@code GameType} with at most one
 * player (and the {@link #SOLO} mode).
 *
 * @param id       used in commands and config, e.g. {@code "duos"}
 * @param teamSize players per team; 1 means every player for themselves
 */
public record TeamMode(String id, Component displayName, int teamSize) {

    public static final TeamMode SOLO = new TeamMode("solo", Utils.mm("Solo"), 1);
    public static final TeamMode DUOS = new TeamMode("duos", Utils.mm("Duos"), 2);
    public static final TeamMode TRIOS = new TeamMode("trios", Utils.mm("Trios"), 3);
    public static final TeamMode SQUADS = new TeamMode("squads", Utils.mm("Squads"), 4);

    public TeamMode {
        if (!id.matches("[a-z0-9_-]+")) throw new IllegalArgumentException("Invalid team mode id: " + id);
        if (teamSize < 1) throw new IllegalArgumentException("teamSize must be at least 1");
    }

    /** A custom mode, e.g. {@code TeamMode.of("fives", "Teams of 5", 5)}. */
    public static TeamMode of(String id, String displayName, int teamSize) {
        return new TeamMode(id, Utils.mm(displayName), teamSize);
    }

    /** Every player for themselves. */
    public boolean isSolo() {
        return teamSize == 1;
    }
}
