package me.sevj6.funCore.game;

import me.sevj6.funCore.FunCore;
import me.sevj6.funCore.arena.Arena;
import me.sevj6.funCore.arena.Region;
import me.sevj6.funCore.kit.Kit;
import org.bukkit.inventory.ItemStack;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

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
    private final int maxTeams;
    private final @Nullable Supplier<Kit> defaultKit;
    private final UnaryOperator<ItemStack> kitPreview;
    private final Map<String, GameOption> options;
    private final List<String> requiredPoints, requiredAreas;
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
        this.maxTeams = builder.maxTeams;
        this.defaultKit = builder.defaultKit;
        this.kitPreview = builder.kitPreview;
        this.options = Collections.unmodifiableMap(new LinkedHashMap<>(builder.options));
        this.requiredPoints = List.copyOf(builder.requiredPoints);
        this.requiredAreas = List.copyOf(builder.requiredAreas);
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

    /** Most teams in a match (0 for no limit). Also caps players: {@code maxTeams x team size}, so 2 means 1v1 in FFA. */
    public int getMaxTeams() {
        return maxTeams;
    }

    /** Whether the game hands out a kit (edited with {@code /game kit}, arranged by players with {@code /game layout}). */
    public boolean hasKit() {
        return defaultKit != null;
    }

    /** The kit built into the code; {@code null} if the game has none. Admins' edits are in {@link GameSettings#getKit()}. */
    public @Nullable Kit getDefaultKit() {
        return defaultKit == null ? null : defaultKit.get();
    }

    /** How kit items look in the layout editor, e.g. coloured as if for the first team. */
    public UnaryOperator<ItemStack> getKitPreview() {
        return kitPreview;
    }

    /** The game's own settings, by key. */
    public Map<String, GameOption> getOptions() {
        return options;
    }

    /** Named arena points the game needs, e.g. {@code red-spawn}. */
    public List<String> getRequiredPoints() {
        return requiredPoints;
    }

    /** Named arena areas the game needs, e.g. {@code red-goal}. */
    public List<String> getRequiredAreas() {
        return requiredAreas;
    }

    /**
     * What an arena is missing to host this game: its region, required points and areas. Empty when it's ready. An
     * arena that isn't ready gets no matches.
     */
    public List<String> checkArena(Arena arena) {
        List<String> problems = new ArrayList<>();
        Region region = arena.getRegion();
        if (region == null) problems.add("no region (pos1/pos2)");
        for (String point : requiredPoints) {
            var at = arena.getPoints().get(point);
            if (at == null) problems.add("no point '" + point + "'");
            else if (region != null && !region.contains(at.x(), at.y(), at.z())) problems.add("point '" + point + "' is outside the region");
        }
        for (String area : requiredAreas) {
            if (arena.getArea(area) == null) problems.add("no area '" + area + "'");
        }
        return problems;
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
        private int maxTeams;
        private @Nullable Supplier<Kit> defaultKit;
        private UnaryOperator<ItemStack> kitPreview = UnaryOperator.identity();
        private final Map<String, GameOption> options = new LinkedHashMap<>();
        private final List<String> requiredPoints = new ArrayList<>(), requiredAreas = new ArrayList<>();

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

        /** See {@link GameType#getMaxTeams()}. */
        public Builder maxTeams(int maxTeams) {
            if (maxTeams < 2) throw new IllegalArgumentException("maxTeams must be at least 2");
            this.maxTeams = maxTeams;
            return this;
        }

        /** The game hands out this kit (see {@link Minigame#giveKit}); admins can change it, players can rearrange it. */
        public Builder kit(Supplier<Kit> defaultKit) {
            this.defaultKit = Objects.requireNonNull(defaultKit);
            return this;
        }

        /**
         * Like {@link #kit(Supplier)}, plus how items look in the layout editor (usually the same change the game makes
         * when handing the kit out, e.g. team colours).
         */
        public Builder kit(Supplier<Kit> defaultKit, UnaryOperator<ItemStack> preview) {
            this.kitPreview = Objects.requireNonNull(preview);
            return kit(defaultKit);
        }

        /** A whole-number setting of this game, e.g. {@code option("winning-score", 3, 1, 100, "Goals needed to win")}. */
        public Builder option(String key, int defaultValue, int min, int max, String description) {
            return add(new GameOption(key, defaultValue, min, max, description));
        }

        /** A decimal setting of this game. */
        public Builder option(String key, double defaultValue, double min, double max, String description) {
            return add(new GameOption(key, defaultValue, min, max, description));
        }

        /** A true/false setting of this game. */
        public Builder option(String key, boolean defaultValue, String description) {
            return add(new GameOption(key, defaultValue, 0, 0, description));
        }

        private Builder add(GameOption option) {
            if (options.putIfAbsent(option.key(), option) != null) throw new IllegalArgumentException("duplicate option " + option.key());
            return this;
        }

        /** Points every arena of this game must have (set with {@code /arena setpoint}). */
        public Builder requiresPoints(String... names) {
            requiredPoints.addAll(List.of(names));
            return this;
        }

        /** Areas every arena of this game must have (set with {@code /arena area}). */
        public Builder requiresAreas(String... names) {
            requiredAreas.addAll(List.of(names));
            return this;
        }

        public GameType build() {
            if (supportsDynamic && !modes.contains(PlayMode.FFA)) {
                throw new IllegalArgumentException(id + ": dynamic matches are free-for-all, so the game must support FFA");
            }
            if (!requiredPoints.isEmpty() || !requiredAreas.isEmpty()) requiresArena = true;
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
