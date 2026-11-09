package me.sevj6.funCore.arena;

import me.sevj6.funCore.util.Utils;
import org.bukkit.ChunkSnapshot;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Everything in an arena's region at one moment, so it can be put back after a match: every block, the data of block
 * entities (chest contents, sign text, ...) and the entities in it (mobs with their saddles and names, armour stands,
 * item frames, boats, ...).
 *
 * <p>Stored gzipped in {@code plugins/FunCore/arenas/<id>.blocks}: a palette of distinct block states plus one palette
 * index per block (ordered x first, then z, then y), then the block entities and entities as NBT.
 *
 * <p>Version 1 files (written before block entities and entities were saved) still load: they have no block entities,
 * and {@link #entities} is {@code null}, meaning entities aren't touched when restoring.
 */
final class ArenaSnapshot {

    private static final int MAGIC = 0x46434152; // "FCAR"
    private static final int VERSION = 2;

    /** A block entity's data ({@code BlockEntityData}), at an absolute block position. */
    record SavedBlockEntity(int x, int y, int z, byte[] data) {
    }

    /** An entity serialized by the server, and where it stood. */
    record SavedEntity(double x, double y, double z, float yaw, float pitch, byte[] data) {
    }

    final Region region;
    final String[] palette;
    final char[] blocks;
    /** Minecraft data version the NBT was written with; block entity data from another version isn't loaded. */
    final int dataVersion;
    final List<SavedBlockEntity> blockEntities;
    /** {@code null} for a version 1 snapshot, which didn't save entities. */
    final @Nullable List<SavedEntity> entities;

    private ArenaSnapshot(Region region, String[] palette, char[] blocks, int dataVersion,
                          List<SavedBlockEntity> blockEntities, @Nullable List<SavedEntity> entities) {
        if (blocks.length != region.volume()) throw new IllegalArgumentException("snapshot size doesn't match its region");
        this.region = region;
        this.palette = palette;
        this.blocks = blocks;
        this.dataVersion = dataVersion;
        this.blockEntities = List.copyOf(blockEntities);
        this.entities = entities == null ? null : List.copyOf(entities);
    }

    static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    /** Index of a block of the region in {@link #blocks}. */
    int indexOf(int x, int y, int z) {
        return (x - region.minX()) + (z - region.minZ()) * region.sizeX() + (y - region.minY()) * region.sizeX() * region.sizeZ();
    }

    /**
     * Builds a snapshot from chunk snapshots covering the region (keyed by {@link #chunkKey}), plus the block entities
     * and entities, which must be read on the main thread beforehand. Safe to call off the main thread.
     */
    static ArenaSnapshot capture(Region region, Map<Long, ChunkSnapshot> chunks, int dataVersion,
                                 List<SavedBlockEntity> blockEntities, List<SavedEntity> entities) {
        Map<BlockData, Integer> ids = new HashMap<>();
        List<String> palette = new ArrayList<>();
        char[] blocks = new char[(int) region.volume()];
        int i = 0;
        for (int y = region.minY(); y <= region.maxY(); y++) {
            for (int z = region.minZ(); z <= region.maxZ(); z++) {
                for (int x = region.minX(); x <= region.maxX(); x++) {
                    ChunkSnapshot chunk = chunks.get(chunkKey(x >> 4, z >> 4));
                    BlockData data = chunk.getBlockData(x & 15, y, z & 15);
                    Integer id = ids.get(data);
                    if (id == null) {
                        if (palette.size() >= Character.MAX_VALUE) throw new IllegalStateException("too many different blocks");
                        id = palette.size();
                        ids.put(data, id);
                        palette.add(data.getAsString());
                    }
                    blocks[i++] = (char) id.intValue();
                }
            }
        }
        return new ArenaSnapshot(region, palette.toArray(String[]::new), blocks, dataVersion, blockEntities, entities);
    }

    void write(File file) throws IOException {
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(temp))))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            for (int value : region.toList()) out.writeInt(value);
            out.writeInt(palette.length);
            for (String state : palette) out.writeUTF(state);
            ByteBuffer buffer = ByteBuffer.allocate(blocks.length * 2);
            buffer.asCharBuffer().put(blocks);
            out.write(buffer.array());

            out.writeInt(dataVersion);
            out.writeInt(blockEntities.size());
            for (SavedBlockEntity entity : blockEntities) {
                out.writeInt(entity.x());
                out.writeInt(entity.y());
                out.writeInt(entity.z());
                writeBytes(out, entity.data());
            }
            List<SavedEntity> saved = entities == null ? List.of() : entities;
            out.writeInt(saved.size());
            for (SavedEntity entity : saved) {
                out.writeDouble(entity.x());
                out.writeDouble(entity.y());
                out.writeDouble(entity.z());
                out.writeFloat(entity.yaw());
                out.writeFloat(entity.pitch());
                writeBytes(out, entity.data());
            }
        }
        Utils.moveReplacing(temp, file);
    }

    static ArenaSnapshot read(File file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(file))))) {
            if (in.readInt() != MAGIC) throw new IOException(file.getName() + " is not an arena snapshot");
            int version = in.readInt();
            if (version < 1 || version > VERSION) throw new IOException(file.getName() + " has unknown version " + version);
            Region region = new Region(in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt());
            String[] palette = new String[in.readInt()];
            for (int i = 0; i < palette.length; i++) palette[i] = in.readUTF();
            byte[] bytes = new byte[Math.toIntExact(region.volume() * 2)];
            in.readFully(bytes);
            char[] blocks = new char[bytes.length / 2];
            ByteBuffer.wrap(bytes).asCharBuffer().get(blocks);
            for (char id : blocks) {
                if (id >= palette.length) throw new IOException(file.getName() + " is corrupt");
            }
            if (version == 1) return new ArenaSnapshot(region, palette, blocks, 0, List.of(), null);

            int dataVersion = in.readInt();
            int blockEntityCount = in.readInt();
            List<SavedBlockEntity> blockEntities = new ArrayList<>(blockEntityCount);
            for (int i = 0; i < blockEntityCount; i++) {
                blockEntities.add(new SavedBlockEntity(in.readInt(), in.readInt(), in.readInt(), readBytes(in)));
            }
            int entityCount = in.readInt();
            List<SavedEntity> entities = new ArrayList<>(entityCount);
            for (int i = 0; i < entityCount; i++) {
                entities.add(new SavedEntity(in.readDouble(), in.readDouble(), in.readDouble(), in.readFloat(), in.readFloat(), readBytes(in)));
            }
            return new ArenaSnapshot(region, palette, blocks, dataVersion, blockEntities, entities);
        }
    }

    private static void writeBytes(DataOutputStream out, byte[] data) throws IOException {
        out.writeInt(data.length);
        out.write(data);
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > 16 * 1024 * 1024) throw new IOException("corrupt snapshot: entry of " + length + " bytes");
        byte[] data = new byte[length];
        in.readFully(data);
        return data;
    }
}
