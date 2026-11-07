package me.sevj6.funCore.game;

import me.sevj6.funCore.kit.Kit;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A game's adjustable settings, saved in {@code plugins/FunCore/games/<id>.yml} and changed with {@code /game config}.
 * They can only switch on what the game's code supports ({@link GameType}). Changes apply right away, so matches
 * already waiting pick them up too.
 *
 * <pre>
 * modes: [singleplayer, ffa]   # enabled modes, a subset of what the game supports
 * dynamic: false               # FFA starts with 1 player; others can join during the countdown
 * late-join: false             # players can join a running multiplayer match
 * min-players: 2               # multiplayer: players needed to start the countdown
 * max-players: 16              # multiplayer: most players in one match
 * countdown-seconds: 10
 * pvp:
 *   ffa: true
 *   teams: true                # teammates still can't hurt each other
 * options:                     # the game's own settings (see GameOption)
 *   winning-score: 3
 * kit:                         # games with a kit; edited with /game kit
 *   - {id: iron_sword, slot: 0, item: ...}
 * </pre>
 */
public final class GameSettings {

    public static final int MAX_COUNTDOWN_SECONDS = 300;

    private final GameType type;
    private final Set<PlayMode> enabled = EnumSet.noneOf(PlayMode.class);
    private boolean dynamic, lateJoin;
    private boolean pvpFfa = true, pvpTeams = true;
    private int minPlayers, maxPlayers;
    private int countdownSeconds = 10;
    private final Map<String, Object> options = new LinkedHashMap<>();
    private @Nullable Kit kit;

    GameSettings(GameType type) {
        this.type = type;
        this.enabled.addAll(type.getSupportedModes());
        this.minPlayers = type.getDefaultMinPlayers();
        this.maxPlayers = type.getDefaultMaxPlayers();
        type.getOptions().forEach((key, option) -> options.put(key, option.defaultValue()));
        this.kit = type.getDefaultKit();
    }

    public GameType getType() {
        return type;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Modes
    // ------------------------------------------------------------------------------------------------------------

    /** Enabled modes, in {@link PlayMode} order. */
    public List<PlayMode> getEnabledModes() {
        return new ArrayList<>(enabled);
    }

    public boolean isEnabled(PlayMode mode) {
        return enabled.contains(mode);
    }

    /** The mode a match uses when the first player doesn't ask for one: the first enabled mode. */
    public PlayMode getDefaultMode() {
        return enabled.iterator().next();
    }

    /** @throws IllegalArgumentException if the game doesn't support the mode, or it's the last enabled one */
    public void setEnabled(PlayMode mode, boolean on) {
        if (!type.supports(mode)) throw new IllegalArgumentException(type.getId() + " can't be played in " + mode.id() + ".");
        if (!on && enabled.size() == 1 && enabled.contains(mode)) {
            throw new IllegalArgumentException("That's the only enabled mode; enable another one first.");
        }
        if (on && mode.isTeams() && maxPlayers < mode.teamSize() * 2) {
            throw new IllegalArgumentException(mode.id() + " needs room for at least 2 teams; raise max players to "
                    + mode.teamSize() * 2 + " first.");
        }
        if (on) enabled.add(mode);
        else enabled.remove(mode);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Dynamic and late join
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Whether a free-for-all match starts its countdown with just one player (others can still join during it), so the
     * game works both alone and with others.
     */
    public boolean isDynamic() {
        return dynamic;
    }

    public void setDynamic(boolean dynamic) {
        if (dynamic && !type.supportsDynamic()) throw new IllegalArgumentException(type.getId() + " doesn't support dynamic matches.");
        this.dynamic = dynamic;
    }

    /** Whether players can join a multiplayer match that's already running. */
    public boolean isLateJoin() {
        return lateJoin;
    }

    public void setLateJoin(boolean lateJoin) {
        if (lateJoin && !type.supportsLateJoin()) throw new IllegalArgumentException(type.getId() + " doesn't support joining mid-match.");
        this.lateJoin = lateJoin;
    }

    // ------------------------------------------------------------------------------------------------------------
    // PvP
    // ------------------------------------------------------------------------------------------------------------

    /** Whether players can hurt each other in this mode. Never in singleplayer; teammates never can in team modes. */
    public boolean isPvp(PlayMode mode) {
        if (mode.isSingleplayer()) return false;
        return mode.isTeams() ? pvpTeams : pvpFfa;
    }

    public boolean isPvpFfa() {
        return pvpFfa;
    }

    public void setPvpFfa(boolean pvp) {
        this.pvpFfa = pvp;
    }

    public boolean isPvpTeams() {
        return pvpTeams;
    }

    public void setPvpTeams(boolean pvp) {
        this.pvpTeams = pvp;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Players and countdown
    // ------------------------------------------------------------------------------------------------------------

    /** The configured multiplayer minimum. Use {@link #getMinPlayers(PlayMode)} for what a match actually needs. */
    public int getMinPlayers() {
        return minPlayers;
    }

    public void setMinPlayers(int minPlayers) {
        if (minPlayers < 2) throw new IllegalArgumentException("The multiplayer minimum is at least 2 (turn on dynamic to start with 1).");
        if (minPlayers > maxPlayers) throw new IllegalArgumentException("The minimum can't be above the maximum (" + maxPlayers + ").");
        this.minPlayers = minPlayers;
    }

    /** The configured multiplayer maximum. Use {@link #getMaxPlayers(PlayMode)} for a match's actual limit. */
    public int getMaxPlayers() {
        return maxPlayers;
    }

    public void setMaxPlayers(int maxPlayers) {
        if (maxPlayers < minPlayers) throw new IllegalArgumentException("The maximum can't be below the minimum (" + minPlayers + ").");
        for (PlayMode mode : enabled) {
            if (mode.isTeams() && maxPlayers < mode.teamSize() * 2) {
                throw new IllegalArgumentException(mode.id() + " needs room for at least 2 teams (" + mode.teamSize() * 2 + " players).");
            }
        }
        this.maxPlayers = maxPlayers;
    }

    /**
     * Players a match in this mode needs before its countdown starts: 1 in singleplayer and dynamic free-for-all, the
     * configured minimum otherwise, and in team modes at least enough for two teams. Never more than the mode's
     * maximum.
     */
    public int getMinPlayers(PlayMode mode) {
        int min;
        if (mode.isSingleplayer() || (mode == PlayMode.FFA && dynamic)) min = 1;
        else if (mode.isTeams()) min = Math.max(minPlayers, mode.teamSize() + 1);
        else min = minPlayers;
        return Math.min(min, getMaxPlayers(mode));
    }

    /**
     * Most players in a match of this mode: 1 in singleplayer, otherwise the configured maximum, capped by the game's
     * {@link GameType#getMaxTeams() team limit} (e.g. 2 teams: 2 in FFA, 4 in duos).
     */
    public int getMaxPlayers(PlayMode mode) {
        if (mode.isSingleplayer()) return 1;
        return type.getMaxTeams() > 0 ? Math.min(maxPlayers, type.getMaxTeams() * mode.teamSize()) : maxPlayers;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Game options and kit
    // ------------------------------------------------------------------------------------------------------------

    /** The game's own settings (see {@link GameType#getOptions()}), by key. */
    public Map<String, Object> getOptions() {
        return Map.copyOf(options);
    }

    public int getInt(String key) {
        return ((Number) option(key)).intValue();
    }

    public double getDouble(String key) {
        return ((Number) option(key)).doubleValue();
    }

    public boolean getBoolean(String key) {
        return (Boolean) option(key);
    }

    private Object option(String key) {
        Object value = options.get(key);
        if (value == null) throw new IllegalArgumentException(type.getId() + " has no option '" + key + "'");
        return value;
    }

    /** @throws IllegalArgumentException if there's no such option or the value is invalid */
    public void setOption(String key, Object value) {
        GameOption option = type.getOptions().get(key);
        if (option == null) throw new IllegalArgumentException(type.getId() + " has no option called '" + key + "'.");
        options.put(key, option.parse(value));
    }

    /** The kit handed out, as last saved by an admin (or the game's default); {@code null} if the game has none. */
    public @Nullable Kit getKit() {
        return kit;
    }

    public void setKit(Kit kit) {
        if (!type.hasKit()) throw new IllegalArgumentException(type.getId() + " doesn't use a kit.");
        this.kit = kit;
    }

    public int getCountdownSeconds() {
        return countdownSeconds;
    }

    public void setCountdownSeconds(int seconds) {
        if (seconds < 0 || seconds > MAX_COUNTDOWN_SECONDS) {
            throw new IllegalArgumentException("The countdown is 0-" + MAX_COUNTDOWN_SECONDS + " seconds.");
        }
        this.countdownSeconds = seconds;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Saving / loading
    // ------------------------------------------------------------------------------------------------------------

    void save(ConfigurationSection config) {
        config.set("modes", getEnabledModes().stream().map(PlayMode::id).toList());
        config.set("dynamic", dynamic);
        config.set("late-join", lateJoin);
        config.set("min-players", minPlayers);
        config.set("max-players", maxPlayers);
        config.set("countdown-seconds", countdownSeconds);
        config.set("pvp.ffa", pvpFfa);
        config.set("pvp.teams", pvpTeams);
        options.forEach((key, value) -> config.set("options." + key, value));
        if (kit != null) kit.save(config, "kit");
    }

    /** Reads saved settings over the defaults. Anything invalid is skipped with a warning, keeping the default. */
    void load(ConfigurationSection config, Logger logger) {
        String where = "games/" + type.getId() + ".yml";
        if (config.isList("modes")) {
            Set<PlayMode> modes = EnumSet.noneOf(PlayMode.class);
            for (String id : config.getStringList("modes")) {
                PlayMode mode = PlayMode.byId(id);
                if (mode == null || !type.supports(mode)) logger.warn("{}: ignoring mode '{}', which {} doesn't support", where, id, type.getId());
                else modes.add(mode);
            }
            if (modes.isEmpty()) {
                logger.warn("{}: no usable modes listed; enabling all supported modes", where);
            } else {
                enabled.clear();
                enabled.addAll(modes);
            }
        }
        apply(where, logger, "dynamic", () -> setDynamic(config.getBoolean("dynamic", dynamic)));
        apply(where, logger, "late-join", () -> setLateJoin(config.getBoolean("late-join", lateJoin)));
        // Max first, then min, so a saved pair like 4..20 is accepted whatever the defaults were.
        int savedMin = config.getInt("min-players", minPlayers), savedMax = config.getInt("max-players", maxPlayers);
        apply(where, logger, "max-players", () -> setMaxPlayers(Math.max(savedMax, minPlayers)));
        apply(where, logger, "min-players", () -> setMinPlayers(savedMin));
        apply(where, logger, "max-players", () -> setMaxPlayers(savedMax));
        apply(where, logger, "countdown-seconds", () -> setCountdownSeconds(config.getInt("countdown-seconds", countdownSeconds)));
        pvpFfa = config.getBoolean("pvp.ffa", pvpFfa);
        pvpTeams = config.getBoolean("pvp.teams", pvpTeams);
        for (String key : type.getOptions().keySet()) {
            Object saved = config.get("options." + key);
            if (saved != null) apply(where, logger, "options." + key, () -> setOption(key, saved));
        }
        if (type.hasKit() && config.isList("kit")) {
            apply(where, logger, "kit", () -> {
                Kit saved = Kit.load(config, "kit");
                if (!saved.isEmpty()) kit = saved;
            });
        }
    }

    private static void apply(String where, Logger logger, String key, Runnable setter) {
        try {
            setter.run();
        } catch (IllegalArgumentException e) {
            logger.warn("{}: ignoring {}: {}", where, key, e.getMessage());
        }
    }
}
