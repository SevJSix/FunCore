package me.sevj6.funCore.tycoon.plot;

import me.sevj6.funCore.tycoon.core.sim.Item;
import me.sevj6.funCore.tycoon.core.sim.Segment;
import me.sevj6.funCore.tycoon.core.template.Component;
import me.sevj6.funCore.tycoon.core.template.Placeholders;
import me.sevj6.funCore.tycoon.core.template.Vec;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Shows a plot's items as display entities that slide along the belts. Each view is only a picture of an
 * {@link Item}: it's created when the item is (if the plot is under its visible-item cap and the spout's chunk is
 * loaded), moved once per segment with teleport interpolation so the client animates the slide, and removed when the
 * item is cashed in. An item without a view is still simulated and paid.
 *
 * <p>Views are never saved with the world ({@code persistent = false}) and carry {@link #key} so leftovers from a
 * crash can be found and removed.
 */
public final class ItemViews {

    private final NamespacedKey key;
    private final Location origin;
    private final int cap;
    private int shown;

    public ItemViews(NamespacedKey key, Location origin, int cap) {
        this.key = key;
        this.origin = origin;
        this.cap = cap;
    }

    /** The PDC key every view carries. */
    public NamespacedKey key() {
        return key;
    }

    public int shown() {
        return shown;
    }

    /** Gives a newly made item a view at its spout, if it can have one. */
    public void spawn(Item item) {
        Component.Dropper dropper = item.dropper();
        Location at = at(dropper.spout().center());
        World world = origin.getWorld();
        if (!world.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) return;
        // The dropper's puff and click, even when the item itself is over the visible cap.
        world.spawnParticle(Particle.SMOKE, at, 3, 0.15, 0.1, 0.15, 0.01);
        world.playSound(at, "block.dispenser.dispense", SoundCategory.BLOCKS, 0.25f, 1.4f);
        if (shown >= cap) return;
        Material material = Material.matchMaterial(dropper.item().material());
        if (material == null) material = Material.COAL;
        float scale = (float) dropper.item().scale();
        Display display;
        if (dropper.item().block() && material.isBlock()) {
            Material blockType = material;
            display = world.spawn(at, BlockDisplay.class, view -> {
                view.setBlock(blockType.createBlockData());
                // A block display's corner sits at its position; centre it, bottom on the belt.
                view.setTransformation(transform(-scale / 2, 0, -scale / 2, scale));
                setUp(view, item);
            });
        } else {
            ItemStack stack = ItemStack.of(material.isItem() ? material : Material.COAL);
            display = world.spawn(at, ItemDisplay.class, view -> {
                view.setItemStack(stack);
                // An item display is centred on its position; lift it so it sits on the belt.
                view.setTransformation(transform(0, scale / 2, 0, scale));
                setUp(view, item);
            });
        }
        item.view = display;
        shown++;
    }

    private void setUp(Display view, Item item) {
        view.setPersistent(false);
        view.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        // Ready for its first slide (see move).
        view.setTeleportDuration(duration(item.upcomingSegment()));
    }

    private static int duration(@Nullable Segment segment) {
        return segment == null ? 0 : Math.min(59, Math.max(0, segment.ticks()));
    }

    private static Transformation transform(float x, float y, float z, float scale) {
        return new Transformation(new Vector3f(x, y, z), new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f());
    }

    /**
     * Starts the view sliding along a segment; the client interpolates the whole way.
     *
     * <p>The server sends an entity's new position before its changed data in the same tick, so a teleport is
     * animated with the duration the client <i>already has</i>. Each view's duration is therefore set one segment
     * ahead: at spawn for the first slide, and after each teleport for the next one.
     */
    public void move(Item item, Segment segment) {
        Display view = view(item);
        if (view == null) return;
        view.teleport(at(segment.to()));
        view.setTeleportDuration(duration(item.upcomingSegment()));
    }

    /** Changes what an item looks like (an upgrader's swap). */
    public void swap(Item item, String materialName) {
        Display view = view(item);
        Material material = Material.matchMaterial(materialName.toUpperCase(Locale.ROOT));
        if (view == null || material == null) return;
        if (view instanceof ItemDisplay itemView && material.isItem()) itemView.setItemStack(ItemStack.of(material));
        else if (view instanceof BlockDisplay blockView && material.isBlock()) blockView.setBlock(material.createBlockData());
    }

    /** Removes an item's view (it was cashed in or thrown away). */
    public void remove(Item item) {
        if (item.view instanceof Entity entity) {
            entity.remove();
            shown--;
        }
        item.view = null;
    }

    /** The item's view if it still exists (it's gone if its chunk unloaded; the item carries on as data). */
    private @Nullable Display view(Item item) {
        if (!(item.view instanceof Display display)) return null;
        if (display.isValid()) return display;
        item.view = null;
        shown--;
        return null;
    }

    /** A point on the plot, raised so items ride on top of a belt's surface rather than inside it. */
    public Location at(Vec point) {
        return new Location(origin.getWorld(), origin.getX() + point.x(), origin.getY() + point.y() + Placeholders.BELT_SURFACE,
                origin.getZ() + point.z());
    }
}
