package me.sevj6.funCore.game;

import me.sevj6.funCore.FunCore;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.Nullable;

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
    private final Map<String, Minigame> games = new LinkedHashMap<>();
    private final Map<UUID, Minigame> playerGames = new HashMap<>();

    public GameManager(FunCore plugin) {
        this.plugin = plugin;
    }

    /** Adds a game and registers its event handlers. */
    public void register(Minigame game) {
        if (games.containsKey(game.getId())) {
            throw new IllegalArgumentException("A minigame with id '" + game.getId() + "' is already registered");
        }
        games.put(game.getId(), game);
        plugin.getServer().getPluginManager().registerEvents(game, plugin);
        plugin.getSLF4JLogger().info("Registered minigame '{}'", game.getId());
    }

    /** Force-stops a game (restoring its players), unregisters its event handlers and removes it. */
    public boolean unregister(String id) {
        Minigame game = games.get(id);
        if (game == null) return false;
        game.forceStop();
        HandlerList.unregisterAll(game);
        games.remove(id);
        return true;
    }

    /** Stops and unregisters every game. Called when the plugin is disabled. */
    public void shutdown() {
        for (String id : List.copyOf(games.keySet())) {
            try {
                unregister(id);
            } catch (Exception e) {
                plugin.getSLF4JLogger().error("Failed to stop minigame '{}'", id, e);
            }
        }
        playerGames.clear();
    }

    public @Nullable Minigame getGame(String id) {
        return games.get(id);
    }

    public Collection<Minigame> getGames() {
        return Collections.unmodifiableCollection(games.values());
    }

    /** The game this player is in, or {@code null}. */
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
}
