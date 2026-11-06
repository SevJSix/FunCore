package me.sevj6.funCore.npc.event;

import me.sevj6.funCore.npc.Npc;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

/** A player came within an NPC's proximity range. Players in spectator mode are ignored. */
public final class NpcProximityEnterEvent extends NpcEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;

    public NpcProximityEnterEvent(Npc npc, Player player) {
        super(npc);
        this.player = player;
    }

    public Player getPlayer() {
        return player;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
