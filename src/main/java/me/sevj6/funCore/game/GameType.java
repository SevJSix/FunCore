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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
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

    /** The name of a game's main kit (the one players arrange with {@code /game layout}). */
    public static final String DEFAULT_KIT = "default";

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
    private final boolean defaultCollision;
    private final boolean defaultDynamic, defaultPvp;
    private final int maxTeams;
    private final Map<String, Supplier<Kit>> kits;
    private final UnaryOperator<ItemStack> kitPreview;
    private final Map<String, GameOption> options;
    private final List<String> requiredPoints, requiredAreas, pointNames, areaNames;
    private final @Nullable Consumer<Arena> arenaDefaults;
    private final @Nullable Function<Arena, List<String>> arenaCheck;
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
        this.defaultDynamic = builder.defaultDynamic;
        this.defaultPvp = builder.defaultPvp;
        this.defaultCollision = builder.defaultCollision;
        this.maxTeams = builder.maxTeams;
        this.kits = Collections.unmodifiableMap(new LinkedHashMap<>(builder.kits));
        this.kitPreview = builder.kitPreview;
        this.options = Collections.unmodifiableMap(new LinkedHashMap<>(builder.options));
        this.requiredPoints = List.copyOf(builder.requiredPoints);
        this.requiredAreas = List.copyOf(builder.requiredAreas);
        this.pointNames = List.copyOf(new LinkedHashSet<>(concat(builder.requiredPoints, builder.suggestedPoints)));
        this.areaNames = List.copyOf(new LinkedHashSet<>(concat(builder.requiredAreas, builder.suggestedAreas)));
        this.arenaDefaults = builder.arenaDefaults;
        this.arenaCheck = builder.arenaCheck;
        this.factory = builder.factory;
    }

    public static Builder builder(String id, Component displayName, Factory factory) {
        return new Builder(id, displayName, factory);
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
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

    /** Whether {@link GameSettings#isDynamic()} starts out on (admins can still turn it off). */
    public boolean isDynamicByDefault() {
        return defaultDynamic;
    }

    /** Whether PvP starts out on for FFA and teams (see {@link GameSettings#isPvp}); admins can change it. */
    public boolean isPvpByDefault() {
        return defaultPvp;
    }

    /** Whether players push each other around (see {@link GameSettings#isCollision}); admins can change it. */
    public boolean isCollisionByDefault() {
        return defaultCollision;
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

    /**
     * Whether the game has a main kit ({@link #DEFAULT_KIT}): edited with {@code /game kit}, arranged by players with
     * {@code /game layout}.
     */
    public boolean hasKit() {
        return kits.containsKey(DEFAULT_KIT);
    }

    /** Whether the game has any kits, main or named (e.g. one per level). Admins edit each with {@code /game kit}. */
    public boolean hasKits() {
        return !kits.isEmpty();
    }

    /** Every kit's name, the main one ({@link #DEFAULT_KIT}) first if there is one. */
    public List<String> getKitNames() {
        return List.copyOf(kits.keySet());
    }

    /** The main kit built into the code; {@code null} if the game has none. Admins' edits are in {@link GameSettings#getKit()}. */
    public @Nullable Kit getDefaultKit() {
        return getDefaultKit(DEFAULT_KIT);
    }

    /** A kit as built into the code; {@code null} if the game has no kit by that name. */
    public @Nullable Kit getDefaultKit(String name) {
        Supplier<Kit> kit = kits.get(name);
        return kit == null ? null : kit.get();
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

    /** Point names the game uses, required or optional (for command suggestions). */
    public List<String> getPointNames() {
        return pointNames;
    }

    /** Area names the game uses, required or optional (for command suggestions). */
    public List<String> getAreaNames() {
        return areaNames;
    }

    /** Whether the game has its own defaults for arena settings (see {@link #applyArenaDefaults}). */
    public boolean hasArenaDefaults() {
        return arenaDefaults != null;
    }

    /**
     * Sets the game's preferred arena settings (e.g. placing on, breaking off). Done once, when an arena is given this
     * game with {@code /arena setgame}; admins can change them afterwards.
     */
    public void applyArenaDefaults(Arena arena) {
        if (arenaDefaults != null) arenaDefaults.accept(arena);
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
        if (arenaCheck != null && problems.isEmpty()) problems.addAll(arenaCheck.apply(arena));
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
        private boolean defaultDynamic, defaultPvp = true, defaultCollision = true;
        private int maxTeams;
        private final Map<String, Supplier<Kit>> kits = new LinkedHashMap<>();
        private UnaryOperator<ItemStack> kitPreview = UnaryOperator.identity();
        private final Map<String, GameOption> options = new LinkedHashMap<>();
        private final List<String> requiredPoints = new ArrayList<>(), requiredAreas = new ArrayList<>();
        private final List<String> suggestedPoints = new ArrayList<>(), suggestedAreas = new ArrayList<>();
        private @Nullable Consumer<Arena> arenaDefaults;
        private @Nullable Function<Arena, List<String>> arenaCheck;

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

        /** Like {@link #supportsDynamic()}, and dynamic matches start out switched on. */
        public Builder dynamicByDefault() {
            this.defaultDynamic = true;
            return supportsDynamic();
        }

        /** Whether PvP starts out on (it does unless this says otherwise). Admins can change it with {@code /game config}. */
        public Builder pvpByDefault(boolean pvp) {
            this.defaultPvp = pvp;
            return this;
        }

        /**
         * Whether players bump into and push each other (they do unless this says otherwise). Admins can change it with
         * {@code /game config}.
         */
        public Builder collisionByDefault(boolean collision) {
            this.defaultCollision = collision;
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
            return kit(DEFAULT_KIT, defaultKit);
        }

        /**
         * A named kit, for games that hand out different items at different times (e.g. one per level). Admins edit it
         * with {@code /game kit <game> <name>}; it may be empty. Give it with {@link Minigame#giveKit(org.bukkit.entity.Player, String,
         * UnaryOperator)}.
         */
        public Builder kit(String name, Supplier<Kit> defaultKit) {
            if (!name.matches("[a-z0-9_-]+")) throw new IllegalArgumentException("Invalid kit name: " + name);
            if (kits.putIfAbsent(name, Objects.requireNonNull(defaultKit)) != null) throw new IllegalArgumentException("duplicate kit " + name);
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

        /**
         * A text setting of this game.
         *
         * @param check checks a value (throwing {@link IllegalArgumentException} with a message for the player) and
         *              returns it tidied up; it's applied to the default too
         */
        public Builder option(String key, String defaultValue, UnaryOperator<String> check, String description) {
            return add(new GameOption(key, check.apply(defaultValue), 0, 0, description, check));
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

        /** Optional points the game uses: suggested by {@code /arena setpoint}, but not required. */
        public Builder suggestsPoints(String... names) {
            suggestedPoints.addAll(List.of(names));
            return this;
        }

        /** Optional areas the game uses: suggested by {@code /arena area}, but not required. */
        public Builder suggestsAreas(String... names) {
            suggestedAreas.addAll(List.of(names));
            return this;
        }

        /** The game's preferred arena settings, set when an arena is given this game (see {@link GameType#applyArenaDefaults}). */
        public Builder arenaDefaults(Consumer<Arena> defaults) {
            this.arenaDefaults = Objects.requireNonNull(defaults);
            return this;
        }

        /**
         * Extra checks of an arena, for what the required points and areas can't say (e.g. "every tower is the same
         * size"). Returns the problems; empty when it's ready. Runs once the required points and areas are there.
         */
        public Builder arenaCheck(Function<Arena, List<String>> check) {
            this.arenaCheck = Objects.requireNonNull(check);
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
