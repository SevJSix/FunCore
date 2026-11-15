package me.sevj6.funCore.spawn;

import me.sevj6.funCore.FunCore;
import me.sevj6.funCore.arena.BlockPos;
import me.sevj6.funCore.arena.Region;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.Nullable;

import java.util.List;

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
 *   area:                 # the spawn lobby, kept safe by SpawnProtection; world empty = none
 *     world: world
 *     pos1: [-20, 50, -20]
 *     pos2: [20, 90, 20]
 * </pre>
 *
 * Players who left mid-game are the exception: they get their items and location from before the game back instead
 * (see {@link me.sevj6.funCore.player.PlayerStateManager}).
 */
public final class ServerSpawn implements Listener {

    private static final String PATH = "spawn";
    private static final String AREA = "area";

    private final FunCore plugin;
    private @Nullable Location location;
    private boolean everyJoin = true;
    /** The spawn area's world, and its corners (either may be missing while it's being set up). */
    private @Nullable String areaWorld;
    private @Nullable BlockPos areaCorner1, areaCorner2;

    public ServerSpawn(FunCore plugin) {
        this.plugin = plugin;
    }

    /** Reads the spawn from config.yml. A world that doesn't exist turns it off, with a warning. */
    public void load() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection(PATH);
        location = null;
        areaWorld = null;
        areaCorner1 = areaCorner2 = null;
        if (section == null) return;
        loadArea(section.getConfigurationSection(AREA));
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

    // ------------------------------------------------------------------------------------------------------------
    // The spawn area
    // ------------------------------------------------------------------------------------------------------------

    private void loadArea(@Nullable ConfigurationSection area) {
        if (area == null) return;
        String world = area.getString("world", "");
        if (world == null || world.isBlank()) return;
        areaWorld = world;
        areaCorner1 = corner(area.getIntegerList("pos1"));
        areaCorner2 = corner(area.getIntegerList("pos2"));
        if (plugin.getServer().getWorld(world) == null) {
            plugin.getSLF4JLogger().warn("config.yml: the spawn area's world '{}' doesn't exist; nowhere is protected", world);
        } else if (getArea() == null) {
            plugin.getSLF4JLogger().warn("config.yml: the spawn area needs both pos1 and pos2 as [x, y, z]; nowhere is protected");
        }
    }

    private static @Nullable BlockPos corner(List<Integer> xyz) {
        return xyz.size() == 3 ? new BlockPos(xyz.get(0), xyz.get(1), xyz.get(2)) : null;
    }

    /** The spawn area, or {@code null} if it isn't set (or only one corner is). */
    public @Nullable Region getArea() {
        return areaWorld == null || areaCorner1 == null || areaCorner2 == null ? null : Region.of(areaCorner1, areaCorner2);
    }

    /** The spawn area's world name, or {@code null} if it isn't set. */
    public @Nullable String getAreaWorld() {
        return areaWorld;
    }

    /** A corner set so far, or {@code null}. */
    public @Nullable BlockPos getAreaCorner(boolean first) {
        return first ? areaCorner1 : areaCorner2;
    }

    /** Whether a spot (e.g. a player's feet) is in the spawn area. */
    public boolean isInArea(Location at) {
        Region area = getArea();
        return area != null && at.getWorld() != null && at.getWorld().getName().equals(areaWorld)
                && area.contains(at.getX(), at.getY(), at.getZ());
    }

    /**
     * Sets one corner of the spawn area to the block at {@code at}. A corner in another world than the area's starts
     * the area over in that world (the other corner is dropped).
     */
    public void setAreaCorner(boolean first, Location at) {
        String world = at.getWorld().getName();
        if (!world.equals(areaWorld)) {
            areaWorld = world;
            areaCorner1 = areaCorner2 = null;
        }
        if (first) areaCorner1 = BlockPos.of(at);
        else areaCorner2 = BlockPos.of(at);
        saveArea();
    }

    /** Sets the whole spawn area at once (e.g. to a WorldEdit selection). */
    public void setArea(String world, BlockPos corner1, BlockPos corner2) {
        areaWorld = world;
        areaCorner1 = corner1;
        areaCorner2 = corner2;
        saveArea();
    }

    /** Removes the spawn area: nowhere is protected. */
    public void clearArea() {
        areaWorld = null;
        areaCorner1 = areaCorner2 = null;
        saveArea();
    }

    private void saveArea() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection(PATH);
        if (section == null) section = plugin.getConfig().createSection(PATH);
        ConfigurationSection area = section.getConfigurationSection(AREA);
        if (area == null) area = section.createSection(AREA);
        area.set("world", areaWorld == null ? "" : areaWorld);
        area.set("pos1", areaCorner1 == null ? List.of() : List.of(areaCorner1.x(), areaCorner1.y(), areaCorner1.z()));
        area.set("pos2", areaCorner2 == null ? List.of() : List.of(areaCorner2.x(), areaCorner2.y(), areaCorner2.z()));
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
