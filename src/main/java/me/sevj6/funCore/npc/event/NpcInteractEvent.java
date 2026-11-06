package me.sevj6.funCore.npc.event;

import me.sevj6.funCore.npc.Npc;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;

/**
 * A player clicked an NPC. This is the entry point for anything an NPC can do when clicked (dialogue, menus, vendors,
 * ...). The vanilla interaction or attack is always cancelled; cancelling this event tells later listeners (registered
 * with {@code ignoreCancelled = true}) that the click was already handled.
 */
public final class NpcInteractEvent extends NpcEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    public enum Click {
        /** Attack / left click. */
        LEFT,
        /** Use / right click. */
        RIGHT
    }

    private final Player player;
    private final Click click;
    private boolean cancelled;

    public NpcInteractEvent(Npc npc, Player player, Click click) {
        super(npc);
        this.player = player;
        this.click = click;
    }

    public Player getPlayer() {
        return player;
    }

    public Click getClick() {
        return click;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
