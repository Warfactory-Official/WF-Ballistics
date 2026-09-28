package com.wf.wflib.round.terrain;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Anvil chunk reads beside the server's own {@code RegionFileStorage}. Channel opened {@code READ} only: vanilla's
 * {@code RegionFile} ctor opens {@code CREATE, WRITE} (creates missing files) and {@code close()} pads to a sector
 * and forces => concurrent writes into a file the IO worker owns.
 */
public final class RegionReader {

    private static final int SECTOR = 4096;
    private static final int EXTERNAL = 0x80;

    private RegionReader() {
    }

    public static Path directory(ServerLevel level) {
        return DimensionType.getStorageFolder(level.dimension(), level.getServer().getWorldPath(LevelResource.ROOT))
                .resolve("region");
    }

    public static Path file(Path regionDir, int cx, int cz) {
        return regionDir.resolve("r." + (cx >> 5) + "." + (cz >> 5) + ".mca");
    }

    /** Chunk NBT as last written; null = no region file or chunk never saved. */
    @Nullable
    public static CompoundTag read(Path regionDir, int cx, int cz) throws IOException {
        FileChannel channel;
        try {
            channel = FileChannel.open(file(regionDir, cx, cz), StandardOpenOption.READ);
        } catch (NoSuchFileException e) {
            return null;
        }
        try (channel) {
            ByteBuffer entry = ByteBuffer.allocate(4);
            long index = 4L * ((cx & 31) + (cz & 31) * 32);
            if (channel.size() < index + 4) {
                return null;
            }
            read(channel, entry, index);
            int packed = entry.getInt(0);
            if (packed == 0) {
                return null;
            }
            ByteBuffer data = ByteBuffer.allocate((packed & 0xFF) * SECTOR);
            // Last chunk's sectors unpadded until the writer closes => short read, bounded by the length check.
            read(channel, data, (long) (packed >>> 8) * SECTOR);
            data.flip();
            if (data.remaining() < 5) {
                throw new IOException("chunk " + cx + "," + cz + ": header truncated");
            }
            int length = data.getInt();
            int version = data.get() & 0xFF;
            InputStream raw;
            if ((version & EXTERNAL) != 0) {
                raw = Files.newInputStream(regionDir.resolve("c." + cx + "." + cz + ".mcc"));
                version &= ~EXTERNAL;
            } else if (length < 1 || length - 1 > data.remaining()) {
                throw new IOException("chunk " + cx + "," + cz + ": stream length " + length);
            } else {
                raw = new ByteArrayInputStream(data.array(), data.position(), length - 1);
            }
            RegionFileVersion codec = RegionFileVersion.fromId(version);
            if (codec == null || codec == RegionFileVersion.VERSION_CUSTOM) {
                raw.close();
                throw new IOException("chunk " + cx + "," + cz + ": compression " + version);
            }
            try (DataInputStream in = new DataInputStream(codec.wrap(raw))) {
                return NbtIo.read(in);
            }
        }
    }

    /**
     * Throws unless {@code chunk} is the NBT of chunk {@code cx,cz}: a slot's sectors freed by the IO worker and reused
     * by another chunk between header and data reads decode as that chunk. Pre-1.18 layout (position under
     * {@code Level}) passes.
     */
    public static CompoundTag requirePosition(CompoundTag chunk, int cx, int cz) throws IOException {
        if (!chunk.contains("Level") && (chunk.getInt("xPos") != cx || chunk.getInt("zPos") != cz)) {
            throw new IOException("chunk " + cx + "," + cz + ": read chunk " + chunk.getInt("xPos") + ","
                    + chunk.getInt("zPos"));
        }
        return chunk;
    }

    /** Until full or end of file. */
    private static void read(FileChannel channel, ByteBuffer into, long at) throws IOException {
        while (into.hasRemaining() && channel.read(into, at + into.position()) >= 0) {
        }
    }
}
