package me.sevj6.funCore.npc;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import me.sevj6.funCore.npc.event.NpcInteractEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Turns clicks on NPCs into {@link NpcInteractEvent}s and keeps NPC entities from being hurt or changed. */
public final class NpcListener implements Listener {

    private final NpcManager manager;
    /** The client can send two packets for one right click; this makes sure only one event fires per tick. */
    private final Map<UUID, Integer> lastRightClickTick = new HashMap<>();

    public NpcListener(NpcManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onRightClick(PlayerInteractEntityEvent event) {
        handleRightClick(event.getPlayer(), event.getRightClicked(), event.getHand(), event);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onRightClickAt(PlayerInteractAtEntityEvent event) {
        handleRightClick(event.getPlayer(), event.getRightClicked(), event.getHand(), event);
    }

    private void handleRightClick(Player player, Entity clicked, EquipmentSlot hand, PlayerInteractEntityEvent event) {
        Npc npc = manager.get(clicked);
        if (npc == null) return;
        event.setCancelled(true);
        if (hand != EquipmentSlot.HAND) return;

        int now = Bukkit.getCurrentTick();
        Integer last = lastRightClickTick.put(player.getUniqueId(), now);
        if (last != null && last == now) return;
        Bukkit.getPluginManager().callEvent(new NpcInteractEvent(npc, player, NpcInteractEvent.Click.RIGHT));
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onLeftClick(PrePlayerAttackEntityEvent event) {
        Npc npc = manager.get(event.getAttacked());
        if (npc == null) return;
        event.setCancelled(true);
        Bukkit.getPluginManager().callEvent(new NpcInteractEvent(npc, event.getPlayer(), NpcInteractEvent.Click.LEFT));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent event) {
        if (manager.isNpcEntity(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCombust(EntityCombustEvent event) {
        if (manager.isNpcEntity(event.getEntity())) event.setCancelled(true);
    }

    /** NPC entities are never saved, so one being loaded from disk is a leftover (e.g. after a crash). */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (manager.isNpcEntity(entity) && manager.get(entity) == null) entity.remove();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        lastRightClickTick.remove(event.getPlayer().getUniqueId());
        for (Npc npc : List.copyOf(manager.getAll())) npc.handleQuit(event.getPlayer());
    }
}
