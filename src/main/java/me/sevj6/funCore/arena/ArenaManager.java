package me.sevj6.funCore.arena;

import me.sevj6.funCore.FunCore;
import me.sevj6.funCore.arena.nms.BlockEntityData;
import me.sevj6.funCore.util.Utils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.UnsafeValues;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Item;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

/**
 * Loads, saves and resets {@link Arena arenas}. Each arena is one file, {@code plugins/FunCore/arenas/<id>.yml}, plus
 * an optional block snapshot, {@code <id>.blocks}.
 *
 * <pre>
 * world: world
 * corner1: {x: 0, y: 60, z: 0}
 * corner2: {x: 40, y: 80, z: 40}
 * lobby: {x: 20.5, y: 81.0, z: 20.5, yaw: 0.0, pitch: 0.0}
 * spawns:
 *   - {x: 5.5, y: 61.0, z: 5.5, yaw: -45.0, pitch: 0.0}
 * points:
 *   center: {x: 20.5, y: 61.0, z: 20.5, yaw: 0.0, pitch: 0.0}
 * areas:                       # named cuboids, [minX, minY, minZ, maxX, maxY, maxZ]
 *   red-goal: [2, 60, 18, 4, 62, 22]
 * game: spleef                 # the game played here
 * allow-break: false           # players in a match may break blocks in the region
 * allow-place: false           # players in a match may place blocks in the region
 * allow-pickup: true           # players in its game may pick up items
 * allow-drop: true             # players in its game may drop items
 * allow-mob-spawning: false    # mobs may spawn in the region (plugin-spawned ones always can)
 * allow-environment: false     # fire, snowfall, melting, plant growth, ... may change the region
 * allow-fluid-flow: false      # water and lava may flow in the region
 * allow-fall-damage: true      # falling hurts in the region
 * snapshot-region: [0, 60, 0, 40, 80, 40]   # written by /arena snapshot
 * dirty: false                 # a match ran since the last restore
 * </pre>
 *
 * <p>The snapshot holds the blocks, block entity data (chest contents, sign text, ...) and the entities in the region
 * (mobs with their saddles, names and attributes, armour stands, item frames, boats, ...). Restoring is spread over
 * several ticks (a few milliseconds each), and only blocks that changed are set, so big arenas don't freeze the
 * server. Block entities and entities are put back once the blocks are done: every entity in the region (except
 * players and NPCs) is removed and the saved ones are spawned again.
 */
public final class ArenaManager {

    /** Largest region that can be snapshotted (blocks), about 170 x 170 x 170. */
    public static final long MAX_SNAPSHOT_VOLUME = 5_000_000;
    /** How long restoring may run each tick. */
    private static final long RESTORE_NANOS_PER_TICK = 15_000_000;

    private final FunCore plugin;
    private final Logger logger;
    private final File directory;
    /** NPCs carry this tag; they're never removed as stray mobs. */
    private final NamespacedKey npcKey;

    private final Map<String, Arena> arenas = new TreeMap<>();
    /** Snapshots already read from disk, so each match doesn't read the file again. */
    private final Map<String, ArenaSnapshot> snapshots = new HashMap<>();
    private final Map<String, RestoreJob> restores = new LinkedHashMap<>();
    private @Nullable BukkitTask restoreTask;

    public ArenaManager(FunCore plugin) {
        this.plugin = plugin;
        this.logger = plugin.getSLF4JLogger();
        this.directory = new File(plugin.getDataFolder(), "arenas");
        this.npcKey = new NamespacedKey(plugin, "npc");
    }

    /** The arena whose region contains the location, or {@code null}. */
    public @Nullable Arena getArenaAt(Location location) {
        for (Arena arena : arenas.values()) {
            if (arena.contains(location)) return arena;
        }
        return null;
    }

    public static boolean isValidId(String id) {
        return id.matches("[a-z0-9_-]{1,32}");
    }

    // ------------------------------------------------------------------------------------------------------------
    // Arenas
    // ------------------------------------------------------------------------------------------------------------

    public void load() {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            logger.error("Could not create {}", directory);
            return;
        }
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) return;
        for (File file : files) {
            String id = file.getName().substring(0, file.getName().length() - ".yml".length());
            if (!isValidId(id)) continue;
            try {
                Arena arena = read(file, id);
                arenas.put(id, arena);
            } catch (Exception e) {
                logger.error("Could not load arena {}: {}", file.getName(), e.getMessage());
            }
        }
        logger.info("Loaded {} {}", arenas.size(), Utils.plural(arenas.size(), "arena", "arenas"));

        // Arenas a crash left mid-match get their blocks back.
        for (Arena arena : arenas.values()) {
            if (arena.isDirty() && arena.isSnapshotCurrent() && arena.isAvailable()) {
                restore(arena).whenComplete((changed, error) -> {
                    if (error != null) logger.error("Could not restore arena '{}'", arena.getId(), error);
                    else logger.info("Restored arena '{}' after an unfinished match ({} blocks)", arena.getId(), changed);
                });
            }
        }
    }

    public @Nullable Arena getArena(String id) {
        return arenas.get(id);
    }

    public Collection<Arena> getArenas() {
        return Collections.unmodifiableCollection(arenas.values());
    }

    /** The arenas the game is played in, sorted by id. Each runs its own match. */
    public List<Arena> getArenasFor(String gameId) {
        List<Arena> list = new ArrayList<>();
        for (Arena arena : arenas.values()) {
            if (gameId.equals(arena.getGame())) list.add(arena);
        }
        return list;
    }

    /**
     * Creates and saves a new, empty arena in the given world.
     *
     * @throws IllegalArgumentException if the id is invalid or taken
     */
    public Arena create(String id, World world) {
        if (!isValidId(id)) throw new IllegalArgumentException("Arena ids are 1-32 characters: a-z, 0-9, _ and -.");
        if (arenas.containsKey(id)) throw new IllegalArgumentException("There is already an arena called '" + id + "'.");
        Arena arena = new Arena(id, world.getName());
        // Items can be picked up and dropped unless an admin turns it off (/arena set <arena> pickup|drop false).
        arena.setPickupAllowed(true);
        arena.setDropAllowed(true);
        arenas.put(id, arena);
        save(arena);
        return arena;
    }

    /** Deletes an arena, its file and its snapshot. Don't call it while the arena is being restored. */
    public boolean delete(String id) {
        Arena arena = arenas.remove(id);
        if (arena == null) return false;
        snapshots.remove(id);
        deleteFile(file(id));
        deleteFile(snapshotFile(id));
        return true;
    }

    /**
     * Sets the game played in an arena ({@code null} for none). A game can have any number of arenas. Afterwards, call
     * {@code GameManager#sync} for the old and new game so their matches follow.
     */
    public void setGame(Arena arena, @Nullable String gameId) {
        arena.setGameInternal(gameId);
        save(arena);
    }

    /** Writes the arena's file. Logs and returns {@code false} on failure. */
    public boolean save(Arena arena) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("world", arena.getWorldName());
        if (arena.getCorner1() != null) yaml.createSection("corner1", arena.getCorner1().toMap());
        if (arena.getCorner2() != null) yaml.createSection("corner2", arena.getCorner2().toMap());
        if (arena.getLobbyPoint() != null) yaml.createSection("lobby", arena.getLobbyPoint().toMap());
        List<Map<String, Object>> spawns = new ArrayList<>();
        for (ArenaPoint spawn : arena.getSpawnPoints()) spawns.add(spawn.toMap());
        if (!spawns.isEmpty()) yaml.set("spawns", spawns);
        if (!arena.getPoints().isEmpty()) {
            ConfigurationSection points = yaml.createSection("points");
            arena.getPoints().forEach((name, point) -> points.createSection(name, point.toMap()));
        }
        arena.getAreas().forEach((name, area) -> yaml.set("areas." + name, area.toList()));
        yaml.set("game", arena.getGame());
        yaml.set("allow-break", arena.isBreakAllowed());
        yaml.set("allow-place", arena.isPlaceAllowed());
        yaml.set("allow-pickup", arena.isPickupAllowed());
        yaml.set("allow-drop", arena.isDropAllowed());
        yaml.set("allow-mob-spawning", arena.isMobSpawningAllowed());
        yaml.set("allow-environment", arena.isEnvironmentAllowed());
        yaml.set("allow-fluid-flow", arena.isFluidFlowAllowed());
        yaml.set("allow-fall-damage", arena.isFallDamageAllowed());
        if (arena.getSnapshotRegion() != null) yaml.set("snapshot-region", arena.getSnapshotRegion().toList());
        yaml.set("dirty", arena.isDirty());

        File target = file(arena.getId());
        File temp = new File(directory, target.getName() + ".tmp");
        try {
            yaml.save(temp);
            Utils.moveReplacing(temp, target);
            return true;
        } catch (IOException e) {
            logger.error("Could not save arena '{}'", arena.getId(), e);
            return false;
        }
    }

    private Arena read(File file, String id) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file);
        String world = yaml.getString("world");
        if (world == null) throw new IllegalArgumentException("no world");
        Arena arena = new Arena(id, world);
        ConfigurationSection section;
        if ((section = yaml.getConfigurationSection("corner1")) != null) arena.setCorner1(BlockPos.fromMap(section.getValues(false)));
        if ((section = yaml.getConfigurationSection("corner2")) != null) arena.setCorner2(BlockPos.fromMap(section.getValues(false)));
        if ((section = yaml.getConfigurationSection("lobby")) != null) arena.setLobby(ArenaPoint.fromMap(section.getValues(false)));
        for (Map<?, ?> spawn : yaml.getMapList("spawns")) arena.addSpawn(ArenaPoint.fromMap(spawn));
        if ((section = yaml.getConfigurationSection("points")) != null) {
            for (String name : section.getKeys(false)) {
                ConfigurationSection point = section.getConfigurationSection(name);
                if (point != null) arena.setPoint(name, ArenaPoint.fromMap(point.getValues(false)));
            }
        }
        if ((section = yaml.getConfigurationSection("areas")) != null) {
            for (String name : section.getKeys(false)) arena.setArea(name, Region.fromList(section.getIntegerList(name)));
        }
        arena.setGameInternal(yaml.getString("game"));
        arena.setBreakAllowed(yaml.getBoolean("allow-break"));
        arena.setPlaceAllowed(yaml.getBoolean("allow-place"));
        arena.setPickupAllowed(yaml.getBoolean("allow-pickup", true));
        arena.setDropAllowed(yaml.getBoolean("allow-drop", true));
        arena.setMobSpawningAllowed(yaml.getBoolean("allow-mob-spawning", false));
        arena.setEnvironmentAllowed(yaml.getBoolean("allow-environment", false));
        arena.setFluidFlowAllowed(yaml.getBoolean("allow-fluid-flow", false));
        arena.setFallDamageAllowed(yaml.getBoolean("allow-fall-damage", true));
        List<Integer> snapshotRegion = yaml.getIntegerList("snapshot-region");
        if (!snapshotRegion.isEmpty() && snapshotFile(id).isFile()) arena.setSnapshotRegion(Region.fromList(snapshotRegion));
        arena.setDirty(yaml.getBoolean("dirty"));
        return arena;
    }

    private File file(String id) {
        return new File(directory, id + ".yml");
    }

    private File snapshotFile(String id) {
        return new File(directory, id + ".blocks");
    }

    private void deleteFile(File file) {
        if (file.exists() && !file.delete()) logger.warn("Could not delete {}", file);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Matches
    // ------------------------------------------------------------------------------------------------------------

    /** Records that a match is starting, so the blocks are restored afterwards (or on the next start, after a crash). */
    public void markDirty(Arena arena) {
        if (arena.isDirty() || !arena.isSnapshotCurrent()) return;
        arena.setDirty(true);
        save(arena);
    }

    /**
     * Tidies up after a match: removes dropped items, projectiles, XP orbs, falling blocks and lit TNT, and restores
     * the blocks if a match changed them.
     */
    public void cleanUp(Arena arena) {
        clearEntities(arena);
        if (arena.isDirty() && arena.isSnapshotCurrent()) {
            restore(arena).whenComplete((changed, error) -> {
                if (error != null) logger.error("Could not restore arena '{}'", arena.getId(), error);
            });
        }
    }

    /**
     * Restores the arena if it has an up-to-date snapshot, whether or not it's marked dirty. Called when someone
     * joins its game, so the match always starts on the snapshot. Does nothing if a restore is already running.
     */
    public void refresh(Arena arena) {
        if (!arena.isSnapshotCurrent() || !arena.isAvailable()) return;
        restore(arena).whenComplete((changed, error) -> {
            if (error != null) logger.error("Could not restore arena '{}'", arena.getId(), error);
        });
    }

    /**
     * Removes the leftovers of a match (see {@link #cleanUp}) inside the region, plus any mobs that got in when the
     * arena doesn't allow mob spawning (never NPCs). Returns how many were removed.
     */
    public int clearEntities(Arena arena) {
        World world = arena.getWorld();
        Region region = arena.getRegion();
        if (world == null || region == null) return 0;
        boolean removeMobs = !arena.isMobSpawningAllowed();
        Collection<Entity> leftovers = world.getNearbyEntities(region.toBoundingBox(), entity ->
                entity instanceof Item || entity instanceof Projectile || entity instanceof ExperienceOrb
                        || entity instanceof FallingBlock || entity instanceof TNTPrimed
                        || (removeMobs && entity instanceof Mob && !entity.getPersistentDataContainer().has(npcKey)));
        leftovers.forEach(Entity::remove);
        return leftovers.size();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Snapshots
    // ------------------------------------------------------------------------------------------------------------

    /** What a snapshot saved. */
    public record SnapshotInfo(long blocks, int blockEntities, int entities) {
    }

    /**
     * Saves everything in the arena's region as its snapshot: blocks, block entity data and entities. The world is read
     * now; building and writing the file happens off the main thread.
     *
     * @return completes on the main thread with what was saved
     * @throws IllegalArgumentException if the arena has no region, its world isn't loaded or it's too big
     */
    public CompletableFuture<SnapshotInfo> snapshot(Arena arena) {
        World world = arena.getWorld();
        Region region = arena.getRegion();
        if (world == null) throw new IllegalArgumentException("World '" + arena.getWorldName() + "' isn't loaded.");
        if (region == null) throw new IllegalArgumentException("Set both corners first.");
        if (region.volume() > MAX_SNAPSHOT_VOLUME) {
            throw new IllegalArgumentException("The region has " + region.volume() + " blocks; the most is " + MAX_SNAPSHOT_VOLUME + ".");
        }
        if (region.minY() < world.getMinHeight() || region.maxY() >= world.getMaxHeight()) {
            throw new IllegalArgumentException("The region goes outside the world's height limits.");
        }

        Map<Long, ChunkSnapshot> chunks = new HashMap<>();
        List<ArenaSnapshot.SavedBlockEntity> blockEntities = new ArrayList<>();
        for (int cx = region.minX() >> 4; cx <= region.maxX() >> 4; cx++) {
            for (int cz = region.minZ() >> 4; cz <= region.maxZ() >> 4; cz++) {
                Chunk chunk = world.getChunkAt(cx, cz);
                chunks.put(ArenaSnapshot.chunkKey(cx, cz), chunk.getChunkSnapshot(false, false, false));
                for (BlockState state : chunk.getTileEntities(block -> region.contains(block.getX(), block.getY(), block.getZ()), false)) {
                    byte[] data = BlockEntityData.save(state.getBlock());
                    if (data != null) blockEntities.add(new ArenaSnapshot.SavedBlockEntity(state.getX(), state.getY(), state.getZ(), data));
                }
            }
        }
        List<ArenaSnapshot.SavedEntity> entities = new ArrayList<>();
        for (Entity entity : world.getNearbyEntities(region.toBoundingBox(), this::isSavedEntity)) {
            Location at = entity.getLocation();
            if (!region.contains(at.getX(), at.getY(), at.getZ())) continue; // only touching the region
            try {
                entities.add(new ArenaSnapshot.SavedEntity(at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch(),
                        unsafe().serializeEntity(entity)));
            } catch (IllegalArgumentException e) {
                logger.warn("Arena '{}': couldn't save a {} in the snapshot: {}", arena.getId(), entity.getType(), e.getMessage());
            }
        }
        int dataVersion = unsafe().getDataVersion();

        CompletableFuture<SnapshotInfo> result = new CompletableFuture<>();
        File file = snapshotFile(arena.getId());
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                ArenaSnapshot snapshot = ArenaSnapshot.capture(region, chunks, dataVersion, blockEntities, entities);
                snapshot.write(file);
                Utils.sync(() -> {
                    snapshots.put(arena.getId(), snapshot);
                    arena.setSnapshotRegion(region);
                    arena.setDirty(false);
                    save(arena);
                    result.complete(new SnapshotInfo(region.volume(), blockEntities.size(), entities.size()));
                });
            } catch (Exception e) {
                Utils.sync(() -> result.completeExceptionally(e));
            }
        });
        return result;
    }

    /**
     * Puts the arena back the way the snapshot has it (blocks, block entities, entities), a few milliseconds per tick.
     * While it runs, {@link Arena#isRestoring()} is true. Asking again while it runs returns the same restore.
     *
     * @return completes on the main thread with the number of blocks that were changed
     */
    public CompletableFuture<Integer> restore(Arena arena) {
        return startRestore(arena, null);
    }

    /**
     * Puts one part of the arena back the way the snapshot has it: its blocks, block entities and the entities inside
     * it. For resetting a player's section mid-match (e.g. their tower in Clutch): the arena isn't marked as restoring
     * or clean, so its match carries on and the whole arena is still restored afterwards. Asking again for the same part
     * while it runs returns the same restore.
     *
     * @return completes on the main thread with the number of blocks that were changed (0 if the part is outside the
     * snapshot)
     */
    public CompletableFuture<Integer> restore(Arena arena, Region part) {
        Region snapshotRegion = arena.getSnapshotRegion();
        if (snapshotRegion == null || !arena.isSnapshotCurrent()) {
            return CompletableFuture.failedFuture(new IllegalStateException("There is no up-to-date snapshot."));
        }
        Region bounds = snapshotRegion.intersection(part);
        return bounds == null ? CompletableFuture.completedFuture(0) : startRestore(arena, bounds);
    }

    private CompletableFuture<Integer> startRestore(Arena arena, @Nullable Region part) {
        String key = part == null ? arena.getId() : arena.getId() + " " + part.toList();
        RestoreJob running = restores.get(key);
        if (running != null) return running.future;
        World world = arena.getWorld();
        if (world == null) return CompletableFuture.failedFuture(new IllegalStateException("World '" + arena.getWorldName() + "' isn't loaded."));
        if (!arena.isSnapshotCurrent()) return CompletableFuture.failedFuture(new IllegalStateException("There is no up-to-date snapshot."));

        RestoreJob job = new RestoreJob(key, arena, world, part);
        restores.put(key, job);
        if (part == null) arena.setRestoring(true);

        ArenaSnapshot cached = snapshots.get(arena.getId());
        if (cached != null) {
            job.begin(cached);
        } else if (!plugin.isEnabled()) {
            // Shutting down (a game's match stopping as the plugin disables): nothing can be scheduled now, and
            // shutdown() reads the snapshot and finishes every queued restore itself.
            return job.future;
        } else {
            File file = snapshotFile(arena.getId());
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    ArenaSnapshot snapshot = ArenaSnapshot.read(file);
                    Utils.sync(() -> {
                        snapshots.put(arena.getId(), snapshot);
                        job.begin(snapshot);
                    });
                } catch (Exception e) {
                    Utils.sync(() -> finish(job, e));
                }
            });
        }
        // While shutting down, shutdown() runs the queued restores to the end instead.
        if (restoreTask == null && plugin.isEnabled()) restoreTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickRestores, 1L, 1L);
        return job.future;
    }

    private void tickRestores() {
        long deadline = System.nanoTime() + RESTORE_NANOS_PER_TICK;
        for (RestoreJob job : List.copyOf(restores.values())) {
            if (job.isReady() && job.run(deadline)) finish(job, null);
            if (System.nanoTime() > deadline) break;
        }
        if (restores.isEmpty() && restoreTask != null) {
            restoreTask.cancel();
            restoreTask = null;
        }
    }

    private void finish(RestoreJob job, @Nullable Throwable error) {
        restores.remove(job.key);
        boolean whole = job.part == null;
        if (whole) job.arena.setRestoring(false);
        if (error != null) {
            job.future.completeExceptionally(error);
            return;
        }
        if (whole) {
            job.arena.setDirty(false);
            save(job.arena);
        }
        job.future.complete(job.changed);
    }

    /** Finishes every restore right away, so no arena is left half-reset when the server stops. */
    public void shutdown() {
        if (restoreTask != null) {
            restoreTask.cancel();
            restoreTask = null;
        }
        for (RestoreJob job : List.copyOf(restores.values())) {
            try {
                if (!job.isReady()) job.begin(ArenaSnapshot.read(snapshotFile(job.arena.getId())));
                job.run(Long.MAX_VALUE);
                finish(job, null);
            } catch (Exception e) {
                logger.error("Could not finish restoring arena '{}'", job.arena.getId(), e);
                finish(job, e);
            }
        }
    }

    /**
     * Whether an entity belongs in a snapshot: anything but players, NPCs (the NPC system keeps those) and the
     * leftovers of play (dropped items, projectiles, XP orbs, falling blocks, lit TNT).
     */
    private boolean isSavedEntity(Entity entity) {
        return !isKeptEntity(entity) && !(entity instanceof Item || entity instanceof Projectile || entity instanceof ExperienceOrb
                || entity instanceof FallingBlock || entity instanceof TNTPrimed);
    }

    /**
     * The server's entity serialization and data version. Bukkit marks the accessor deprecated (as "use with care"),
     * but it's the supported way to save entities with everything they carry.
     */
    @SuppressWarnings("deprecation")
    private static UnsafeValues unsafe() {
        return Bukkit.getUnsafe();
    }

    /** Players and NPCs: never removed when an arena is restored. */
    private boolean isKeptEntity(Entity entity) {
        return entity instanceof Player || entity.getPersistentDataContainer().has(npcKey);
    }

    /**
     * The whole arena, or one part of it, being restored: block by block (picking up where it left off each tick),
     * then its block entities and entities in one go.
     */
    private final class RestoreJob {

        final String key;
        final Arena arena;
        final World world;
        /** The part being restored, or {@code null} for the whole arena. */
        final @Nullable Region part;
        final CompletableFuture<Integer> future = new CompletableFuture<>();
        private @Nullable ArenaSnapshot snapshot;
        private BlockData[] palette;
        private Region bounds;
        private long cursor;
        private boolean extrasDone;
        int changed;

        RestoreJob(String key, Arena arena, World world, @Nullable Region part) {
            this.key = key;
            this.arena = arena;
            this.world = world;
            this.part = part;
        }

        void begin(ArenaSnapshot snapshot) {
            BlockData[] palette = new BlockData[snapshot.palette.length];
            for (int i = 0; i < palette.length; i++) palette[i] = Bukkit.createBlockData(snapshot.palette[i]);
            Region bounds = part == null ? snapshot.region : snapshot.region.intersection(part);
            this.palette = palette;
            this.snapshot = snapshot;
            if (bounds == null) {
                // Nothing of the part is in this snapshot.
                this.bounds = snapshot.region;
                this.cursor = snapshot.region.volume();
                this.extrasDone = true;
            } else {
                this.bounds = bounds;
            }
        }

        boolean isReady() {
            return snapshot != null;
        }

        /** Restores blocks until the deadline. Returns {@code true} once everything is done. */
        boolean run(long deadline) {
            ArenaSnapshot snapshot = Objects.requireNonNull(this.snapshot);
            Region bounds = this.bounds;
            int sizeX = bounds.sizeX(), sizeZ = bounds.sizeZ();
            long layer = (long) sizeX * sizeZ, total = bounds.volume();
            while (cursor < total) {
                if ((cursor & 1023) == 0 && System.nanoTime() > deadline) return false;
                long i = cursor++;
                int x = bounds.minX() + (int) (i % sizeX), y = bounds.minY() + (int) (i / layer), z = bounds.minZ() + (int) ((i / sizeX) % sizeZ);
                Block block = world.getBlockAt(x, y, z);
                BlockData target = palette[snapshot.blocks[snapshot.indexOf(x, y, z)]];
                if (!block.getBlockData().equals(target)) {
                    block.setBlockData(target, false);
                    changed++;
                }
            }
            if (!extrasDone) {
                extrasDone = true;
                restoreBlockEntities(snapshot);
                restoreEntities(snapshot);
            }
            return true;
        }

        /** Loads every saved block entity's data, even where the block didn't change (a chest that was emptied). */
        private void restoreBlockEntities(ArenaSnapshot snapshot) {
            if (snapshot.blockEntities.isEmpty()) return;
            if (snapshot.dataVersion != unsafe().getDataVersion()) {
                logger.warn("Arena '{}': its snapshot is from another Minecraft version, so chest contents and other block data "
                        + "weren't restored. Run /arena snapshot {} again.", arena.getId(), arena.getId());
                return;
            }
            for (ArenaSnapshot.SavedBlockEntity saved : snapshot.blockEntities) {
                if (!bounds.contains(saved.x(), saved.y(), saved.z())) continue;
                try {
                    BlockEntityData.load(world.getBlockAt(saved.x(), saved.y(), saved.z()), saved.data());
                } catch (RuntimeException e) {
                    logger.warn("Arena '{}': couldn't restore the block entity at {}, {}, {}: {}", arena.getId(),
                            saved.x(), saved.y(), saved.z(), e.getMessage());
                }
            }
        }

        /** Removes every entity in the bounds (but players and NPCs) and spawns the saved ones again. */
        private void restoreEntities(ArenaSnapshot snapshot) {
            List<ArenaSnapshot.SavedEntity> saved = snapshot.entities;
            if (saved == null) return; // an old snapshot without entities: leave them alone
            // By where they stand, not their hitbox, so nothing just outside a part (the next tower over) is touched.
            for (Entity entity : world.getNearbyEntities(bounds.toBoundingBox(), entity -> !isKeptEntity(entity))) {
                Location at = entity.getLocation();
                if (bounds.contains(at.getX(), at.getY(), at.getZ())) entity.remove();
            }
            for (ArenaSnapshot.SavedEntity entity : saved) {
                if (!bounds.contains(entity.x(), entity.y(), entity.z())) continue;
                try {
                    Entity spawned = unsafe().deserializeEntity(entity.data(), world);
                    // CUSTOM, so arenas that block mob spawning let it through.
                    spawned.spawnAt(new Location(world, entity.x(), entity.y(), entity.z(), entity.yaw(), entity.pitch()),
                            CreatureSpawnEvent.SpawnReason.CUSTOM);
                } catch (RuntimeException e) {
                    logger.warn("Arena '{}': couldn't respawn an entity from its snapshot: {}", arena.getId(), e.getMessage());
                }
            }
        }
    }
}
