package me.sevj6.funCore.game;

import me.sevj6.funCore.FunCore;
import me.sevj6.funCore.arena.Arena;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import me.sevj6.funCore.util.Utils;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps track of every registered {@link Minigame} and which game each player is in. A player can be in at most one
 * game at a time.
 */
public final class GameManager implements Listener {

    private final FunCore plugin;
    private final Map<String, GameType> types = new LinkedHashMap<>();
    /** The matches of each game: one per arena, or a single arena-less one for a game with no arenas. */
    private final Map<String, List<Minigame>> matches = new HashMap<>();
    private final Map<UUID, Minigame> playerGames = new HashMap<>();
    private final BukkitTask boundsTask;

    public GameManager(FunCore plugin) {
        this.plugin = plugin;
        this.boundsTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::checkBounds, 20L, 20L);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Games and their matches
    // ------------------------------------------------------------------------------------------------------------

    /** Adds a game and makes a match for each of its arenas. */
    public void register(GameType type) {
        if (types.containsKey(type.getId())) {
            throw new IllegalArgumentException("A minigame with id '" + type.getId() + "' is already registered");
        }
        types.put(type.getId(), type);
        matches.put(type.getId(), new ArrayList<>());
        sync(type.getId());
        plugin.getSLF4JLogger().info("Registered minigame '{}' ({} {})", type.getId(), matches.get(type.getId()).size(),
                Utils.plural(matches.get(type.getId()).size(), "match", "matches"));
    }

    /** Force-stops every match of a game (restoring their players) and removes it. */
    public boolean unregister(String id) {
        if (types.remove(id) == null) return false;
        for (Minigame match : matches.remove(id)) close(match);
        return true;
    }

    /** Stops and unregisters every game. Called when the plugin is disabled. */
    public void shutdown() {
        boundsTask.cancel();
        for (String id : List.copyOf(types.keySet())) {
            try {
                unregister(id);
            } catch (Exception e) {
                plugin.getSLF4JLogger().error("Failed to stop minigame '{}'", id, e);
            }
        }
        playerGames.clear();
    }

    /**
     * Makes the game's matches match its arenas again: a new arena gets a match, and a match whose arena was removed
     * from the game is stopped (sending its players back). Call it after changing which game an arena belongs to.
     */
    public void sync(String gameId) {
        GameType type = types.get(gameId);
        if (type == null) return;
        List<Minigame> list = matches.get(gameId);
        List<Arena> arenas = plugin.getArenaManager().getArenasFor(gameId);
        boolean arenaLess = arenas.isEmpty() && !type.requiresArena();

        for (Minigame match : List.copyOf(list)) {
            boolean keep = match.getArena() == null ? arenaLess : arenas.contains(match.getArena());
            if (!keep) {
                close(match);
                list.remove(match);
            }
        }
        for (Arena arena : arenas) {
            if (list.stream().noneMatch(match -> match.getArena() == arena)) list.add(open(type, arena));
        }
        if (arenaLess && list.isEmpty()) list.add(open(type, null));
    }

    private Minigame open(GameType type, @Nullable Arena arena) {
        Minigame match = type.create(plugin, arena);
        plugin.getServer().getPluginManager().registerEvents(match, plugin);
        return match;
    }

    private void close(Minigame match) {
        match.forceStop();
        HandlerList.unregisterAll(match);
    }

    public @Nullable GameType getType(String id) {
        return types.get(id);
    }

    public Collection<GameType> getTypes() {
        return Collections.unmodifiableCollection(types.values());
    }

    /** Every match of a game (one per arena). */
    public List<Minigame> getMatches(String gameId) {
        List<Minigame> list = matches.get(gameId);
        return list == null ? List.of() : Collections.unmodifiableList(list);
    }

    /** The match played in an arena, or {@code null} if the arena has no (registered) game. */
    public @Nullable Minigame getMatch(Arena arena) {
        if (arena.getGame() == null) return null;
        for (Minigame match : getMatches(arena.getGame())) {
            if (match.getArena() == arena) return match;
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Joining
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Puts a player into a match of the game: one that's already filling up (in the requested mode, if any), or else
     * an empty arena. If every arena is busy, they're refused.
     *
     * @param mode the team mode they asked for, or {@code null} for any
     */
    public JoinResult join(Player player, GameType type, @Nullable TeamMode mode) {
        if (isInGame(player)) return JoinResult.ALREADY_IN_GAME;
        if (mode != null && !type.supports(mode)) return JoinResult.MODE_NOT_SUPPORTED;
        List<Minigame> list = getMatches(type.getId());
        if (list.isEmpty()) return JoinResult.NO_ARENA;

        // A queue that's filling up, the fullest first, so matches start sooner.
        Minigame best = null;
        for (Minigame match : list) {
            if (match.getPlayerCount() == 0 || !match.getState().isJoinable() || match.isFull() || !match.isAvailable()) continue;
            if (mode != null && !match.getMode().equals(mode)) continue;
            if (best == null || match.getPlayerCount() > best.getPlayerCount()) best = match;
        }
        // Otherwise an empty arena, preferring one that isn't being restored.
        if (best == null) best = emptyMatch(list, false);
        if (best == null) best = emptyMatch(list, true);
        if (best == null) {
            boolean anyAvailable = list.stream().anyMatch(Minigame::isAvailable);
            return anyAvailable ? JoinResult.ALL_ARENAS_BUSY : JoinResult.NO_ARENA;
        }
        return best.join(player, mode);
    }

    private static @Nullable Minigame emptyMatch(List<Minigame> list, boolean allowRestoring) {
        for (Minigame match : list) {
            if (match.getPlayerCount() == 0 && match.getState() == GameState.WAITING && match.isAvailable()
                    && (allowRestoring || !match.isArenaRestoring())) {
                return match;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Players
    // ------------------------------------------------------------------------------------------------------------

    /** The match this player is in, or {@code null}. */
    public @Nullable Minigame getGame(Player player) {
        return playerGames.get(player.getUniqueId());
    }

    public @Nullable Minigame getGame(UUID uuid) {
        return playerGames.get(uuid);
    }

    public boolean isInGame(Player player) {
        return playerGames.containsKey(player.getUniqueId());
    }

    // Only Minigame should change who is in which game.
    void track(UUID uuid, Minigame game) {
        playerGames.put(uuid, game);
    }

    void untrack(UUID uuid) {
        playerGames.remove(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Minigame game = getGame(event.getPlayer());
        // Their saved state stays on disk and PlayerStateManager restores it when they come back.
        if (game != null) game.leave(event.getPlayer(), LeaveReason.DISCONNECT);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Teams: no friendly fire unless the game allows it (see Minigame#allowsFriendlyFire)
    // ------------------------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = event.getDamager() instanceof Player player ? player
                : event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter ? shooter
                : null;
        if (attacker == null) return;
        Minigame game = getGame(victim);
        if (game != null && game == getGame(attacker) && !game.allowsFriendlyFire() && game.areTeammates(attacker, victim)) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Items: each game decides (see Minigame#canPickUp / #canDrop; by default the arena's settings)
    // ------------------------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Minigame game = getGame(player);
        if (game != null && !game.canPickUp(player, event.getItem().getItemStack())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Minigame game = getGame(event.getPlayer());
        if (game != null && !game.canDrop(event.getPlayer(), event.getItemDrop().getItemStack())) event.setCancelled(true);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Building: players in a game only break and place what the game allows (see Minigame#canBreak / #canPlace).
    // Players who aren't in a game are left alone.
    // ------------------------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!mayBreak(event.getPlayer(), event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!mayPlace(event.getPlayer(), event.getBlockPlaced())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (!mayPlace(event.getPlayer(), event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (!mayBreak(event.getPlayer(), event.getBlockClicked())) event.setCancelled(true);
    }

    private boolean mayBreak(Player player, Block block) {
        Minigame game = getGame(player);
        return game == null || (game.getState() == GameState.RUNNING && game.canBreak(player, block));
    }

    private boolean mayPlace(Player player, Block block) {
        Minigame game = getGame(player);
        return game == null || (game.getState() == GameState.RUNNING && game.canPlace(player, block));
    }

    // ------------------------------------------------------------------------------------------------------------
    // Keeping players inside their arena, in the queue and during the match (see Minigame#confinesToArena).
    // Anyone found outside is teleported back to their start point.
    // ------------------------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock()) return;
        Location back = returnPoint(event.getPlayer(), event.getTo());
        if (back != null) event.setTo(back);
    }

    /** Ender pearls, chorus fruit and the like can't take players out either. Plugin teleports (e.g. restoring a player) can. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN) return;
        if (returnPoint(event.getPlayer(), event.getTo()) != null) event.setCancelled(true);
    }

    /**
     * Once a second, catches anyone who got out in a way that doesn't fire a move event (riding a vehicle, being
     * pushed, an admin /tp, respawning, ...).
     */
    private void checkBounds() {
        for (Map.Entry<UUID, Minigame> entry : playerGames.entrySet()) {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null) continue;
            Location back = returnPoint(player, player.getLocation());
            if (back != null) {
                player.leaveVehicle();
                player.teleport(back);
            }
        }
    }

    /**
     * Where to send a player who is (or is about to be) at {@code location}, or {@code null} if that's allowed: they
     * aren't in a confining game, their start point isn't inside the arena, or the location is inside it.
     */
    private @Nullable Location returnPoint(Player player, Location location) {
        Minigame game = getGame(player);
        if (game == null || !game.confinesToArena()) return null;
        Arena arena = game.getArena();
        if (arena == null || arena.getRegion() == null || arena.contains(location)) return null;
        Location start = game.getStartPoint(player);
        // A start point outside the arena (e.g. a separate lobby) means they aren't in the arena yet.
        return start != null && arena.contains(start) ? start : null;
    }
}
