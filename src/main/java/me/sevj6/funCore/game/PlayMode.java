package me.sevj6.funCore.game;

import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;

/**
 * How a match is played. A {@link GameType} declares the modes its code supports; {@link GameSettings} decides which
 * of those are enabled. Players pick one with {@code /game join <game> [mode]}.
 *
 * <ul>
 *     <li>{@link #SINGLEPLAYER}: one player, in an arena of their own.</li>
 *     <li>{@link #FFA}: multiplayer, every player for themselves. With {@link GameSettings#isDynamic() dynamic} on,
 *     one player is enough to start, so a game can be played alone or with others (parkour, time trials).</li>
 *     <li>{@link #DUOS}, {@link #TRIOS}, {@link #SQUADS}: multiplayer in teams of 2, 3 or 4.</li>
 * </ul>
 */
public enum PlayMode {

    SINGLEPLAYER("singleplayer", "Singleplayer", 1),
    FFA("ffa", "Free-for-all", 1),
    DUOS("duos", "Duos", 2),
    TRIOS("trios", "Trios", 3),
    SQUADS("squads", "Squads", 4);

    private final String id;
    private final String displayName;
    private final int teamSize;

    PlayMode(String id, String displayName, int teamSize) {
        this.id = id;
        this.displayName = displayName;
        this.teamSize = teamSize;
    }

    /** Used in commands and config, e.g. {@code "duos"}. */
    public String id() {
        return id;
    }

    public Component displayName() {
        return Component.text(displayName);
    }

    /** Players per team: 1 outside the team modes. */
    public int teamSize() {
        return teamSize;
    }

    public boolean isSingleplayer() {
        return this == SINGLEPLAYER;
    }

    /** Duos, trios or squads. */
    public boolean isTeams() {
        return teamSize > 1;
    }

    public boolean isMultiplayer() {
        return this != SINGLEPLAYER;
    }

    public static @Nullable PlayMode byId(String id) {
        for (PlayMode mode : values()) {
            if (mode.id.equalsIgnoreCase(id)) return mode;
        }
        return null;
    }
}
