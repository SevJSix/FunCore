package me.sevj6.funCore.tycoon.plot;

import me.sevj6.funCore.tycoon.core.template.Offset;
import me.sevj6.funCore.tycoon.core.template.Part;
import me.sevj6.funCore.tycoon.core.template.Vec;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Everything the plugin has added to one plot, in named groups (a component, a pad, the vault door), so any of it
 * can be taken away again and the plot returned to exactly how the map has it.
 *
 * <ul>
 *     <li>Entities (display parts, labels) are never saved with the world and carry the tycoon's PDC key.</li>
 *     <li>Real blocks (barriers, pressure plates) go only into air, and are only removed while they're still the
 *     block that was placed, so a wrong offset can never destroy part of the map. Each one is recorded in the
 *     {@link FixtureJournal} until it's removed.</li>
 * </ul>
 */
public final class Fixtures {

    private static final class Group {
        final List<Entity> entities = new ArrayList<>();
        final Map<Offset, Material> blocks = new LinkedHashMap<>();
    }

    private final int slot;
    private final Location origin;
    private final NamespacedKey key;
    private final FixtureJournal journal;
    private final Logger logger;
    private final Map<String, Group> groups = new LinkedHashMap<>();
    /** Block data that failed to parse, so each is only reported once. */
    private static final Set<String> badData = new HashSet<>();

    /** @param slot the plot's slot (0-based), for the journal */
    public Fixtures(int slot, Location origin, NamespacedKey key, FixtureJournal journal, Logger logger) {
        this.slot = slot;
        this.origin = origin.clone();
        this.key = key;
        this.journal = journal;
        this.logger = logger;
    }

    public Location at(Vec point) {
        return new Location(origin.getWorld(), origin.getX() + point.x(), origin.getY() + point.y(), origin.getZ() + point.z());
    }

    public Block block(Offset at) {
        return origin.getWorld().getBlockAt(origin.getBlockX() + at.x(), origin.getBlockY() + at.y(), origin.getBlockZ() + at.z());
    }

    public boolean has(String group) {
        return groups.containsKey(group);
    }

    /** How many entities the plot has (for admins). */
    public int entityCount() {
        int count = 0;
        for (Group group : groups.values()) count += group.entities.size();
        return count;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Adding
    // ------------------------------------------------------------------------------------------------------------

    /** Spawns display parts into {@code group}, returning their entities in the same order. */
    public List<Display> spawn(String group, List<Part> parts) {
        List<Display> spawned = new ArrayList<>(parts.size());
        for (Part part : parts) spawned.add(spawn(group, part));
        return spawned;
    }

    private Display spawn(String group, Part part) {
        World world = origin.getWorld();
        Location at = at(part.at());
        Vector3f scale = new Vector3f((float) part.size().x(), (float) part.size().y(), (float) part.size().z());
        Transformation transform = new Transformation(new Vector3f(), new AxisAngle4f(), scale, new AxisAngle4f());
        Display display;
        if (part.block()) {
            BlockData data = blockData(part.data());
            display = world.spawn(at, BlockDisplay.class, view -> {
                view.setBlock(data);
                view.setTransformation(transform);
                tag(view);
            });
        } else {
            Material material = Material.matchMaterial(part.data());
            ItemStack item = ItemStack.of(material != null && material.isItem() ? material : Material.BARRIER);
            display = world.spawn(at, ItemDisplay.class, view -> {
                view.setItemStack(item);
                view.setTransformation(transform);
                tag(view);
            });
        }
        groups.computeIfAbsent(group, name -> new Group()).entities.add(display);
        return display;
    }

    /** Spawns any other entity (a label, say) into {@code group}; it's set up as a tycoon entity first. */
    public <T extends Entity> T spawn(String group, Vec point, Class<T> type, Consumer<T> setup) {
        T entity = origin.getWorld().spawn(at(point), type, created -> {
            setup.accept(created);
            tag(created);
        });
        groups.computeIfAbsent(group, name -> new Group()).entities.add(entity);
        return entity;
    }

    /** A text label in {@code group}, facing whoever looks at it. */
    public TextDisplay label(String group, Vec point, net.kyori.adventure.text.Component text) {
        return spawn(group, point, TextDisplay.class, label -> {
            label.text(text);
            label.setBillboard(Display.Billboard.CENTER);
            label.setAlignment(TextDisplay.TextAlignment.CENTER);
            label.setShadowed(true);
        });
    }

    /** Takes an entity spawned some other way (a dropped item) into {@code group}, as a tycoon entity. */
    public void adopt(String group, Entity entity) {
        tag(entity);
        groups.computeIfAbsent(group, name -> new Group()).entities.add(entity);
    }

    private void tag(Entity entity) {
        entity.setPersistent(false);
        entity.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
    }

    /**
     * A part's block data. {@code @copy x y z} copies the map's block at that offset from the plot origin (so a part
     * can match each plot's own colours, like its border), anything else is ordinary block data.
     */
    private BlockData blockData(String data) {
        if (data.startsWith("@copy")) {
            String[] xyz = data.substring(5).trim().split("[\\s,]+");
            try {
                return block(new Offset(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]))).getBlockData().clone();
            } catch (RuntimeException e) {
                if (badData.add(data)) logger.warn("Tycoon: '{}' should be '@copy x y z'; showing stone instead", data);
                return Material.STONE.createBlockData();
            }
        }
        try {
            return Bukkit.createBlockData(data.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            if (badData.add(data)) logger.warn("Tycoon: '{}' isn't valid block data; showing stone instead", data);
            return Material.STONE.createBlockData();
        }
    }

    /**
     * Puts a real block into {@code group}, only where there's air.
     *
     * @return whether it was placed
     */
    public boolean place(String group, Offset at, Material material) {
        Block block = block(at);
        if (!block.getType().isAir()) {
            logger.warn("Tycoon: plot {} {} is {}, not air, so the {} for '{}' wasn't placed", slot + 1, at,
                    block.getType().getKey().getKey(), material.getKey().getKey(), group);
            return false;
        }
        journal.add(slot, at, material.name());
        block.setType(material, false);
        groups.computeIfAbsent(group, name -> new Group()).blocks.put(at, material);
        return true;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Removing
    // ------------------------------------------------------------------------------------------------------------

    /** Takes away everything in {@code group}. */
    public void remove(String group) {
        Group removed = groups.remove(group);
        if (removed != null) clear(removed);
    }

    /** Takes away every group whose name starts with {@code prefix}. */
    public void removeAll(String prefix) {
        List<String> names = new ArrayList<>(groups.keySet());
        for (String name : names) {
            if (name.startsWith(prefix)) remove(name);
        }
    }

    /** Takes away everything: the plot is as the map has it. */
    public void clear() {
        List<Group> all = new ArrayList<>(groups.values());
        groups.clear();
        all.forEach(this::clear);
    }

    private void clear(Group group) {
        group.entities.forEach(Entity::remove);
        group.blocks.forEach((at, material) -> {
            Block block = block(at);
            if (block.getType() == material) block.setType(Material.AIR, false);
            journal.remove(slot, at);
        });
    }

    /**
     * Removes blocks an earlier run recorded but didn't take away (a crash): each goes back to air if it's still the
     * block that was placed.
     */
    public static int sweep(Location origin, Map<Offset, String> recorded) {
        int removed = 0;
        for (Map.Entry<Offset, String> entry : new HashMap<>(recorded).entrySet()) {
            Offset at = entry.getKey();
            Block block = origin.getWorld().getBlockAt(origin.getBlockX() + at.x(), origin.getBlockY() + at.y(), origin.getBlockZ() + at.z());
            if (block.getType().name().equals(entry.getValue())) {
                block.setType(Material.AIR, false);
                removed++;
            }
        }
        return removed;
    }
}
