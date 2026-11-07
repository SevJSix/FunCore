package me.sevj6.funCore.arena;

import me.sevj6.funCore.util.Utils;
import org.bukkit.ChunkSnapshot;
import org.bukkit.block.data.BlockData;

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
 * Every block in an arena's region at one moment, so it can be put back after a match.
 *
 * <p>Stored as a palette of distinct block states plus one palette index per block, gzipped, in
 * {@code plugins/FunCore/arenas/<id>.blocks}. Blocks are ordered x first, then z, then y.
 *
 * <p>Only block states are kept (type, facing, waterlogged, ...), not block entity contents such as chest items or
 * sign text.
 */
final class ArenaSnapshot {

    private static final int MAGIC = 0x46434152; // "FCAR"
    private static final int VERSION = 1;

    final Region region;
    final String[] palette;
    final char[] blocks;

    private ArenaSnapshot(Region region, String[] palette, char[] blocks) {
        if (blocks.length != region.volume()) throw new IllegalArgumentException("snapshot size doesn't match its region");
        this.region = region;
        this.palette = palette;
        this.blocks = blocks;
    }

    static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    /**
     * Builds a snapshot from chunk snapshots covering the region (keyed by {@link #chunkKey}). Safe to call off the
     * main thread.
     */
    static ArenaSnapshot capture(Region region, Map<Long, ChunkSnapshot> chunks) {
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
        return new ArenaSnapshot(region, palette.toArray(String[]::new), blocks);
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
        }
        Utils.moveReplacing(temp, file);
    }

    static ArenaSnapshot read(File file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(file))))) {
            if (in.readInt() != MAGIC) throw new IOException(file.getName() + " is not an arena snapshot");
            int version = in.readInt();
            if (version != VERSION) throw new IOException(file.getName() + " has unknown version " + version);
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
            return new ArenaSnapshot(region, palette, blocks);
        }
    }
}
