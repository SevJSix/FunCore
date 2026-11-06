package me.sevj6.funCore.game;

import me.sevj6.funCore.FunCore;
import me.sevj6.funCore.util.Utils;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Base class for every minigame.
 *
 * <p>This class owns the lifecycle ({@link GameState}), the player list, the start countdown and the saving/restoring
 * of player state. Subclasses only define their rules by overriding the {@code on*} hooks and, when someone wins,
 * calling {@link #end()}.
 *
 * <p>A minigame is also a {@link Listener}: it is registered for events when it is added to the {@link GameManager}, so
 * subclasses can declare {@code @EventHandler} methods directly. Those handlers receive events for <b>every</b> player
 * on the server, so check {@link #isPlaying(Entity)} first.
 *
 * <p>Use {@link #runTaskLater}/{@link #runTaskTimer} for game logic instead of the Bukkit scheduler; those tasks are
 * cancelled automatically when the game resets.
 */
public abstract class Minigame implements Listener {

    protected final FunCore plugin;

    private final String id;
    private final Component displayName;
    private final int minPlayers;
    private final int maxPlayers;

    private final Set<UUID> players = new LinkedHashSet<>();
    private final List<BukkitTask> tasks = new ArrayList<>();

    private GameState state = GameState.WAITING;
    private @Nullable BukkitTask countdownTask;
    private int countdownRemaining;

    /**
     * @param id          unique, lowercase identifier (used in commands and config), e.g. {@code "spleef"}
     * @param displayName name shown to players
     * @param minPlayers  players needed before the countdown starts
     * @param maxPlayers  maximum number of players
     */
    protected Minigame(FunCore plugin, String id, Component displayName, int minPlayers, int maxPlayers) {
        if (!id.matches("[a-z0-9_-]+")) throw new IllegalArgumentException("Invalid minigame id: " + id);
        if (minPlayers < 1) throw new IllegalArgumentException("minPlayers must be at least 1");
        if (maxPlayers < minPlayers) throw new IllegalArgumentException("maxPlayers must be >= minPlayers");
        this.plugin = Objects.requireNonNull(plugin);
        this.id = id;
        this.displayName = displayName;
        this.minPlayers = minPlayers;
        this.maxPlayers = maxPlayers;
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

    /** Where players are teleported when they join, or {@code null} to leave them where they are. */
    protected @Nullable Location getJoinLocation() {
        return null;
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

    /** How long the start countdown lasts. */
    protected int getCountdownSeconds() {
        return 10;
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
        return Utils.mm("<dark_gray>[</dark_gray><name><dark_gray>]</dark_gray> ", Placeholder.component("name", displayName));
    }

    // ------------------------------------------------------------------------------------------------------------
    // Players
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Tries to add a player. On success their state is saved to disk, they are prepared and teleported, and the
     * countdown starts if enough players have joined.
     */
    public final JoinResult join(Player player) {
        if (plugin.getGameManager().isInGame(player)) return JoinResult.ALREADY_IN_GAME;
        if (!state.isJoinable()) return JoinResult.IN_PROGRESS;
        if (isFull()) return JoinResult.FULL;

        JoinResult custom = canJoin(player);
        if (custom != JoinResult.SUCCESS) return custom;

        // Never touch a player we couldn't make a backup of.
        if (!plugin.getPlayerStateManager().capture(player)) return JoinResult.ERROR;

        players.add(player.getUniqueId());
        plugin.getGameManager().track(player.getUniqueId(), this);

        preparePlayer(player);
        Location joinLocation = getJoinLocation();
        if (joinLocation != null) player.teleport(joinLocation);

        onPlayerJoin(player);
        broadcast(Utils.mm("<gray><player> joined. <dark_gray>(<count>/<max>)",
                Placeholder.component("player", player.name()),
                Placeholder.unparsed("count", String.valueOf(players.size())),
                Placeholder.unparsed("max", String.valueOf(maxPlayers))));

        if (autoStart() && state == GameState.WAITING && players.size() >= minPlayers) startCountdown();
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
        plugin.getGameManager().untrack(player.getUniqueId());

        onPlayerLeave(player, reason);
        if (reason.restoresImmediately()) plugin.getPlayerStateManager().restore(player);

        if (reason.isMidGame()) {
            broadcast(Utils.mm("<gray><player> left.", Placeholder.component("player", player.name())));
            if (state == GameState.STARTING && players.size() < minPlayers) {
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
        return players.size() >= maxPlayers;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------------------------------------------------

    /** Starts the countdown. Only works from {@link GameState#WAITING}. */
    public final boolean startCountdown() {
        if (state != GameState.WAITING) return false;
        setState(GameState.STARTING);
        countdownRemaining = getCountdownSeconds();
        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (countdownRemaining <= 0) {
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

    /** Starts the game now, skipping any countdown that is left. Needs at least one player. */
    public final boolean start() {
        if (!state.isJoinable() || players.isEmpty()) return false;
        stopCountdownTask();
        setState(GameState.RUNNING);
        onStart();
        return true;
    }

    /**
     * Ends the game: calls {@link #onEnd()}, waits {@link #getEndDelaySeconds()} seconds, then restores every player
     * and resets to {@link GameState#WAITING}.
     */
    public final boolean end() {
        if (state != GameState.RUNNING) return false;
        setState(GameState.ENDING);
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
        setState(GameState.WAITING);
        onReset();
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

    public final String getId() {
        return id;
    }

    public final Component getDisplayName() {
        return displayName;
    }

    public final int getMinPlayers() {
        return minPlayers;
    }

    public final int getMaxPlayers() {
        return maxPlayers;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[id=" + id + ", state=" + state + ", players=" + players.size() + "]";
    }
}
