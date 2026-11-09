package me.sevj6.funCore.music.nms;

import me.sevj6.funCore.music.NoteSender;
import me.sevj6.funCore.music.Song;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends a tick's notes as sound packets, straight to the player's connection:
 * <ul>
 *     <li>Several notes go in one bundle packet, which the client always handles in a single frame, so a chord stays a
 *     chord even when the connection splits it up. It's also one write to the network instead of one per note.</li>
 *     <li>Vanilla sounds are sent by registry id rather than by name, which keeps the packets small.</li>
 *     <li>Unpanned notes "in the head" are attached to the player, so they follow them smoothly when they move.</li>
 * </ul>
 * Server internals are used only here (and in the other {@code nms} packages).
 */
public final class NmsNoteSender implements NoteSender {

    /** How far to the side a fully panned note is placed, in blocks. */
    private static final double PAN_DISTANCE = 2.0;
    /** The client's limit on packets in one bundle. */
    private static final int MAX_BUNDLE = 4096;

    private final Map<String, Holder<SoundEvent>> sounds = new HashMap<>();

    @Override
    public void sendToSelf(Player player, List<Song.Note> notes, float volume, SoundCategory category) {
        ServerPlayer handle = ((CraftPlayer) player).getHandle();
        SoundSource source = sourceOf(category);
        Location eye = player.getEyeLocation();
        double yaw = Math.toRadians(eye.getYaw());
        // The player's right-hand side, flat.
        double rightX = -Math.cos(yaw), rightZ = -Math.sin(yaw);
        List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>(notes.size());
        for (Song.Note note : notes) {
            Holder<SoundEvent> sound = sound(note.sound());
            if (sound == null) continue;
            float noteVolume = note.volume() * volume;
            if (noteVolume <= 0) continue;
            if (note.pan() == 0) {
                packets.add(new ClientboundSoundEntityPacket(sound, source, handle, noteVolume, note.pitch(), 0L));
            } else {
                double offset = note.pan() * PAN_DISTANCE;
                packets.add(new ClientboundSoundPacket(sound, source, eye.getX() + rightX * offset, eye.getY(),
                        eye.getZ() + rightZ * offset, noteVolume, note.pitch(), 0L));
            }
        }
        send(handle, packets);
    }

    @Override
    public void sendFrom(Player player, Location from, List<Song.Note> notes, float volume, SoundCategory category) {
        SoundSource source = sourceOf(category);
        List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>(notes.size());
        for (Song.Note note : notes) {
            Holder<SoundEvent> sound = sound(note.sound());
            if (sound == null) continue;
            float noteVolume = note.volume() * volume;
            if (noteVolume <= 0) continue;
            packets.add(new ClientboundSoundPacket(sound, source, from.getX(), from.getY(), from.getZ(), noteVolume, note.pitch(), 0L));
        }
        send(((CraftPlayer) player).getHandle(), packets);
    }

    private static void send(ServerPlayer player, List<Packet<? super ClientGamePacketListener>> packets) {
        if (packets.isEmpty()) return;
        if (packets.size() == 1) {
            player.connection.send(packets.getFirst());
            return;
        }
        for (int from = 0; from < packets.size(); from += MAX_BUNDLE) {
            List<Packet<? super ClientGamePacketListener>> part = packets.subList(from, Math.min(packets.size(), from + MAX_BUNDLE));
            player.connection.send(new ClientboundBundlePacket(List.copyOf(part)));
        }
    }

    /** The registered sound (sent as a number), or a sound by name (e.g. from a resource pack); cached. */
    private @Nullable Holder<SoundEvent> sound(String key) {
        Holder<SoundEvent> cached = sounds.get(key);
        if (cached != null || sounds.containsKey(key)) return cached;
        Identifier id = Identifier.tryParse(key);
        Holder<SoundEvent> holder = id == null ? null : BuiltInRegistries.SOUND_EVENT.get(id)
                .<Holder<SoundEvent>>map(reference -> reference)
                .orElseGet(() -> Holder.direct(SoundEvent.createVariableRangeEvent(id)));
        sounds.put(key, holder);
        return holder;
    }

    private static SoundSource sourceOf(SoundCategory category) {
        return SoundSource.valueOf(category.name());
    }
}
