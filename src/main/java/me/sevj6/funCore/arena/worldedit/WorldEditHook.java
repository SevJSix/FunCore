package me.sevj6.funCore.arena.worldedit;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.selector.CuboidRegionSelector;
import com.sk89q.worldedit.world.World;
import me.sevj6.funCore.arena.BlockPos;
import me.sevj6.funCore.arena.Region;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * The bridge to WorldEdit (or FastAsyncWorldEdit, which provides the same API). Lets arenas be set from a wand
 * selection and shown as one.
 *
 * <p>This is the only class that touches WorldEdit classes. Only use it when {@link #isAvailable()} is true, otherwise
 * loading it fails with {@link NoClassDefFoundError}.
 */
public final class WorldEditHook {

    private WorldEditHook() {
    }

    /** Whether WorldEdit or FastAsyncWorldEdit is installed and enabled. Safe to call without either. */
    public static boolean isAvailable() {
        var plugins = Bukkit.getPluginManager();
        return plugins.isPluginEnabled("FastAsyncWorldEdit") || plugins.isPluginEnabled("WorldEdit");
    }

    /**
     * A player's current selection.
     *
     * @param worldName world the selection is in
     * @param cuboid    whether it's a plain cuboid; other shapes (polygons, spheres, ...) are reduced to the box around them
     */
    public record Selection(String worldName, BlockPos min, BlockPos max, boolean cuboid) {
    }

    /**
     * The player's selection (e.g. made with the wand, {@code //wand}).
     *
     * @throws IllegalStateException if they haven't selected anything, or only one corner
     */
    public static Selection getSelection(Player player) {
        LocalSession session = session(player);
        World world = session.getSelectionWorld();
        if (world == null) throw new IllegalStateException("You haven't selected anything. Use //wand, then left- and right-click two corners.");
        com.sk89q.worldedit.regions.Region region;
        try {
            region = session.getSelection(world);
        } catch (IncompleteRegionException e) {
            throw new IllegalStateException("Your selection is incomplete. Select both corners with the wand.");
        }
        BlockVector3 min = region.getMinimumPoint(), max = region.getMaximumPoint();
        return new Selection(world.getName(), new BlockPos(min.x(), min.y(), min.z()), new BlockPos(max.x(), max.y(), max.z()),
                region instanceof CuboidRegion);
    }

    /** Replaces the player's selection with a cuboid region, so they can see it with their WorldEdit CUI and edit it. */
    public static void select(Player player, org.bukkit.World bukkitWorld, Region region) {
        LocalSession session = session(player);
        World world = BukkitAdapter.adapt(bukkitWorld);
        CuboidRegionSelector selector = new CuboidRegionSelector(world,
                BlockVector3.at(region.minX(), region.minY(), region.minZ()),
                BlockVector3.at(region.maxX(), region.maxY(), region.maxZ()));
        session.setRegionSelector(world, selector);
        session.dispatchCUISelection(BukkitAdapter.adapt(player));
    }

    private static LocalSession session(Player player) {
        return WorldEdit.getInstance().getSessionManager().get(BukkitAdapter.adapt(player));
    }
}
