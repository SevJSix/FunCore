package me.sevj6.funCore.tycoon;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * The tycoon world's rules, over the whole map: nobody builds or breaks anything (admins in edit mode excepted, to fix
 * the map), decorative blocks like the hoppers can't be opened or used, pressure plates never press (buy pads are
 * handled by the plugin, not by redstone), nothing is dropped or picked up, and nobody gets hurt. The map doesn't
 * change by itself either: water doesn't flow (the river reaches the map's edges, and would pour off into the void),
 * and grass, leaves and the like stay as they are. Falling off the map puts you back on your plot, or at the hub.
 */
final class TycoonProtection implements Listener {

    /** How far below the plots a falling player is caught. */
    private static final int CATCH_BELOW = 24;

    private final TycoonManager tycoon;

    TycoonProtection(TycoonManager tycoon) {
        this.tycoon = tycoon;
    }

    private boolean inTycoon(Player player) {
        return tycoon.isTycoonWorld(player.getWorld());
    }

    /** In the tycoon world and not an admin in edit mode: the rules apply. */
    private boolean protects(Player player) {
        return inTycoon(player) && !tycoon.isEditing(player);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (protects(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (protects(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (protects(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (protects(event.getPlayer())) event.setCancelled(true);
    }

    /** Blocks are decoration: chests, droppers, furnaces, doors and levers don't open or work; pressure plates don't press. */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        if (!protects(event.getPlayer()) || event.getClickedBlock() == null) return;
        if (event.getAction() == Action.PHYSICAL) {
            event.setCancelled(true);
        } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            event.setUseInteractedBlock(Event.Result.DENY);
        }
    }

    /** Nothing but a player's step (handled by the plugin) presses a plate: not a fallen star, not a mob. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityPress(EntityInteractEvent event) {
        if (tycoon.isTycoonWorld(event.getBlock().getWorld())) event.setCancelled(true);
    }

    /** The map's hoppers are decoration: they don't take items (like fallen stars). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHopperPickup(InventoryPickupItemEvent event) {
        if (tycoon.isTycoonWorld(event.getItem().getWorld())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (protects(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (protects(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        if (tycoon.isTycoonWorld(event.getEntity().getWorld())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (protects(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && protects(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (tycoon.isTycoonWorld(event.getEntity().getWorld())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && inTycoon(player)) event.setCancelled(true);
    }

    // ------------------------------------------------------------------------------------------------------------
    // The map stays as pasted
    // ------------------------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        if (tycoon.isTycoonWorld(event.getBlock().getWorld())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (tycoon.isTycoonWorld(event.getBlock().getWorld())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        if (tycoon.isTycoonWorld(event.getBlock().getWorld())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        if (tycoon.isTycoonWorld(event.getBlock().getWorld())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent event) {
        if (tycoon.isTycoonWorld(event.getBlock().getWorld())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent event) {
        if (tycoon.isTycoonWorld(event.getBlock().getWorld())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (tycoon.isTycoonWorld(event.getBlock().getWorld())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (!tycoon.isTycoonWorld(event.getBlock().getWorld())) return;
        if (!(event.getPlayer() != null && tycoon.isEditing(event.getPlayer()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTrample(EntityChangeBlockEvent event) {
        if (!tycoon.isTycoonWorld(event.getBlock().getWorld())) return;
        if (!(event.getEntity() instanceof Player player && tycoon.isEditing(player))) event.setCancelled(true);
    }

    /** Falling off the map into the void: back to your own plot, or the hub without one. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock() || !inTycoon(event.getPlayer())) return;
        Double catchBelow = tycoon.catchBelowY();
        if (catchBelow == null || event.getTo().getY() >= catchBelow) return;
        PlotSession session = tycoon.session(event.getPlayer().getUniqueId());
        event.setTo(session != null ? session.spawn() : tycoon.hub());
        event.getPlayer().setFallDistance(0);
    }
}
