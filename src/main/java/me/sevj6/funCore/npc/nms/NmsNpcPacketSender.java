package me.sevj6.funCore.npc.nms;

import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/** {@link NpcPacketSender} that uses Minecraft's own packet classes (through the paperweight dev bundle). */
public final class NmsNpcPacketSender implements NpcPacketSender {

    @Override
    public void sendRotation(Player viewer, Entity entity, float yaw, float pitch) {
        ServerPlayer player = ((CraftPlayer) viewer).getHandle();
        net.minecraft.world.entity.Entity handle = ((CraftEntity) entity).getHandle();

        byte packedYaw = Mth.packDegrees(yaw);
        byte packedPitch = Mth.packDegrees(pitch);

        // The head packet turns the head; the rotation packet turns the body (and sets pitch).
        player.connection.send(new ClientboundRotateHeadPacket(handle, packedYaw));
        player.connection.send(new ClientboundMoveEntityPacket.Rot(handle.getId(), packedYaw, packedPitch, handle.onGround()));
    }
}
