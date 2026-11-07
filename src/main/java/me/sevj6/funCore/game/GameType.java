package me.sevj6.funCore.game;

import me.sevj6.funCore.FunCore;
import me.sevj6.funCore.arena.Arena;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * A kind of minigame, registered once with {@link GameManager#register}. It runs one match ({@link Minigame}) in each
 * of its arenas, so several can be played at once; when every arena is busy, nobody else can join.
 *
 * <pre>{@code
 * public static final GameType TYPE = GameType.builder("spleef", Utils.mm("<aqua>Spleef"), SpleefGame::new)
 *         .players(2, 12)
 *         .modes(TeamMode.SOLO, TeamMode.DUOS)
 *         .requiresArena()
 *         .build();
 * }</pre>
 *
 * <ul>
 *     <li><b>Single-player</b>: {@code .players(1, 1)}. Each player gets an arena to themselves.</li>
 *     <li><b>Multiplayer</b>: e.g. {@code .players(2, 16)}.</li>
 *     <li><b>Both</b>: e.g. {@code .players(1, 8)}: it starts as soon as one player joins, and others can join during
 *     the countdown.</li>
 * </ul>
 */
public final class GameType {

    /** Makes one match of a game. Usually the constructor, e.g. {@code SpleefGame::new}. */
    @FunctionalInterface
    public interface Factory {
        Minigame create(FunCore plugin, GameType type, @Nullable Arena arena);
    }

    private final String id;
    private final Component displayName;
    private final int minPlayers, maxPlayers;
    private final List<TeamMode> modes;
    private final boolean requiresArena;
    private final Factory factory;

    private GameType(Builder builder) {
        this.id = builder.id;
        this.displayName = builder.displayName;
        this.minPlayers = builder.minPlayers;
        this.maxPlayers = builder.maxPlayers;
        this.modes = List.copyOf(builder.modes);
        this.requiresArena = builder.requiresArena;
        this.factory = builder.factory;
    }

    public static Builder builder(String id, Component displayName, Factory factory) {
        return new Builder(id, displayName, factory);
    }

    Minigame create(FunCore plugin, @Nullable Arena arena) {
        Minigame match = factory.create(plugin, this, arena);
        if (match.getType() != this) throw new IllegalStateException(id + "'s factory made a match of another game type");
        return match;
    }

    /** Unique, lowercase identifier used in commands and config, e.g. {@code "spleef"}. */
    public String getId() {
        return id;
    }

    public Component getDisplayName() {
        return displayName;
    }

    /** Players needed before the countdown starts. */
    public int getMinPlayers() {
        return minPlayers;
    }

    /** Most players in one match. */
    public int getMaxPlayers() {
        return maxPlayers;
    }

    public boolean isSinglePlayer() {
        return maxPlayers == 1;
    }

    /** The modes players can pick, in order; the first is the default. */
    public List<TeamMode> getModes() {
        return modes;
    }

    public TeamMode getDefaultMode() {
        return modes.getFirst();
    }

    public boolean supports(TeamMode mode) {
        return modes.contains(mode);
    }

    public @Nullable TeamMode getMode(String id) {
        for (TeamMode mode : modes) {
            if (mode.id().equals(id)) return mode;
        }
        return null;
    }

    /**
     * Whether it can only be played in an arena. Without this, a game with no arenas runs a single match wherever
     * its players are.
     */
    public boolean requiresArena() {
        return requiresArena;
    }

    /** "single-player", "2-16 players", ... */
    public String describePlayers() {
        if (maxPlayers == 1) return "single-player";
        if (minPlayers == maxPlayers) return maxPlayers + " players";
        return minPlayers + "-" + maxPlayers + " players";
    }

    @Override
    public String toString() {
        return "GameType[" + id + "]";
    }

    public static final class Builder {

        private final String id;
        private final Component displayName;
        private final Factory factory;
        private int minPlayers = 2, maxPlayers = 16;
        private List<TeamMode> modes = List.of(TeamMode.SOLO);
        private boolean requiresArena;

        private Builder(String id, Component displayName, Factory factory) {
            if (!id.matches("[a-z0-9_-]+")) throw new IllegalArgumentException("Invalid minigame id: " + id);
            this.id = id;
            this.displayName = Objects.requireNonNull(displayName);
            this.factory = Objects.requireNonNull(factory);
        }

        /** Players needed to start, and the most in one match. Defaults to 2-16. */
        public Builder players(int min, int max) {
            if (min < 1) throw new IllegalArgumentException("min must be at least 1");
            if (max < min) throw new IllegalArgumentException("max must be >= min");
            this.minPlayers = min;
            this.maxPlayers = max;
            return this;
        }

        /** The team modes players can pick; the first is the default. Defaults to {@link TeamMode#SOLO} only. */
        public Builder modes(TeamMode... modes) {
            if (modes.length == 0) throw new IllegalArgumentException("a game needs at least one mode");
            if (List.of(modes).stream().map(TeamMode::id).distinct().count() != modes.length) {
                throw new IllegalArgumentException("mode ids must be unique");
            }
            this.modes = List.of(modes);
            return this;
        }

        /** See {@link GameType#requiresArena()}. */
        public Builder requiresArena() {
            this.requiresArena = true;
            return this;
        }

        public GameType build() {
            for (TeamMode mode : modes) {
                if (!mode.isSolo() && mode.teamSize() > maxPlayers) {
                    throw new IllegalArgumentException(id + ": mode '" + mode.id() + "' has teams bigger than the player limit");
                }
            }
            return new GameType(this);
        }
    }
}
