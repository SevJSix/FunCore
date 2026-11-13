package me.sevj6.funCore.game;

import me.sevj6.funCore.FunCore;
import me.sevj6.funCore.arena.Arena;
import me.sevj6.funCore.util.Utils;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import me.sevj6.funCore.util.ItemBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.UnaryOperator;

/**
 * Base class for every minigame. An instance is one <b>match</b> of a {@link GameType}, played in one {@link Arena}
 * (or, for a game with no arenas, wherever its players are). The {@link GameManager} makes one per arena and reuses
 * it match after match.
 *
 * <p>This class owns the lifecycle ({@link GameState}), the player list, the start countdown, teams and the
 * saving/restoring of player state. Subclasses only define their rules by overriding the {@code on*} hooks and, when
 * someone wins, calling {@link #end()}. Their constructor matches {@link GameType.Factory}:
 * <pre>{@code
 * public SpleefGame(FunCore plugin, GameType type, @Nullable Arena arena) {
 *     super(plugin, type, arena);
 * }
 * }</pre>
 *
 * <p>A match is also a {@link Listener}: subclasses can declare {@code @EventHandler} methods directly. Those
 * handlers receive events for <b>every</b> player on the server (including players in other matches of the same
 * game), so check {@link #isPlaying(Entity)} first.
 *
 * <p>Use {@link #runTaskLater}/{@link #runTaskTimer} for game logic instead of the Bukkit scheduler; those tasks are
 * cancelled automatically when the match resets.
 */
public abstract class Minigame implements Listener {

    /** Marks the team selector item players get in the queue of a team-mode match. */
    private static final NamespacedKey TEAM_SELECTOR = new NamespacedKey("funcore", "team-selector");
    /** The max-health change a game makes with {@link #setMaxHealth}. */
    private static final NamespacedKey MAX_HEALTH = new NamespacedKey("funcore", "game-max-health");
    /** Hotbar slot of the team selector: the middle. */
    private static final int TEAM_SELECTOR_SLOT = 4;

    protected final FunCore plugin;

    private final GameType type;
    private final @Nullable Arena arena;

    private final Set<UUID> players = new LinkedHashSet<>();
    private PlayMode mode;
    private final List<GameTeam> teams = new ArrayList<>();
    private final Map<UUID, GameTeam> teamOf = new HashMap<>();
    /** The team slot each player picked in the queue ({@link #chooseTeam}); players who didn't pick aren't in it. */
    private final Map<UUID, Integer> teamChoices = new HashMap<>();
    private final Set<TeamMenu> teamMenus = new HashSet<>();
    /** Each team's spawn, so late joiners go to their team. */
    private final Map<GameTeam, Location> teamSpawns = new HashMap<>();
    /** Shows team colours on name tags in team modes; {@code null} otherwise and between matches. */
    private @Nullable Scoreboard scoreboard;
    private final List<BukkitTask> tasks = new ArrayList<>();
    /** Where each player was last put by the game (lobby, then spawn). Players who leave the arena are sent back here. */
    private final Map<UUID, Location> startPoints = new HashMap<>();
    /**
     * Where each player has moved their kit items during this match (item id to slot), so a fresh kit after a death
     * keeps their arrangement. Forgotten when they leave or the match ends; {@code /game layout} is the lasting one.
     */
    private final Map<UUID, Map<String, Integer>> matchLayouts = new HashMap<>();
    /** The match's music, while it plays (see {@link #playMusic}). */
    private me.sevj6.funCore.music.@Nullable SongSession music;

    private GameState state = GameState.WAITING;
    private @Nullable BukkitTask countdownTask;
    private int countdownRemaining;
    /** The countdown reached 0 but the arena was still being restored (so it's announced once). */
    private boolean waitingForArena;

    /**
     * @param type  the game this is a match of
     * @param arena where it's played, or {@code null} for a game with no arenas
     */
    protected Minigame(FunCore plugin, GameType type, @Nullable Arena arena) {
        this.plugin = Objects.requireNonNull(plugin);
        this.type = Objects.requireNonNull(type);
        this.arena = arena;
        this.mode = getSettings().getDefaultMode();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Hooks: override these to build a game
    // ------------------------------------------------------------------------------------------------------------

    /** Called when the game moves to {@link GameState#RUNNING}. Set up the round here. */
    protected abstract void onStart();

    /**
     * Called when the game moves to {@link GameState#ENDING}, while all players are still in it. Announce winners
     * here. Players are restored after {@link #getEndDelaySeconds()} seconds.
     */
    protected abstract void onEnd();

    /** Called after every player has been removed and the game is back to {@link GameState#WAITING}. Clean up arena state here. */
    protected void onReset() {
    }

    /** Called after a player has joined and been prepared. */
    protected void onPlayerJoin(Player player) {
    }

    /** Called after a player is removed, before their state is restored. Check win conditions here. */
    protected void onPlayerLeave(Player player, LeaveReason reason) {
    }

    /** Called whenever the state changes. */
    protected void onStateChange(GameState from, GameState to) {
    }

    /** Extra checks that can stop a player from joining. Return anything other than {@link JoinResult#SUCCESS} to deny. */
    protected JoinResult canJoin(Player player) {
        return JoinResult.SUCCESS;
    }

    /**
     * Gets a player ready to play right after their state has been saved. By default this wipes them and puts them in
     * adventure mode.
     */
    protected void preparePlayer(Player player) {
        player.setGameMode(GameMode.ADVENTURE);
        Utils.resetPlayer(player);
    }

    /**
     * Where players are teleported when they join, or {@code null} to leave them where they are. By default that's
     * the arena's lobby, if the game has an arena with one.
     */
    protected @Nullable Location getJoinLocation() {
        Arena arena = getArena();
        return arena == null ? null : arena.getLobby();
    }

    /**
     * Called after {@link #onPlayerJoin} for a player who joined while the match was running (only possible when the
     * game {@link GameType#supportsLateJoin() supports it} and late join is turned on). By then they have a team, have
     * been sent to a spawn and have the playing game mode. Set up their part of the match here (a kit, a timer, ...).
     */
    protected void onLateJoin(Player player) {
    }

    /**
     * Whether players get team colours (name tags, {@link GameTeam#getColor()}) outside the team modes too, where each
     * player is a team of one. Useful for red-vs-blue games played 1v1. Defaults to {@code false}: plain white.
     */
    protected boolean colorsSoloTeams() {
        return false;
    }

    /**
     * Whether teammates can hurt each other in team modes, when PvP is on. Defaults to {@code false}. Whether players
     * can hurt each other at all is the game's PvP setting ({@link GameSettings#isPvp}).
     */
    protected boolean allowsFriendlyFire() {
        return false;
    }

    /**
     * Whether players are sent to the arena's spawns when the game starts. Each team gets a spawn (a random one of
     * the arena's), so teammates start together; with more teams than spawns, spawns are shared.
     */
    protected boolean teleportToSpawnsOnStart() {
        return true;
    }

    /**
     * Whether players are kept inside the arena's region, in the queue and during the match. It applies to a player
     * whose {@link #getStartPoint start point} is inside the region (so a lobby outside the arena isn't affected);
     * anyone who gets out is teleported back to their start point. Going up past the top of the region isn't getting
     * out (see {@link Arena#isWithinBounds}), and those players also can't use blocks (doors, chests, buttons, ...)
     * outside the region.
     */
    protected boolean confinesToArena() {
        return true;
    }

    /**
     * Whether a player in the running game may break this block. By default: only if the arena allows breaking and
     * the block is inside its region. Override to narrow it down (e.g. only snow), or to allow it without an arena.
     * Players who aren't in a running game never can.
     */
    protected boolean canBreak(Player player, Block block) {
        Arena arena = getArena();
        return arena != null && arena.isBreakAllowed() && arena.contains(block.getLocation());
    }

    /**
     * Whether a player in the game (queued or playing) may pick up this item. By default: only if the arena allows
     * pickups. Override for game-specific rules.
     */
    protected boolean canPickUp(Player player, ItemStack item) {
        Arena arena = getArena();
        return arena != null && arena.isPickupAllowed();
    }

    /** Whether a player in the game (queued or playing) may drop this item. By default: only if the arena allows drops. */
    protected boolean canDrop(Player player, ItemStack item) {
        Arena arena = getArena();
        return arena != null && arena.isDropAllowed();
    }

    /** Like {@link #canBreak}, for placing blocks (and emptying buckets). */
    protected boolean canPlace(Player player, Block block) {
        Arena arena = getArena();
        return arena != null && arena.isPlaceAllowed() && arena.contains(block.getLocation());
    }

    /**
     * The game mode players are put in when the game starts. Adventure mode can't break or place blocks at all, so by
     * default it's survival when the arena allows either, and adventure otherwise.
     */
    protected GameMode getPlayingGameMode() {
        Arena arena = getArena();
        return arena != null && (arena.isBreakAllowed() || arena.isPlaceAllowed()) ? GameMode.SURVIVAL : GameMode.ADVENTURE;
    }

    /**
     * Most players this match's arena has room for, e.g. one per tower. {@link #getMaxPlayers()} never goes above it.
     * Defaults to no limit beyond the game's settings.
     */
    protected int getCapacity() {
        return Integer.MAX_VALUE;
    }

    /** Whether the arena's blocks are restored from its snapshot after each match. */
    protected boolean resetsArena() {
        return true;
    }

    /**
     * Called once a second during the countdown with the seconds left (never 0). By default it announces the time at
     * 30, 20, 10 and 5 to 1 seconds.
     */
    protected void onCountdownTick(int secondsLeft) {
        if (secondsLeft <= 5 || secondsLeft == 10 || secondsLeft == 20 || secondsLeft == 30) {
            broadcast(Utils.mm("<yellow>Starting in <gold><time></gold> <unit>.",
                    Placeholder.unparsed("time", String.valueOf(secondsLeft)),
                    Placeholder.unparsed("unit", Utils.plural(secondsLeft, "second", "seconds"))));
            Utils.playPling(audience(), secondsLeft <= 3 ? 1.5f : 1f);
        }
    }

    /** How long the start countdown lasts. Defaults to the game's {@code countdown-seconds} setting. */
    protected int getCountdownSeconds() {
        return getSettings().getCountdownSeconds();
    }

    /** How long players stay in the game after it ends before they are restored. */
    protected int getEndDelaySeconds() {
        return 5;
    }

    /** Whether the countdown starts by itself once {@link #getMinPlayers()} players have joined. */
    protected boolean autoStart() {
        return true;
    }

    /** Prefix added to {@link #broadcast} messages. */
    protected Component prefix() {
        return Utils.mm("<dark_gray>[</dark_gray><name><dark_gray>]</dark_gray> ", Placeholder.component("name", getDisplayName()));
    }

    // ------------------------------------------------------------------------------------------------------------
    // Players
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Tries to add a player. On success their state is saved to disk, they are prepared and teleported, and the
     * countdown starts if enough players have joined. If the match is running and {@link #acceptsLateJoin() takes
     * late joiners}, they're put straight into it instead. Players join through {@link GameManager#join}, which picks
     * the match.
     *
     * @param requested the mode the player asked for, or {@code null} for any. The first player to join sets the
     *                  match's mode.
     */
    final JoinResult join(Player player, @Nullable PlayMode requested) {
        if (plugin.getGameManager().isInGame(player)) return JoinResult.ALREADY_IN_GAME;
        boolean late = state == GameState.RUNNING;
        if (late ? !acceptsLateJoin() : !state.isJoinable()) return JoinResult.IN_PROGRESS;
        if (isFull()) return JoinResult.FULL;
        if (!isAvailable()) return JoinResult.NO_ARENA;
        if (requested != null && !getSettings().isEnabled(requested)) return JoinResult.MODE_NOT_AVAILABLE;
        if (requested != null && !players.isEmpty() && requested != mode) return JoinResult.MODE_NOT_AVAILABLE;

        JoinResult custom = canJoin(player);
        if (custom != JoinResult.SUCCESS) return custom;

        // Never touch a player we couldn't make a backup of.
        if (!plugin.getPlayerStateManager().capture(player)) return JoinResult.ERROR;

        if (players.isEmpty()) mode = requested != null ? requested : getSettings().getDefaultMode();
        players.add(player.getUniqueId());
        plugin.getGameManager().track(player.getUniqueId(), this);

        preparePlayer(player);
        if (late) {
            placeLateJoiner(player);
            onPlayerJoin(player);
            onLateJoin(player);
            if (music != null) music.addListener(player);
            broadcast(Utils.mm("<gray><player> joined the match. <dark_gray>(<count>/<max>)",
                    Placeholder.component("player", player.name()),
                    Placeholder.unparsed("count", String.valueOf(players.size())),
                    Placeholder.unparsed("max", String.valueOf(getMaxPlayers()))));
            return JoinResult.SUCCESS;
        }
        Location joinLocation = getJoinLocation();
        if (joinLocation != null) player.teleport(joinLocation);
        startPoints.put(player.getUniqueId(), player.getLocation());

        // Make sure the arena matches its snapshot before anyone plays in it, whatever happened to it since the
        // last match (a builder's edits, a crash). Only changed blocks are set, so this is cheap when nothing changed.
        if (arena != null && resetsArena()) plugin.getArenaManager().refresh(arena);

        onPlayerJoin(player);
        if (getChoosableTeams() > 0) {
            player.getInventory().setItem(TEAM_SELECTOR_SLOT, teamSelector(null));
            player.sendMessage(prefix().append(Utils.mm("<gray>Pick a team with the wool in your hotbar or <white>/game team</white>, or leave it to chance.")));
        }
        broadcast(Utils.mm("<gray><player> joined<mode>. <dark_gray>(<count>/<max>)",
                Placeholder.component("player", player.name()),
                Placeholder.component("mode", getSettings().getEnabledModes().size() > 1
                        ? Component.text(" (").append(mode.displayName()).append(Component.text(")")) : Component.empty()),
                Placeholder.unparsed("count", String.valueOf(players.size())),
                Placeholder.unparsed("max", String.valueOf(getMaxPlayers()))));

        if (autoStart() && state == GameState.WAITING && players.size() >= getMinPlayers()) startCountdown();
        return JoinResult.SUCCESS;
    }

    /**
     * Removes a player from the game. Their saved state is restored right away, except on
     * {@link LeaveReason#DISCONNECT}, where it is restored the next time they join the server.
     *
     * @return {@code false} if the player wasn't in this game
     */
    public final boolean leave(Player player, LeaveReason reason) {
        if (!players.remove(player.getUniqueId())) return false;
        startPoints.remove(player.getUniqueId());
        matchLayouts.remove(player.getUniqueId());
        if (music != null) music.removeListener(player.getUniqueId());
        resetMaxHealth(player);
        if (teamChoices.remove(player.getUniqueId()) != null) refreshTeamMenus();
        plugin.getGameManager().untrack(player.getUniqueId());
        if (scoreboard != null && player.isOnline() && player.getScoreboard() == scoreboard) {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }

        onPlayerLeave(player, reason);
        if (reason.restoresImmediately()) plugin.getPlayerStateManager().restore(player);

        if (reason.isMidGame()) {
            if (reason != LeaveReason.ELIMINATED) {
                broadcast(Utils.mm("<gray><player> left.", Placeholder.component("player", player.name())));
            }
            if (state == GameState.STARTING && players.size() < getMinPlayers()) {
                cancelCountdown();
            } else if (state == GameState.RUNNING && players.isEmpty()) {
                end();
            }
        }
        return true;
    }

    public final boolean isPlaying(Entity entity) {
        return entity instanceof Player && players.contains(entity.getUniqueId());
    }

    public final boolean isPlaying(UUID uuid) {
        return players.contains(uuid);
    }

    /** UUIDs of everyone in the game, in join order (read-only). */
    public final Set<UUID> getPlayerIds() {
        return Collections.unmodifiableSet(players);
    }

    /** Players in the game who are online. Normally that's all of them, since disconnecting removes a player. */
    public final List<Player> getPlayers() {
        List<Player> online = new ArrayList<>(players.size());
        for (UUID uuid : players) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) online.add(player);
        }
        return online;
    }

    public final int getPlayerCount() {
        return players.size();
    }

    public final boolean isFull() {
        return players.size() >= getMaxPlayers();
    }

    /** Whether a player could join this match while it's running: late join is on and it's a multiplayer mode. */
    public final boolean acceptsLateJoin() {
        return type.supportsLateJoin() && getSettings().isLateJoin() && mode.isMultiplayer();
    }

    /** Gives a late joiner a team (the smallest, in team modes), sends them to its spawn and sets their game mode. */
    private void placeLateJoiner(Player player) {
        GameTeam team = null;
        if (mode.isTeams()) {
            for (GameTeam candidate : teams) {
                if (team == null || getPlayers(candidate).size() < getPlayers(team).size()) team = candidate;
            }
        }
        if (team == null) {
            NamedTextColor color = colorsSoloTeams() ? GameTeam.colorOf(teams.size()) : NamedTextColor.WHITE;
            team = new GameTeam(teams.size(), color, player.name().color(color));
            teams.add(team);
            if (scoreboard != null) {
                Team display = scoreboard.registerNewTeam("team" + team.getIndex());
                display.color(color);
                display.setAllowFriendlyFire(allowsFriendlyFire());
                display.setOption(Team.Option.COLLISION_RULE, collisionRule());
            }
        }
        team.add(player.getUniqueId());
        teamOf.put(player.getUniqueId(), team);
        if (scoreboard != null) {
            Team display = scoreboard.getTeam("team" + team.getIndex());
            if (display != null) display.addEntry(player.getName());
            player.setScoreboard(scoreboard);
        }

        Location spawn = teamSpawns.get(team);
        if (spawn == null) spawn = getRandomSpawn();
        if (spawn != null) {
            player.teleport(spawn);
            startPoints.put(player.getUniqueId(), spawn);
        } else {
            startPoints.put(player.getUniqueId(), player.getLocation());
        }
        player.setGameMode(getPlayingGameMode());
    }

    // ------------------------------------------------------------------------------------------------------------
    // Teams: made when the match starts (outside team modes, one per player)
    // ------------------------------------------------------------------------------------------------------------

    /** The match's mode. Set by the first player to join; back to the game's default between matches. */
    public final PlayMode getMode() {
        return mode;
    }

    /** Every team of the running match, including ones whose players are all out. Empty before the match starts. */
    public final List<GameTeam> getTeams() {
        return Collections.unmodifiableList(teams);
    }

    public final @Nullable GameTeam getTeam(Player player) {
        return teamOf.get(player.getUniqueId());
    }

    public final @Nullable GameTeam getTeam(UUID uuid) {
        return teamOf.get(uuid);
    }

    /** Whether two different players are on the same team. Always {@code false} outside team modes. */
    public final boolean areTeammates(Player a, Player b) {
        if (a.equals(b)) return false;
        GameTeam team = teamOf.get(a.getUniqueId());
        return team != null && team.contains(b.getUniqueId());
    }

    /** Teams that still have a player in the match. When there's one left, it has usually won. */
    public final List<GameTeam> getRemainingTeams() {
        List<GameTeam> remaining = new ArrayList<>();
        for (GameTeam team : teams) {
            for (UUID member : team.getMembers()) {
                if (players.contains(member)) {
                    remaining.add(team);
                    break;
                }
            }
        }
        return remaining;
    }

    /** Players of a team who are still in the match. */
    public final List<Player> getPlayers(GameTeam team) {
        List<Player> online = new ArrayList<>();
        for (UUID member : team.getMembers()) {
            Player player = players.contains(member) ? Bukkit.getPlayer(member) : null;
            if (player != null) online.add(player);
        }
        return online;
    }

    /**
     * Splits the players into teams of the mode's size. Players who {@link #chooseTeam chose a team} are put on it;
     * everyone else is dealt out, in a random order, to the smallest team with room. There are as many teams as the
     * players need, plus any chosen team beyond those (so picking Yellow with only enough players for two teams gives
     * a match with Red, Blue... or Red and Yellow, depending on who picks what).
     */
    private void assignTeams() {
        teams.clear();
        teamOf.clear();
        List<Player> online = new ArrayList<>(getPlayers());
        Collections.shuffle(online);
        if (!mode.isTeams()) {
            for (Player player : online) {
                NamedTextColor color = colorsSoloTeams() ? GameTeam.colorOf(teams.size()) : NamedTextColor.WHITE;
                GameTeam team = new GameTeam(teams.size(), color, player.name().color(color));
                team.add(player.getUniqueId());
                teams.add(team);
                teamOf.put(player.getUniqueId(), team);
            }
            if (colorsSoloTeams() || !getSettings().isCollision()) showTeams(online);
            return;
        }
        int size = mode.teamSize();
        int needed = Math.max(1, (online.size() + size - 1) / size);
        Map<Integer, List<Player>> chosen = new TreeMap<>();
        List<Player> unchosen = new ArrayList<>();
        for (Player player : online) {
            Integer slot = teamChoices.get(player.getUniqueId());
            if (slot != null) chosen.computeIfAbsent(slot, s -> new ArrayList<>()).add(player);
            else unchosen.add(player);
        }
        // The chosen slots, topped up with the lowest free ones until there's room for everyone.
        Set<Integer> slots = new TreeSet<>(chosen.keySet());
        for (int slot = 0; slots.size() < needed; slot++) slots.add(slot);
        Map<Integer, GameTeam> bySlot = new TreeMap<>();
        for (int slot : slots) {
            GameTeam team = GameTeam.forSlot(slot);
            teams.add(team);
            bySlot.put(slot, team);
        }
        chosen.forEach((slot, members) -> members.forEach(player -> addToTeam(bySlot.get(slot), player)));
        // The smallest team with room each time, which keeps sizes within one of each other as far as choices allow.
        for (Player player : unchosen) {
            GameTeam smallest = null;
            for (GameTeam team : teams) {
                if (smallest == null || team.size() < smallest.size()) smallest = team;
            }
            addToTeam(smallest, player);
        }
        showTeams(online);
    }

    private void addToTeam(GameTeam team, Player player) {
        team.add(player.getUniqueId());
        teamOf.put(player.getUniqueId(), team);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Choosing teams in the queue (team modes only)
    // ------------------------------------------------------------------------------------------------------------

    /**
     * How many teams players can pick from right now: in the queue of a team-mode match, enough teams for the most
     * players it takes (at least 2, at most {@link GameTeam#NAMED_SLOTS}). 0 once it has started, or outside team modes.
     */
    public final int getChoosableTeams() {
        if (!mode.isTeams() || !state.isJoinable()) return 0;
        int teams = (getMaxPlayers() + mode.teamSize() - 1) / mode.teamSize();
        return Math.min(GameTeam.NAMED_SLOTS, Math.max(2, teams));
    }

    /** The team slot the player picked in the queue, or {@code null} for "whichever needs players". */
    public final @Nullable Integer getTeamChoice(Player player) {
        return teamChoices.get(player.getUniqueId());
    }

    /** Players who picked this team slot, in the order they picked it. */
    public final List<UUID> getTeamChoosers(int slot) {
        List<UUID> choosers = new ArrayList<>();
        for (UUID uuid : players) {
            Integer choice = teamChoices.get(uuid);
            if (choice != null && choice == slot) choosers.add(uuid);
        }
        return choosers;
    }

    /**
     * Puts a queued player on a team for when the match starts, telling them (and everyone, on success) how it went.
     * A team can't take more than the mode's team size.
     *
     * @param slot the team's slot ({@link GameTeam#slotOf}), or -1 to be put wherever players are needed
     * @return whether the choice was made
     */
    public final boolean chooseTeam(Player player, int slot) {
        if (!isPlaying(player)) return false;
        int choosable = getChoosableTeams();
        if (choosable == 0) {
            player.sendMessage(prefix().append(Utils.mm(mode.isTeams() ? "<red>Teams are set once the match starts."
                    : "<red>This match isn't played in teams.")));
            return false;
        }
        Integer current = teamChoices.get(player.getUniqueId());
        if (slot < 0) {
            if (current == null) return false;
            teamChoices.remove(player.getUniqueId());
            player.sendMessage(prefix().append(Utils.mm("<gray>You'll be put on whichever team needs players.")));
        } else {
            if (slot >= choosable) {
                player.sendMessage(prefix().append(Utils.mm("<red>This match only has <count> teams to choose from.",
                        Placeholder.unparsed("count", String.valueOf(choosable)))));
                return false;
            }
            if (current != null && current == slot) return false;
            if (getTeamChoosers(slot).size() >= mode.teamSize()) {
                player.sendMessage(prefix().append(Utils.mm("<red><team> is full.", Placeholder.component("team", GameTeam.nameOf(slot)))));
                return false;
            }
            teamChoices.put(player.getUniqueId(), slot);
            broadcast(Utils.mm("<gray><player> joined <team>.", Placeholder.component("player", player.name()),
                    Placeholder.component("team", GameTeam.nameOf(slot))));
        }
        updateTeamSelector(player);
        refreshTeamMenus();
        return true;
    }

    /** Opens the team picker for a queued player, or tells them why they can't pick. */
    public final void openTeamMenu(Player player) {
        if (!isPlaying(player)) return;
        int choosable = getChoosableTeams();
        if (choosable == 0) {
            player.sendMessage(prefix().append(Utils.mm(mode.isTeams() ? "<red>Teams are set once the match starts."
                    : "<red>This match isn't played in teams.")));
            return;
        }
        new TeamMenu(this, player, choosable).open(player);
    }

    /** Whether the item is the team selector players get in the queue. */
    public static boolean isTeamSelector(@Nullable ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(TEAM_SELECTOR);
    }

    void teamMenuOpened(TeamMenu menu) {
        teamMenus.add(menu);
    }

    void teamMenuClosed(TeamMenu menu) {
        teamMenus.remove(menu);
    }

    private void refreshTeamMenus() {
        for (TeamMenu menu : List.copyOf(teamMenus)) menu.refresh();
    }

    /** The wool players right-click in the queue, coloured like the team they picked (white for none). */
    private ItemStack teamSelector(@Nullable Integer slot) {
        return ItemBuilder.of(slot == null ? Material.WHITE_WOOL : GameTeam.woolOf(slot))
                .name(slot == null ? Utils.mm("<yellow>Choose a team") : GameTeam.nameOf(slot))
                .lore("<gray>Right-click to choose your team.")
                .meta(meta -> meta.getPersistentDataContainer().set(TEAM_SELECTOR, PersistentDataType.BOOLEAN, true))
                .build();
    }

    private void updateTeamSelector(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isTeamSelector(contents[i])) player.getInventory().setItem(i, teamSelector(teamChoices.get(player.getUniqueId())));
        }
    }

    /** Takes the selectors away and closes the pickers, once teams are set. */
    private void putAwayTeamSelectors() {
        for (TeamMenu menu : List.copyOf(teamMenus)) menu.close();
        teamMenus.clear();
        for (Player player : getPlayers()) {
            ItemStack[] contents = player.getInventory().getContents();
            for (int i = 0; i < contents.length; i++) {
                if (isTeamSelector(contents[i])) player.getInventory().setItem(i, null);
            }
            if (isTeamSelector(player.getItemOnCursor())) player.setItemOnCursor(null);
        }
    }

    /**
     * Colours name tags by team, on a scoreboard only this match's players see. It also carries the collision setting,
     * since whether players push each other is decided by their scoreboard teams.
     */
    private Team.OptionStatus collisionRule() {
        return getSettings().isCollision() ? Team.OptionStatus.ALWAYS : Team.OptionStatus.NEVER;
    }

    private void showTeams(List<Player> online) {
        scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        for (GameTeam team : teams) {
            Team display = scoreboard.registerNewTeam("team" + team.getIndex());
            display.color(team.getColor());
            display.setAllowFriendlyFire(allowsFriendlyFire());
            display.setCanSeeFriendlyInvisibles(true);
            display.setOption(Team.Option.COLLISION_RULE, collisionRule());
            for (UUID member : team.getMembers()) {
                Player player = Bukkit.getPlayer(member);
                if (player != null) display.addEntry(player.getName());
            }
        }
        for (Player player : online) player.setScoreboard(scoreboard);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------------------------------------------------

    /** Starts the countdown. Only works from {@link GameState#WAITING}. */
    public final boolean startCountdown() {
        if (state != GameState.WAITING) return false;
        setState(GameState.STARTING);
        countdownRemaining = getCountdownSeconds();
        waitingForArena = false;
        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (countdownRemaining <= 0) {
                if (isArenaRestoring()) {
                    if (!waitingForArena) broadcast(Utils.mm("<gray>Waiting for the arena to finish resetting..."));
                    waitingForArena = true;
                    return; // try again next second
                }
                start();
                return;
            }
            onCountdownTick(countdownRemaining--);
        }, 0L, 20L);
        return true;
    }

    /** Stops the countdown and goes back to {@link GameState#WAITING}. */
    public final boolean cancelCountdown() {
        if (state != GameState.STARTING) return false;
        stopCountdownTask();
        setState(GameState.WAITING);
        broadcast(Utils.mm("<red>Not enough players. Countdown cancelled."));
        return true;
    }

    /**
     * Starts the game now, skipping any countdown that is left. Needs at least one player, and the arena (if any)
     * must have finished restoring.
     */
    public final boolean start() {
        if (!state.isJoinable() || players.isEmpty() || isArenaRestoring()) return false;
        stopCountdownTask();
        setState(GameState.RUNNING);
        putAwayTeamSelectors();
        assignTeams();
        if (arena != null) {
            if (resetsArena()) plugin.getArenaManager().markDirty(arena);
            if (teleportToSpawnsOnStart()) sendToSpawns(arena);
        }
        GameMode mode = getPlayingGameMode();
        for (Player player : getPlayers()) player.setGameMode(mode);
        onStart();
        // The game's music setting, unless onStart already chose something (or ended the match).
        if (state == GameState.RUNNING && music == null && !getSettings().getMusic().isEmpty()) {
            playMusic(getSettings().getMusic(), true);
        }
        return true;
    }

    private void sendToSpawns(Arena arena) {
        List<Location> spawns = new ArrayList<>(arena.getSpawns());
        if (spawns.isEmpty()) return;
        Collections.shuffle(spawns);
        // By position rather than slot, so Red and Yellow (with no Blue) still get different spawns.
        for (int i = 0; i < teams.size(); i++) {
            GameTeam team = teams.get(i);
            Location spawn = spawns.get(i % spawns.size());
            teamSpawns.put(team, spawn);
            for (Player player : getPlayers(team)) {
                player.teleport(spawn);
                startPoints.put(player.getUniqueId(), spawn);
            }
        }
    }

    /**
     * Ends the game: calls {@link #onEnd()}, waits {@link #getEndDelaySeconds()} seconds, then restores every player
     * and resets to {@link GameState#WAITING}.
     */
    public final boolean end() {
        if (state != GameState.RUNNING) return false;
        setState(GameState.ENDING);
        stopMusic(2);
        onEnd();
        int delay = getEndDelaySeconds();
        if (delay <= 0) {
            reset(LeaveReason.GAME_ENDED);
        } else {
            runTaskLater(() -> reset(LeaveReason.GAME_ENDED), Utils.secondsToTicks(delay));
        }
        return true;
    }

    /**
     * Stops the game right away from any state, with no end delay, and restores every player. Used on shutdown and
     * when a game is unregistered.
     */
    public final void forceStop() {
        if (state == GameState.RUNNING) {
            setState(GameState.ENDING);
            onEnd();
        }
        if (state != GameState.WAITING || !players.isEmpty()) reset(LeaveReason.SHUTDOWN);
    }

    private void reset(LeaveReason reason) {
        stopCountdownTask();
        cancelTasks();
        for (Player player : getPlayers()) leave(player, reason);
        // Anyone left in the list is offline (shouldn't happen); untrack them so they aren't stuck "in game".
        for (UUID uuid : List.copyOf(players)) plugin.getGameManager().untrack(uuid);
        players.clear();
        startPoints.clear();
        matchLayouts.clear();
        stopMusic(0);
        teams.clear();
        teamOf.clear();
        teamChoices.clear();
        for (TeamMenu menu : List.copyOf(teamMenus)) menu.close();
        teamMenus.clear();
        teamSpawns.clear();
        scoreboard = null;
        mode = getSettings().getDefaultMode();
        setState(GameState.WAITING);
        onReset();
        // Players are gone, so this can't hit anyone: clear leftovers and put the blocks back.
        if (arena != null) plugin.getArenaManager().cleanUp(arena);
    }

    private void setState(GameState newState) {
        GameState old = state;
        if (old == newState) return;
        state = newState;
        onStateChange(old, newState);
    }

    private void stopCountdownTask() {
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
    }

    public final GameState getState() {
        return state;
    }

    /** Seconds left on the countdown, or 0 if it isn't running. */
    public final int getCountdownRemaining() {
        return state == GameState.STARTING ? countdownRemaining : 0;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Tasks: cancelled automatically when the game resets
    // ------------------------------------------------------------------------------------------------------------

    protected final BukkitTask runTaskLater(Runnable task, long delayTicks) {
        return track(Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks));
    }

    protected final BukkitTask runTaskTimer(Runnable task, long delayTicks, long periodTicks) {
        return track(Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks));
    }

    private BukkitTask track(BukkitTask task) {
        tasks.removeIf(BukkitTask::isCancelled);
        tasks.add(task);
        return task;
    }

    private void cancelTasks() {
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Messaging
    // ------------------------------------------------------------------------------------------------------------

    /** Everyone in the game as a single Adventure audience (for messages, titles, sounds, action bars, ...). */
    public final Audience audience() {
        return Audience.audience(getPlayers());
    }

    /** Sends a message to everyone in the game, with {@link #prefix()} in front. */
    public final void broadcast(Component message) {
        audience().sendMessage(prefix().append(message));
    }

    // ------------------------------------------------------------------------------------------------------------
    // Info
    // ------------------------------------------------------------------------------------------------------------

    public final GameType getType() {
        return type;
    }

    /** The game's id, e.g. {@code "spleef"} (shared by all its matches). */
    public final String getId() {
        return type.getId();
    }

    public final Component getDisplayName() {
        return type.getDisplayName();
    }

    /** The game's adjustable settings ({@code /game config}). */
    public final GameSettings getSettings() {
        return plugin.getGameManager().getSettings(type);
    }

    /**
     * One of the game's kits for this match ({@link GameType#DEFAULT_KIT} for the main one): the arena's own copy
     * ({@code /arena kit}) if it has one, else the game's ({@code /game kit}). {@code null} if the game has no such kit.
     */
    public final me.sevj6.funCore.kit.@Nullable Kit getKit(String name) {
        var own = arena == null ? null : arena.getKit(name);
        return own != null ? own : getSettings().getKit(name);
    }

    // Option values for this match: the arena's own value (/arena option) wins over the game's (/game config).

    public final int getIntOption(String key) {
        return arenaOption(key) instanceof Number n ? n.intValue() : getSettings().getInt(key);
    }

    public final double getDoubleOption(String key) {
        return arenaOption(key) instanceof Number n ? n.doubleValue() : getSettings().getDouble(key);
    }

    public final boolean getBooleanOption(String key) {
        return arenaOption(key) instanceof Boolean b ? b : getSettings().getBoolean(key);
    }

    public final String getStringOption(String key) {
        Object value = arenaOption(key);
        return value != null ? String.valueOf(value) : getSettings().getString(key);
    }

    /** The arena's override for an option, checked, or {@code null} if it has none (or a hand-edited bad one). */
    private @Nullable Object arenaOption(String key) {
        GameOption option = type.getOptions().get(key);
        Object raw = arena == null || option == null ? null : arena.getOptionOverrides().get(key);
        if (raw == null) return null;
        try {
            return option.parse(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Players this match needs before its countdown starts, for its current mode (see {@link GameSettings#getMinPlayers(PlayMode)}). */
    public final int getMinPlayers() {
        return getSettings().getMinPlayers(mode);
    }

    /** Most players in this match, for its current mode (and no more than the arena has room for). */
    public final int getMaxPlayers() {
        return Math.max(1, Math.min(getSettings().getMaxPlayers(mode), getCapacity()));
    }

    /** The arena this match is played in, or {@code null} for a game with no arenas. Never changes. */
    public final @Nullable Arena getArena() {
        return arena;
    }

    /**
     * Whether the match can be played: it has no arena, or its arena's world is loaded and the arena has everything
     * the game needs ({@link GameType#checkArena}).
     */
    public final boolean isAvailable() {
        return arena == null || (arena.isAvailable() && type.checkArena(arena).isEmpty());
    }

    /**
     * The match's scoreboard, which every player in it sees (for a sidebar, say). It exists while the match runs in
     * team modes, or always when {@link #colorsSoloTeams()} is on; {@code null} otherwise.
     */
    protected final @Nullable Scoreboard getScoreboard() {
        return scoreboard;
    }

    /**
     * Clears the player's inventory and gives them the game's kit ({@link GameSettings#getKit()}), arranged the way they
     * chose in {@code /game layout}. Does nothing if the game has no kit.
     *
     * @param adjust changes each item before it's given (e.g. team colours); {@code UnaryOperator.identity()} for none
     */
    protected final void giveKit(Player player, UnaryOperator<ItemStack> adjust) {
        giveKit(player, GameType.DEFAULT_KIT, adjust);
    }

    /**
     * Like {@link #giveKit(Player, UnaryOperator)}, for one of the game's named kits (e.g. a level's items). The player's
     * layout applies to it too, since items keep their names across kits. Does nothing if there's no such kit.
     */
    protected final void giveKit(Player player, String kitName, UnaryOperator<ItemStack> adjust) {
        var kit = getKit(kitName);
        if (kit == null) return;
        Map<String, Integer> layout = new HashMap<>();
        if (type.hasKit()) layout.putAll(plugin.getGameManager().getLayouts(type).get(player.getUniqueId()));
        // Their rearranging this match beats their saved layout. The first kit of the match has nothing to read yet.
        Map<String, Integer> match = matchLayouts.get(player.getUniqueId());
        if (match != null) match.putAll(arrangement(player, kit, adjust));
        else match = new HashMap<>();
        matchLayouts.put(player.getUniqueId(), match);
        layout.putAll(match);
        kit.give(player, layout, adjust);
    }

    /**
     * Where the kit's items are in the player's inventory or offhand right now (item id to slot), matched by item type. Items
     * they've used up (all their blocks placed, say) aren't found, so those keep their earlier slot.
     */
    private static Map<String, Integer> arrangement(Player player, me.sevj6.funCore.kit.Kit kit, UnaryOperator<ItemStack> adjust) {
        Map<String, Integer> found = new HashMap<>();
        Set<Integer> claimed = new HashSet<>();
        // The inventory (0-35), then the offhand.
        ItemStack[] storage = player.getInventory().getStorageContents();
        Map<Integer, ItemStack> slots = new java.util.LinkedHashMap<>();
        for (int slot = 0; slot < storage.length; slot++) slots.put(slot, storage[slot]);
        slots.put(me.sevj6.funCore.kit.Kit.OFFHAND, player.getInventory().getItemInOffHand());
        for (var item : kit.getItems()) {
            if (!item.isRearrangeable()) continue;
            Material material = adjust.apply(item.item().clone()).getType();
            for (Map.Entry<Integer, ItemStack> entry : slots.entrySet()) {
                ItemStack held = entry.getValue();
                if (held != null && held.getType() == material && claimed.add(entry.getKey())) {
                    found.put(item.id(), entry.getKey());
                    break;
                }
            }
        }
        return found;
    }

    /**
     * Plays songs to everyone in the match (and anyone who joins it later), shuffled, replacing whatever music was
     * playing. It stops when the match ends. Unknown song ids are skipped; nothing plays if none are known or the
     * server has no NoteBlockAPI. Games get their {@code music} setting played this way automatically; call it
     * yourself for something else, e.g. a final-round track.
     *
     * @param loop keep going round the songs until the match ends, rather than stopping after the last
     */
    protected final void playMusic(List<String> songIds, boolean loop) {
        stopMusic(0);
        var manager = plugin.getMusic();
        if (manager == null || !manager.isAvailable()) return;
        var songs = manager.getSongs(songIds);
        if (songs.isEmpty()) {
            plugin.getSLF4JLogger().warn("{}: none of the songs {} exist (see /music list)", type.getId(), songIds);
            return;
        }
        music = manager.session("game:" + type.getId(), songs).shuffle(true).loop(loop).start();
        music.addListeners(getPlayers());
    }

    /** Stops the match's music, fading out over this many seconds (0 for right away). */
    protected final void stopMusic(double fadeSeconds) {
        if (music == null) return;
        music.fadeOut(fadeSeconds);
        music = null;
    }

    /**
     * Where the player was last put by the game: where they stood after joining (the lobby, if there is one), then
     * their spawn once the game starts. {@code null} if they aren't in the game.
     */
    public final @Nullable Location getStartPoint(Player player) {
        Location point = startPoints.get(player.getUniqueId());
        return point == null ? null : point.clone();
    }

    /**
     * Sets the player's max health (in health points: 2 per heart) for as long as they're in the match, e.g. one heart
     * for a game where any hit should count. It's undone when they leave, and never saved with the player, so a crash
     * or disconnect can't leave them with it. Their health is lowered to fit; heal them to fill it up.
     */
    protected final void setMaxHealth(Player player, double health) {
        org.bukkit.attribute.AttributeInstance attribute = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        if (attribute == null) return;
        attribute.removeModifier(MAX_HEALTH);
        double change = Math.max(1, health) - attribute.getBaseValue();
        if (change != 0) {
            attribute.addTransientModifier(new org.bukkit.attribute.AttributeModifier(MAX_HEALTH, change,
                    org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER));
        }
        if (player.getHealth() > attribute.getValue()) player.setHealth(attribute.getValue());
    }

    /** Undoes {@link #setMaxHealth}. */
    protected final void resetMaxHealth(Player player) {
        org.bukkit.attribute.AttributeInstance attribute = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        if (attribute != null) attribute.removeModifier(MAX_HEALTH);
    }

    /** Changes where the player is sent back to if they leave the arena, e.g. after a game respawns them somewhere. */
    protected final void setStartPoint(Player player, Location location) {
        if (isPlaying(player)) startPoints.put(player.getUniqueId(), location.clone());
    }

    /** Whether this game's arena is having its blocks restored. The game can't start until it's done. */
    public final boolean isArenaRestoring() {
        Arena arena = getArena();
        return arena != null && arena.isRestoring();
    }

    /** A random spawn of the arena, falling back to its lobby; {@code null} if there's neither. */
    public final @Nullable Location getRandomSpawn() {
        Arena arena = getArena();
        if (arena == null) return null;
        List<Location> spawns = arena.getSpawns();
        if (spawns.isEmpty()) return arena.getLobby();
        return spawns.get(ThreadLocalRandom.current().nextInt(spawns.size()));
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[id=" + getId() + ", arena=" + (arena == null ? "none" : arena.getId())
                + ", state=" + state + ", players=" + players.size() + "]";
    }
}
