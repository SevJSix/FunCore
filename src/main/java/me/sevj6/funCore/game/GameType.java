package me.sevj6.funCore.game;

import me.sevj6.funCore.FunCore;
import me.sevj6.funCore.arena.Arena;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A kind of minigame, registered once with {@link GameManager#register}. It runs one match ({@link Minigame}) in each
 * of its arenas, so several can be played at once; when every arena is busy, nobody else can join.
 *
 * <p>The type is what the game's <b>code</b> can do: which {@link PlayMode modes} it supports, and whether it copes
 * with dynamic matches and late joiners. What's actually <b>switched on</b> lives in its {@link GameSettings}, which
 * admins change with {@code /game config}; the values here are just the defaults for those settings.
 *
 * <pre>{@code
 * public static final GameType TYPE = GameType.builder("parkour", Utils.mm("<green>Parkour"), ParkourGame::new)
 *         .modes(PlayMode.SINGLEPLAYER, PlayMode.FFA)
 *         .players(2, 8)
 *         .supportsDynamic()
 *         .supportsLateJoin()
 *         .requiresArena()
 *         .build();
 * }</pre>
 */
public final class GameType {

    /** Makes one match of a game. Usually the constructor, e.g. {@code SpleefGame::new}. */
    @FunctionalInterface
    public interface Factory {
        Minigame create(FunCore plugin, GameType type, @Nullable Arena arena);
    }

    private final String id;
    private final Component displayName;
    private final Set<PlayMode> modes;
    private final int defaultMinPlayers, defaultMaxPlayers;
    private final boolean supportsDynamic, supportsLateJoin, requiresArena;
    private final Factory factory;

    private GameType(Builder builder) {
        this.id = builder.id;
        this.displayName = builder.displayName;
        this.modes = Collections.unmodifiableSet(EnumSet.copyOf(builder.modes));
        this.defaultMinPlayers = builder.minPlayers;
        this.defaultMaxPlayers = builder.maxPlayers;
        this.supportsDynamic = builder.supportsDynamic;
        this.supportsLateJoin = builder.supportsLateJoin;
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

    /** The modes the game's code supports, in {@link PlayMode} order. */
    public List<PlayMode> getSupportedModes() {
        return new ArrayList<>(modes);
    }

    public boolean supports(PlayMode mode) {
        return modes.contains(mode);
    }

    /** Default for {@link GameSettings#getMinPlayers()}. */
    public int getDefaultMinPlayers() {
        return defaultMinPlayers;
    }

    /** Default for {@link GameSettings#getMaxPlayers()}. */
    public int getDefaultMaxPlayers() {
        return defaultMaxPlayers;
    }

    /** Whether the code copes with a free-for-all match of one player, so {@link GameSettings#isDynamic()} can be turned on. */
    public boolean supportsDynamic() {
        return supportsDynamic;
    }

    /** Whether the code copes with players joining a running match, so {@link GameSettings#isLateJoin()} can be turned on. */
    public boolean supportsLateJoin() {
        return supportsLateJoin;
    }

    /**
     * Whether it can only be played in an arena. Without this, a game with no arenas runs a single match wherever
     * its players are.
     */
    public boolean requiresArena() {
        return requiresArena;
    }

    @Override
    public String toString() {
        return "GameType[" + id + "]";
    }

    public static final class Builder {

        private final String id;
        private final Component displayName;
        private final Factory factory;
        private Set<PlayMode> modes = EnumSet.of(PlayMode.FFA);
        private int minPlayers = 2, maxPlayers = 16;
        private boolean supportsDynamic, supportsLateJoin, requiresArena;

        private Builder(String id, Component displayName, Factory factory) {
            if (!id.matches("[a-z0-9_-]+")) throw new IllegalArgumentException("Invalid minigame id: " + id);
            this.id = id;
            this.displayName = Objects.requireNonNull(displayName);
            this.factory = Objects.requireNonNull(factory);
        }

        /** The modes the game's code supports. Defaults to {@link PlayMode#FFA} only. */
        public Builder modes(PlayMode... modes) {
            if (modes.length == 0) throw new IllegalArgumentException("a game needs at least one mode");
            this.modes = EnumSet.copyOf(List.of(modes));
            return this;
        }

        /**
         * Default player limits for the multiplayer modes (admins can change them). The minimum is what a multiplayer
         * match needs before its countdown starts; team modes always wait for at least two teams. Defaults to 2-16.
         */
        public Builder players(int min, int max) {
            if (min < 2) throw new IllegalArgumentException("a multiplayer minimum must be at least 2 (use supportsDynamic for 1)");
            if (max < min) throw new IllegalArgumentException("max must be >= min");
            this.minPlayers = min;
            this.maxPlayers = max;
            return this;
        }

        /** The code handles a free-for-all match with just one player. See {@link GameSettings#isDynamic()}. */
        public Builder supportsDynamic() {
            this.supportsDynamic = true;
            return this;
        }

        /** The code handles players joining mid-match (see {@link Minigame#onLateJoin}). */
        public Builder supportsLateJoin() {
            this.supportsLateJoin = true;
            return this;
        }

        /** See {@link GameType#requiresArena()}. */
        public Builder requiresArena() {
            this.requiresArena = true;
            return this;
        }

        public GameType build() {
            if (supportsDynamic && !modes.contains(PlayMode.FFA)) {
                throw new IllegalArgumentException(id + ": dynamic matches are free-for-all, so the game must support FFA");
            }
            for (PlayMode mode : modes) {
                if (mode.isTeams() && maxPlayers < mode.teamSize() * 2) {
                    throw new IllegalArgumentException(id + ": " + mode.id() + " needs room for at least 2 teams (max players "
                            + maxPlayers + ")");
                }
            }
            return new GameType(this);
        }
    }
}
