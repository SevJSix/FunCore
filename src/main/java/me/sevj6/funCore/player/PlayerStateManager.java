package me.sevj6.funCore.player;

import me.sevj6.funCore.FunCore;
import me.sevj6.funCore.util.Utils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Saves a player's {@link PlayerState} before they enter a minigame and puts it back afterward.
 *
 * <p>Each saved state is written to {@code plugins/FunCore/player-states/<uuid>.yml} as soon as it is captured. The
 * file is only deleted after the state has been put back on the player, so if they disconnect or the server crashes
 * mid-game, the state is restored the next time they join.
 */
public final class PlayerStateManager implements Listener {

    private final FunCore plugin;
    private final File directory;
    private final Map<UUID, PlayerState> cache = new HashMap<>();
    /** Players who were dead when a restore was asked for; they're restored when they respawn. */
    private final Set<UUID> awaitingRespawn = new HashSet<>();

    public PlayerStateManager(FunCore plugin) {
        this.plugin = plugin;
        this.directory = new File(plugin.getDataFolder(), "player-states");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            plugin.getSLF4JLogger().error("Could not create {}", directory);
        }
    }

    /**
     * Saves the player's current state to memory and disk.
     *
     * <p>If the player already has a saved state that hasn't been restored, the older one is kept, since that's the
     * real pre-game state.
     *
     * @return {@code false} if the state could not be written to disk, in which case the player shouldn't be modified
     */
    public boolean capture(Player player) {
        UUID uuid = player.getUniqueId();
        if (hasState(uuid)) {
            plugin.getSLF4JLogger().warn("{} already has a saved state; keeping the original", player.getName());
            return true;
        }
        PlayerState state = PlayerState.capture(player);
        try {
            state.save(file(uuid));
        } catch (IOException e) {
            plugin.getSLF4JLogger().error("Failed to save player state for {}", player.getName(), e);
            return false;
        }
        cache.put(uuid, state);
        return true;
    }

    /**
     * Puts the player's saved state back and deletes it. If the player is dead, the restore waits until they respawn.
     *
     * @return {@code true} if a state was put back right now
     */
    public boolean restore(Player player) {
        UUID uuid = player.getUniqueId();
        if (!player.isOnline()) return false;

        PlayerState state = getState(uuid);
        if (state == null) return false;

        if (player.isDead()) {
            awaitingRespawn.add(uuid);
            return false;
        }

        try {
            state.apply(player);
        } catch (Exception e) {
            // Keep the file so the state isn't lost; it will be tried again the next time they join.
            plugin.getSLF4JLogger().error("Failed to restore player state for {}", player.getName(), e);
            return false;
        }
        discard(uuid);
        return true;
    }

    /** Whether the player has a saved state that hasn't been restored yet. */
    public boolean hasState(UUID uuid) {
        return cache.containsKey(uuid) || file(uuid).isFile();
    }

    /** The player's saved state, from memory or disk, or {@code null} if there isn't one or it couldn't be read. */
    public @Nullable PlayerState getState(UUID uuid) {
        PlayerState cached = cache.get(uuid);
        if (cached != null) return cached;

        File file = file(uuid);
        if (!file.isFile()) return null;
        try {
            PlayerState loaded = PlayerState.load(file);
            cache.put(uuid, loaded);
            return loaded;
        } catch (IOException e) {
            plugin.getSLF4JLogger().error("Failed to load player state from {}", file, e);
            return null;
        }
    }

    /** Deletes a saved state without restoring it. */
    public void discard(UUID uuid) {
        cache.remove(uuid);
        awaitingRespawn.remove(uuid);
        File file = file(uuid);
        if (file.exists() && !file.delete()) {
            plugin.getSLF4JLogger().warn("Could not delete player state file {}", file);
        }
    }

    /** Restores every online player who has a pending state and isn't in a game, e.g. after a reload. */
    public void restoreOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (hasState(player.getUniqueId()) && !plugin.getGameManager().isInGame(player)) restore(player);
        }
    }

    private File file(UUID uuid) {
        return new File(directory, uuid + ".yml");
    }

    // ------------------------------------------------------------------------------------------------------------
    // Listeners
    // ------------------------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!hasState(player.getUniqueId())) return;
        // Wait a tick so the player is fully in the world before teleporting them.
        Utils.runLater(() -> {
            if (!player.isOnline() || plugin.getGameManager().isInGame(player)) return;
            if (restore(player)) {
                player.sendMessage(Utils.prefixed(Utils.mm("<gray>Your items and location from before your last game have been restored.")));
            }
        }, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!awaitingRespawn.remove(player.getUniqueId())) return;
        Utils.runLater(() -> {
            if (player.isOnline()) restore(player);
        }, 1L);
    }
}
