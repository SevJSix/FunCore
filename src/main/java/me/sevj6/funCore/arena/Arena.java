package me.sevj6.funCore.arena;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where a minigame is played: a region of one world, plus the places players are sent to.
 *
 * <ul>
 *     <li><b>region</b>: the playing area between two corners. Players can't leave it while the game runs, and its
 *     blocks are restored from a snapshot after every match.</li>
 *     <li><b>lobby</b>: where players wait after joining (optional).</li>
 *     <li><b>spawns</b>: where players are sent when the game starts, spread over the list (optional).</li>
 *     <li><b>points</b>: any other named positions a game wants, e.g. {@code center} (optional).</li>
 * </ul>
 *
 * <p>Each arena belongs to at most one game ({@link #getGame()}), and runs one match of it at a time; a game with
 * several arenas can run several matches at once. The world is looked up by name each time, so an
 * arena in an unloaded world just isn't {@link #isAvailable() available}. After changing an arena, call
 * {@link ArenaManager#save}.
 */
public final class Arena {

    private final String id;
    private final String worldName;
    private @Nullable BlockPos corner1, corner2;
    private @Nullable ArenaPoint lobby;
    private final List<ArenaPoint> spawns = new ArrayList<>();
    private final Map<String, ArenaPoint> points = new LinkedHashMap<>();
    private @Nullable String game;
    private boolean breakAllowed, placeAllowed, pickupAllowed, dropAllowed;

    /** The region the saved snapshot covers, or {@code null} if there is no snapshot. */
    private @Nullable Region snapshotRegion;
    /**
     * A match was played since the blocks were last restored. Saved to disk, so an arena left broken by a crash is
     * restored on the next start.
     */
    private boolean dirty;
    private boolean restoring;

    Arena(String id, String worldName) {
        this.id = id;
        this.worldName = worldName;
    }

    public String getId() {
        return id;
    }

    public String getWorldName() {
        return worldName;
    }

    /** The arena's world, or {@code null} if it isn't loaded. */
    public @Nullable World getWorld() {
        return Bukkit.getWorld(worldName);
    }

    public boolean isAvailable() {
        return getWorld() != null;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Region
    // ------------------------------------------------------------------------------------------------------------

    public @Nullable BlockPos getCorner1() {
        return corner1;
    }

    public @Nullable BlockPos getCorner2() {
        return corner2;
    }

    public void setCorner1(@Nullable BlockPos corner) {
        this.corner1 = corner;
    }

    public void setCorner2(@Nullable BlockPos corner) {
        this.corner2 = corner;
    }

    /** The region between the two corners, or {@code null} until both are set. */
    public @Nullable Region getRegion() {
        return corner1 == null || corner2 == null ? null : Region.of(corner1, corner2);
    }

    /** Whether the location is in this arena's world and inside its region. Always {@code false} without a region. */
    public boolean contains(Location location) {
        Region region = getRegion();
        return region != null && location.getWorld() != null && location.getWorld().getName().equals(worldName)
                && region.contains(location.getX(), location.getY(), location.getZ());
    }

    // ------------------------------------------------------------------------------------------------------------
    // Lobby, spawns and points. The Location getters return null (or nothing) while the world isn't loaded.
    // ------------------------------------------------------------------------------------------------------------

    public @Nullable ArenaPoint getLobbyPoint() {
        return lobby;
    }

    public void setLobby(@Nullable ArenaPoint lobby) {
        this.lobby = lobby;
    }

    public @Nullable Location getLobby() {
        World world = getWorld();
        return world == null || lobby == null ? null : lobby.toLocation(world);
    }

    public List<ArenaPoint> getSpawnPoints() {
        return Collections.unmodifiableList(spawns);
    }

    public void addSpawn(ArenaPoint spawn) {
        spawns.add(spawn);
    }

    public void clearSpawns() {
        spawns.clear();
    }

    public List<Location> getSpawns() {
        World world = getWorld();
        if (world == null) return List.of();
        List<Location> locations = new ArrayList<>(spawns.size());
        for (ArenaPoint spawn : spawns) locations.add(spawn.toLocation(world));
        return locations;
    }

    public Map<String, ArenaPoint> getPoints() {
        return Collections.unmodifiableMap(points);
    }

    /** Sets a named point, or removes it when {@code point} is {@code null}. */
    public void setPoint(String name, @Nullable ArenaPoint point) {
        if (point == null) points.remove(name);
        else points.put(name, point);
    }

    /** A named point, e.g. {@code getPoint("center")}. */
    public @Nullable Location getPoint(String name) {
        World world = getWorld();
        ArenaPoint point = points.get(name);
        return world == null || point == null ? null : point.toLocation(world);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Whether players in a match may break blocks inside the region (never outside it). Games can narrow this down
     * further, e.g. to only snow; see {@code Minigame#canBreak}.
     */
    public boolean isBreakAllowed() {
        return breakAllowed;
    }

    public void setBreakAllowed(boolean allowed) {
        this.breakAllowed = allowed;
    }

    /** Whether players in a match may place blocks inside the region (never outside it). See {@code Minigame#canPlace}. */
    public boolean isPlaceAllowed() {
        return placeAllowed;
    }

    public void setPlaceAllowed(boolean allowed) {
        this.placeAllowed = allowed;
    }

    /** Whether players in its game may pick up items, in the queue and during the match. See {@code Minigame#canPickUp}. */
    public boolean isPickupAllowed() {
        return pickupAllowed;
    }

    public void setPickupAllowed(boolean allowed) {
        this.pickupAllowed = allowed;
    }

    /** Whether players in its game may drop items, in the queue and during the match. See {@code Minigame#canDrop}. */
    public boolean isDropAllowed() {
        return dropAllowed;
    }

    public void setDropAllowed(boolean allowed) {
        this.dropAllowed = allowed;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Game and snapshot
    // ------------------------------------------------------------------------------------------------------------

    /** Id of the game played here, or {@code null}. Change it with {@link ArenaManager#setGame}. */
    public @Nullable String getGame() {
        return game;
    }

    void setGameInternal(@Nullable String game) {
        this.game = game;
    }

    public boolean hasSnapshot() {
        return snapshotRegion != null;
    }

    /** Whether there is a snapshot and it covers the current region (moving a corner makes it out of date). */
    public boolean isSnapshotCurrent() {
        return snapshotRegion != null && snapshotRegion.equals(getRegion());
    }

    @Nullable Region getSnapshotRegion() {
        return snapshotRegion;
    }

    void setSnapshotRegion(@Nullable Region region) {
        this.snapshotRegion = region;
    }

    public boolean isDirty() {
        return dirty;
    }

    void setDirty(boolean dirty) {
        this.dirty = dirty;
    }

    /** Whether its blocks are being restored right now. Its game can't be joined until that's done. */
    public boolean isRestoring() {
        return restoring;
    }

    void setRestoring(boolean restoring) {
        this.restoring = restoring;
    }

    @Override
    public String toString() {
        return "Arena[" + id + " in " + worldName + (game == null ? "" : ", game=" + game) + "]";
    }
}
