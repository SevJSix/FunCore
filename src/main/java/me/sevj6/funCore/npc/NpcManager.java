package me.sevj6.funCore.npc;

import me.sevj6.funCore.FunCore;
import me.sevj6.funCore.menu.MenuRegistry;
import me.sevj6.funCore.npc.behavior.BehaviorRegistry;
import me.sevj6.funCore.npc.behavior.CommandBehavior;
import me.sevj6.funCore.npc.behavior.GreetingBehavior;
import me.sevj6.funCore.npc.behavior.MenuBehavior;
import me.sevj6.funCore.npc.behavior.MessageBehavior;
import me.sevj6.funCore.npc.nms.NmsNpcPacketSender;
import me.sevj6.funCore.npc.nms.NpcPacketSender;
import me.sevj6.funCore.npc.skin.MojangSkinFetcher;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Keeps every {@link Npc}: creates, removes, saves and loads them, and ticks them (spawning, look-at, proximity).
 *
 * <p>NPCs are either <b>saved</b> (made with {@code /npc create}, written to {@code npcs/<id>.yml} and loaded on
 * startup) or <b>temporary</b> (made in code, e.g. by a minigame, and gone after a restart).
 */
public final class NpcManager {

    private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");
    /** How often to check for NPCs whose chunk has loaded, in ticks. */
    private static final int SPAWN_CHECK_TICKS = 10;

    private final FunCore plugin;
    private final NpcStorage storage;
    private final NpcPacketSender packets = new NmsNpcPacketSender();
    private final MojangSkinFetcher skinFetcher;
    private final BehaviorRegistry behaviors;
    private final NamespacedKey npcKey;
    private final Map<String, Npc> npcs = new TreeMap<>();

    private @Nullable BukkitTask tickTask;
    private long tick;

    public NpcManager(FunCore plugin, MenuRegistry menus) {
        this.plugin = plugin;
        this.behaviors = new BehaviorRegistry(plugin.getSLF4JLogger());
        behaviors.register(MessageBehavior.definition());
        behaviors.register(GreetingBehavior.definition());
        behaviors.register(CommandBehavior.definition());
        behaviors.register(MenuBehavior.definition(menus));
        this.npcKey = new NamespacedKey(plugin, "npc");
        this.skinFetcher = new MojangSkinFetcher(plugin.getName() + "/" + plugin.getPluginMeta().getVersion());

        File directory = new File(plugin.getDataFolder(), "npcs");
        if (!directory.isDirectory() && !directory.mkdirs()) plugin.getSLF4JLogger().error("Could not create {}", directory);
        this.storage = new NpcStorage(directory);
    }

    /** Loads every saved NPC and starts ticking. */
    public void start() {
        loadAll();
        tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    /** Stops ticking and despawns every NPC. Nothing needs saving: every change is saved when it's made. */
    public void shutdown() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        npcs.values().forEach(Npc::despawn);
        npcs.clear();
    }

    private void tick() {
        tick++;
        boolean spawnCheck = tick % SPAWN_CHECK_TICKS == 0;
        for (Npc npc : List.copyOf(npcs.values())) {
            if (npcs.get(npc.getId()) != npc) continue; // removed by an earlier NPC's event listener
            try {
                if (spawnCheck) npc.spawn();
                npc.tick(tick);
            } catch (Exception e) {
                plugin.getSLF4JLogger().error("Error ticking NPC '{}'", npc.getId(), e);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Creating and removing
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Creates an NPC and spawns it (if its chunk is loaded). Saved NPCs are written to disk right away.
     *
     * @param name  MiniMessage name
     * @param saved {@code false} for a temporary NPC that is never written to disk
     * @throws IllegalArgumentException if the id is invalid or taken
     */
    public Npc create(String id, Location location, String name, boolean saved) {
        if (!isValidId(id)) throw new IllegalArgumentException("Invalid NPC id '" + id + "' (use a-z, 0-9, _ and -, up to 32 characters)");
        if (npcs.containsKey(id)) throw new IllegalArgumentException("An NPC with id '" + id + "' already exists");
        if (location.getWorld() == null) throw new IllegalArgumentException("location must have a world");

        Npc npc = new Npc(this, id, location, name, saved);
        npcs.put(id, npc);
        npc.spawn();
        if (saved) save(npc);
        return npc;
    }

    /** Despawns and removes an NPC, deleting its config file if it has one. */
    public boolean remove(String id) {
        Npc npc = npcs.remove(id);
        if (npc == null) return false;
        npc.despawn();
        if (npc.isSaved() && !storage.delete(id)) {
            plugin.getSLF4JLogger().warn("Could not delete {}", storage.file(id));
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Saving and loading
    // ------------------------------------------------------------------------------------------------------------

    /** Writes a saved NPC to its config file. Does nothing for temporary NPCs. */
    public boolean save(Npc npc) {
        if (!npc.isSaved()) return true;
        try {
            storage.save(npc);
            return true;
        } catch (IOException e) {
            plugin.getSLF4JLogger().error("Failed to save NPC '{}'", npc.getId(), e);
            return false;
        }
    }

    /** Saves every saved NPC. Returns how many failed. */
    public int saveAll() {
        int failed = 0;
        for (Npc npc : npcs.values()) {
            if (!save(npc)) failed++;
        }
        return failed;
    }

    /**
     * Loads (or reloads) one NPC from its config file, replacing the one in memory.
     *
     * @throws IOException if the file is missing, unreadable, or in a world that isn't loaded
     * @throws IllegalArgumentException if a temporary NPC already uses this id
     */
    public Npc load(String id) throws IOException {
        if (!isValidId(id)) throw new IOException("Invalid NPC id '" + id + "'");
        if (!storage.file(id).isFile()) throw new IOException("There is no file npcs/" + id + ".yml");
        Npc existing = npcs.get(id);
        if (existing != null && !existing.isSaved()) {
            throw new IllegalArgumentException("A temporary NPC is already using id '" + id + "'");
        }

        NpcStorage.Data data = storage.load(id);
        if (existing != null) {
            existing.despawn();
            npcs.remove(id);
        }
        Npc npc = new Npc(this, id, data.location(), data.name(), true);
        data.applyTo(npc);
        for (Map<?, ?> entry : data.behaviors()) npc.addBehavior(behaviors.load(id, entry));
        npcs.put(id, npc);
        npc.spawn();
        return npc;
    }

    /**
     * Reloads every saved NPC from disk: removes saved NPCs that no longer have a file and loads every file.
     * Temporary NPCs are left alone.
     *
     * @return how many NPCs were loaded
     */
    public int loadAll() {
        List<String> ids = storage.listIds();
        for (Npc npc : List.copyOf(npcs.values())) {
            if (npc.isSaved() && !ids.contains(npc.getId())) {
                npc.despawn();
                npcs.remove(npc.getId());
            }
        }

        int loaded = 0;
        for (String id : ids) {
            try {
                load(id);
                loaded++;
            } catch (IOException | IllegalArgumentException e) {
                plugin.getSLF4JLogger().warn("Skipped NPC '{}': {}", id, e.getMessage());
            }
        }
        plugin.getSLF4JLogger().info("Loaded {} NPC(s)", loaded);
        return loaded;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Lookup
    // ------------------------------------------------------------------------------------------------------------

    public @Nullable Npc get(String id) {
        return npcs.get(id);
    }

    public Collection<Npc> getAll() {
        return Collections.unmodifiableCollection(npcs.values());
    }

    /** The NPC an entity belongs to, or {@code null} if it isn't the live entity of an NPC. */
    public @Nullable Npc get(Entity entity) {
        if (!(entity instanceof Mannequin)) return null;
        String id = entity.getPersistentDataContainer().get(npcKey, PersistentDataType.STRING);
        if (id == null) return null;
        Npc npc = npcs.get(id);
        return npc != null && entity.equals(npc.getEntity()) ? npc : null;
    }

    /** Whether the entity was spawned by the NPC system (even an old, no longer used one). */
    public boolean isNpcEntity(Entity entity) {
        return entity.getPersistentDataContainer().has(npcKey, PersistentDataType.STRING);
    }

    public static boolean isValidId(String id) {
        return ID.matcher(id).matches();
    }

    /** Behaviour types NPCs can use. Register new ones here before NPCs that use them are loaded. */
    public BehaviorRegistry behaviors() {
        return behaviors;
    }

    public MojangSkinFetcher skinFetcher() {
        return skinFetcher;
    }

    NpcPacketSender packets() {
        return packets;
    }

    NamespacedKey npcKey() {
        return npcKey;
    }
}
