package me.sevj6.funCore.npc.event;

import me.sevj6.funCore.npc.Npc;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

/**
 * A player who was within an NPC's proximity range no longer is. This always follows a matching
 * {@link NpcProximityEnterEvent}.
 */
public final class NpcProximityLeaveEvent extends NpcEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    public enum Reason {
        /** The player moved (or teleported) out of range, or switched to spectator mode. */
        OUT_OF_RANGE,
        /** The player went to another world. */
        CHANGED_WORLD,
        /** The player disconnected. */
        QUIT,
        /** The NPC despawned (its chunk unloaded, it was removed, or the plugin is shutting down). */
        NPC_DESPAWNED
    }

    private final Player player;
    private final Reason reason;

    public NpcProximityLeaveEvent(Npc npc, Player player, Reason reason) {
        super(npc);
        this.player = player;
        this.reason = reason;
    }

    public Player getPlayer() {
        return player;
    }

    public Reason getReason() {
        return reason;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
