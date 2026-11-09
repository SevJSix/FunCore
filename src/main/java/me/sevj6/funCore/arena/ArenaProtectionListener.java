package me.sevj6.funCore.arena;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * Keeps arenas the way they were built, unless an arena allows otherwise:
 * <ul>
 *     <li><b>Mobs</b> ({@link Arena#isMobSpawningAllowed()}, off by default): no mobs spawn in the region. Plugin spawns
 *     ({@link CreatureSpawnEvent.SpawnReason#CUSTOM}) are left alone, so NPCs and a game's own mobs still work.</li>
 *     <li><b>Environment</b> ({@link Arena#isEnvironmentAllowed()}, off by default): no fire spreading, burning or
 *     starting by itself (lightning, lava), no snow or ice forming or melting, no grass, vine or mushroom spread, no crop
 *     or plant growth and no leaf decay. Fire players light still burns out normally.</li>
 *     <li><b>Fluids</b> ({@link Arena#isFluidFlowAllowed()}, off by default): water and lava don't flow, so a placed
 *     bucket stays one source block.</li>
 *     <li><b>Fall damage</b> ({@link Arena#isFallDamageAllowed()}, on by default): when off, falling doesn't hurt
 *     anyone in the region.</li>
 * </ul>
 */
public final class ArenaProtectionListener implements Listener {

    private final ArenaManager arenas;

    public ArenaProtectionListener(ArenaManager arenas) {
        this.arenas = arenas;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Mobs
    // ------------------------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM) return;
        Arena arena = arenas.getArenaAt(event.getLocation());
        if (arena != null && !arena.isMobSpawningAllowed()) event.setCancelled(true);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Environment
    // ------------------------------------------------------------------------------------------------------------

    /** Blocks burning away. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        protect(event.getBlock(), event);
    }

    /** Fire starting by itself (spread, lava, lightning). A player lighting it is the game's business. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (event.getPlayer() == null) protect(event.getBlock(), event);
    }

    /** Snow and ice forming (snowfall, frost), and concrete powder hardening. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        protect(event.getBlock(), event);
    }

    /** Snow and ice melting, coral dying, farmland drying. Fire still burns out, so it can't burn forever. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        Material type = event.getBlock().getType();
        if (type == Material.FIRE || type == Material.SOUL_FIRE) return;
        protect(event.getBlock(), event);
    }

    /** Grass, mycelium, vines, mushrooms and fire spreading. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        protect(event.getBlock(), event);
    }

    /** Crops, sugar cane, cactus, melons and pumpkins growing. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent event) {
        protect(event.getBlock(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent event) {
        protect(event.getBlock(), event);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Fluids
    // ------------------------------------------------------------------------------------------------------------

    /** Water and lava spreading (into the arena, or within it). The dragon egg teleporting also fires this. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        Arena arena = arenas.getArenaAt(event.getToBlock().getLocation());
        if (arena == null) arena = arenas.getArenaAt(event.getBlock().getLocation());
        if (arena != null && !arena.isFluidFlowAllowed()) event.setCancelled(true);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Fall damage
    // ------------------------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFall(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        Arena arena = arenas.getArenaAt(event.getEntity().getLocation());
        if (arena != null && !arena.isFallDamageAllowed()) event.setCancelled(true);
    }

    private void protect(Block block, Cancellable event) {
        Arena arena = arenas.getArenaAt(block.getLocation());
        if (arena != null && !arena.isEnvironmentAllowed()) event.setCancelled(true);
    }
}
