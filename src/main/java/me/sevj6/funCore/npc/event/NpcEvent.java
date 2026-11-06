package me.sevj6.funCore.npc.event;

import me.sevj6.funCore.npc.Npc;
import org.bukkit.event.Event;

/** Base class for every NPC event. Listen to the subclasses; this one can't be listened to directly. */
public abstract class NpcEvent extends Event {

    private final Npc npc;

    protected NpcEvent(Npc npc) {
        this.npc = npc;
    }

    public Npc getNpc() {
        return npc;
    }
}
