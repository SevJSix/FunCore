package me.sevj6.funCore.npc.behavior;

import me.sevj6.funCore.npc.Npc;
import me.sevj6.funCore.npc.event.NpcInteractEvent;
import me.sevj6.funCore.npc.event.NpcProximityEnterEvent;
import me.sevj6.funCore.npc.event.NpcProximityLeaveEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Runs each NPC's {@link NpcBehavior}s when its events fire. A click that another listener cancelled
 * ({@link NpcInteractEvent#setCancelled}) is skipped, so code can take over an NPC's clicks when it needs to.
 */
public final class NpcBehaviorListener implements Listener {

    /** Clicks closer together than this are ignored, so spam-clicking can't run a command ten times a second. */
    private static final long CLICK_COOLDOWN_MILLIS = 300;

    private final Logger logger;
    private final Map<UUID, Long> lastClick = new HashMap<>();

    public NpcBehaviorListener(Logger logger) {
        this.logger = logger;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(NpcInteractEvent event) {
        long now = System.currentTimeMillis();
        Long last = lastClick.put(event.getPlayer().getUniqueId(), now);
        if (last != null && now - last < CLICK_COOLDOWN_MILLIS) return;
        run(event.getNpc(), event.getPlayer(), behavior -> behavior.onInteract(event.getNpc(), event.getPlayer(), event.getClick()));
    }

    @EventHandler
    public void onProximityEnter(NpcProximityEnterEvent event) {
        run(event.getNpc(), event.getPlayer(), behavior -> behavior.onProximityEnter(event.getNpc(), event.getPlayer()));
    }

    @EventHandler
    public void onProximityLeave(NpcProximityLeaveEvent event) {
        run(event.getNpc(), event.getPlayer(), behavior -> behavior.onProximityLeave(event.getNpc(), event.getPlayer(), event.getReason()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastClick.remove(event.getPlayer().getUniqueId());
    }

    /** Runs a hook on every behaviour; one failing doesn't stop the rest. */
    private void run(Npc npc, Player player, Consumer<NpcBehavior> hook) {
        for (NpcBehavior behavior : List.copyOf(npc.getBehaviors())) {
            try {
                hook.accept(behavior);
            } catch (Exception e) {
                logger.error("NPC '{}' {} behavior failed for {}", npc.getId(), behavior.type(), player.getName(), e);
            }
        }
    }
}
