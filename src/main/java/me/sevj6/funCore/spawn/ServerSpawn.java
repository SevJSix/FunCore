package me.sevj6.funCore.spawn;

import me.sevj6.funCore.FunCore;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.Nullable;

/**
 * The server's spawn point: where players are sent when they join. It lives in config.yml under {@code spawn}, and
 * {@code /setspawn} sets it to where you stand.
 *
 * <pre>
 * spawn:
 *   world: world          # empty = off (players join where they logged out, as usual)
 *   x: 0.5
 *   y: 64.0
 *   z: 0.5
 *   yaw: 0.0
 *   pitch: 0.0
 *   every-join: true      # false = only a player's very first join
 * </pre>
 *
 * Players who left mid-game are the exception: they get their items and location from before the game back instead
 * (see {@link me.sevj6.funCore.player.PlayerStateManager}).
 */
public final class ServerSpawn implements Listener {

    private static final String PATH = "spawn";

    private final FunCore plugin;
    private @Nullable Location location;
    private boolean everyJoin = true;

    public ServerSpawn(FunCore plugin) {
        this.plugin = plugin;
    }

    /** Reads the spawn from config.yml. A world that doesn't exist turns it off, with a warning. */
    public void load() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection(PATH);
        location = null;
        if (section == null) return;
        everyJoin = section.getBoolean("every-join", true);
        String worldName = section.getString("world", "");
        if (worldName == null || worldName.isBlank()) return;
        World world = plugin.getServer().getWorld(worldName);
        if (world == null) {
            plugin.getSLF4JLogger().warn("config.yml: spawn world '{}' doesn't exist; the server spawn is off", worldName);
            return;
        }
        location = new Location(world, section.getDouble("x"), section.getDouble("y"), section.getDouble("z"),
                (float) section.getDouble("yaw"), (float) section.getDouble("pitch"));
    }

    /** The spawn point, or {@code null} if none is set. */
    public @Nullable Location getLocation() {
        return location == null ? null : location.clone();
    }

    /** Sets the spawn point and writes it to config.yml. */
    public void set(Location at) {
        location = at.clone();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection(PATH);
        if (section == null) section = plugin.getConfig().createSection(PATH);
        section.set("world", at.getWorld().getName());
        section.set("x", at.getX());
        section.set("y", at.getY());
        section.set("z", at.getZ());
        section.set("yaw", (double) at.getYaw());
        section.set("pitch", (double) at.getPitch());
        section.set("every-join", everyJoin);
        plugin.saveConfig();
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (location == null || (!everyJoin && player.hasPlayedBefore())) return;
        // Someone who left mid-game gets their own location back instead.
        if (plugin.getPlayerStateManager().hasState(player.getUniqueId())) return;
        player.teleport(location);
    }
}
