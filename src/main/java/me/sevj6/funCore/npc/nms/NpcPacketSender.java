package me.sevj6.funCore.npc.nms;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * Sends NPC packets to individual players, for effects the Bukkit API can only apply to everyone at once.
 *
 * <p>This is the only place the NPC system relies on server internals. Keeping it behind an interface means a
 * Minecraft update can only break the implementation, and it could be swapped for something like PacketEvents.
 */
public interface NpcPacketSender {

    /** Makes {@code entity}'s head and body face the given rotation, for {@code viewer} only. */
    void sendRotation(Player viewer, Entity entity, float yaw, float pitch);
}
