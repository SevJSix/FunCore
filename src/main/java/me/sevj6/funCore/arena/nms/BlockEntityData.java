package me.sevj6.funCore.arena.nms;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.CraftWorld;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Saves and loads the data of a block entity (chest and barrel contents, sign text, banner patterns, spawner settings,
 * ...), which the Bukkit API can't serialize. Uses server internals; this is the only place arenas touch them.
 */
public final class BlockEntityData {

    private BlockEntityData() {
    }

    /** The block's block entity data as NBT bytes, or {@code null} if it has none. Main thread only. */
    public static byte @Nullable [] save(Block block) {
        ServerLevel level = level(block);
        BlockEntity entity = level.getBlockEntity(pos(block));
        if (entity == null) return null;
        CompoundTag tag = entity.saveWithFullMetadata(level.registryAccess());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            NbtIo.write(tag, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /**
     * Loads data written by {@link #save} into the block's block entity, replacing what it holds (a chest's items are
     * all replaced, not merged). Main thread only.
     *
     * @return {@code false} if the block has no block entity (e.g. it isn't the block the data came from)
     */
    public static boolean load(Block block, byte[] data) {
        ServerLevel level = level(block);
        BlockPos pos = pos(block);
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity == null) return false;
        CompoundTag tag;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            tag = NbtIo.read(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        entity.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
        entity.setChanged();
        BlockState state = level.getBlockState(pos);
        level.sendBlockUpdated(pos, state, state, 3);
        return true;
    }

    private static ServerLevel level(Block block) {
        return ((CraftWorld) block.getWorld()).getHandle();
    }

    private static BlockPos pos(Block block) {
        return new BlockPos(block.getX(), block.getY(), block.getZ());
    }
}
