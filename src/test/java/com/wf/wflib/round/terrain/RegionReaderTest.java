package com.wf.wflib.round.terrain;

import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link RegionReader} against vanilla's {@code RegionFile}: same NBT, and none of its writes. */
class RegionReaderTest {

    private static final RegionStorageInfo INFO = new RegionStorageInfo("test",
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld")), "chunk");

    @TempDir
    Path dir;

    private void write(int cx, int cz, CompoundTag tag) throws IOException {
        try (RegionFile region = new RegionFile(INFO, RegionReader.file(dir, cx, cz), dir, false);
             DataOutputStream out = region.getChunkDataOutputStream(new ChunkPos(cx, cz))) {
            NbtIo.write(tag, out);
        }
    }

    private static CompoundTag chunk(int seed, int bytes) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("seed", seed);
        byte[] noise = new byte[bytes];
        new Random(seed).nextBytes(noise);
        tag.putByteArray("noise", noise);
        return tag;
    }

    @Test
    void readsWhatVanillaReads() throws IOException {
        write(3, -7, chunk(1, 5000));
        write(4, -7, chunk(2, 70));
        CompoundTag vanilla;
        try (RegionFile region = new RegionFile(INFO, RegionReader.file(dir, 3, -7), dir, false);
             DataInputStream in = region.getChunkDataInputStream(new ChunkPos(3, -7))) {
            vanilla = NbtIo.read(in);
        }
        assertEquals(vanilla, RegionReader.read(dir, 3, -7));
        assertEquals(chunk(2, 70), RegionReader.read(dir, 4, -7));
        assertNull(RegionReader.read(dir, 5, -7), "chunk never written");
    }

    /** Past 1 MiB vanilla writes {@code c.x.z.mcc} beside the region. */
    @Test
    void readsExternalChunks() throws IOException {
        CompoundTag big = chunk(3, 1_200_000);
        write(40, 2, big);
        assertTrue(Files.exists(dir.resolve("c.40.2.mcc")), "setup: not external");
        assertEquals(big, RegionReader.read(dir, 40, 2));
    }

    @Test
    void neverCreatesARegionFile() throws IOException {
        Path mca = RegionReader.file(dir, 100, 100);
        assertNull(RegionReader.read(dir, 100, 100));
        assertFalse(Files.exists(mca));
        new RegionFile(INFO, mca, dir, false).close();
        assertTrue(Files.exists(mca), "vanilla ctor no longer creates: the READ-only open lost its reason");
    }

    /** A file mid-write (length off a sector): vanilla's close pads and forces it, the reader leaves it be. */
    @Test
    void neverPadsOrWrites() throws IOException {
        write(0, 0, chunk(4, 3000));
        Path mca = RegionReader.file(dir, 0, 0);
        try (FileChannel ch = FileChannel.open(mca, StandardOpenOption.WRITE)) {
            ch.truncate(ch.size() - 100);
        }
        byte[] before = Files.readAllBytes(mca);
        FileTime stamp = FileTime.fromMillis(1_000_000L);
        Files.setLastModifiedTime(mca, stamp);
        RegionReader.read(dir, 0, 0);
        RegionReader.read(dir, 1, 0);
        assertArrayEquals(before, Files.readAllBytes(mca));
        assertEquals(stamp, Files.getLastModifiedTime(mca));
        new RegionFile(INFO, mca, dir, false).close();
        assertNotEquals(before.length, Files.size(mca), "vanilla close no longer pads");
    }

    /** Short last sector (unpadded tail) still reads when the declared stream fits. */
    @Test
    void readsAnUnpaddedTail() throws IOException {
        CompoundTag tag = chunk(5, 100);
        write(0, 0, tag);
        Path mca = RegionReader.file(dir, 0, 0);
        ByteBuffer head = ByteBuffer.allocate(4);
        try (FileChannel ch = FileChannel.open(mca, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ch.read(head, 0L);
            long sector = head.getInt(0) >>> 8;
            ByteBuffer len = ByteBuffer.allocate(4);
            ch.read(len, sector * 4096L);
            ch.truncate(sector * 4096L + 4 + len.getInt(0));
        }
        assertEquals(tag, RegionReader.read(dir, 0, 0));
    }

    /** Slot (0,0)'s header pointing at chunk (1,0)'s sectors, as after the IO worker freed and reused them. */
    @Test
    void aReusedSlotIsRejected() throws IOException {
        CompoundTag a = chunk(6, 100);
        a.putInt("xPos", 0);
        a.putInt("zPos", 0);
        CompoundTag b = chunk(7, 100);
        b.putInt("xPos", 1);
        b.putInt("zPos", 0);
        write(0, 0, a);
        write(1, 0, b);
        assertEquals(a, RegionReader.requirePosition(RegionReader.read(dir, 0, 0), 0, 0));
        Path mca = RegionReader.file(dir, 0, 0);
        try (FileChannel ch = FileChannel.open(mca, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer entry = ByteBuffer.allocate(4);
            ch.read(entry, 4L);
            entry.flip();
            ch.write(entry, 0L);
        }
        CompoundTag stray = RegionReader.read(dir, 0, 0);
        assertEquals(b, stray, "setup: slot not redirected");
        assertThrows(IOException.class, () -> RegionReader.requirePosition(stray, 0, 0));
    }

    @Test
    void unpacksVanillaBitStorage() {
        Random random = new Random(9);
        for (int bits = 4; bits <= 15; bits++) {
            SimpleBitStorage storage = new SimpleBitStorage(bits, 4096);
            int[] values = new int[4096];
            for (int i = 0; i < 4096; i++) {
                values[i] = random.nextInt(1 << bits);
                storage.set(i, values[i]);
            }
            long[] raw = storage.getRaw();
            assertEquals(raw.length, SolidityDecoder.longsFor(bits), "bits " + bits);
            int[] got = new int[4096];
            for (int i = 0; i < 4096; i++) {
                got[i] = SolidityDecoder.paletteIndex(raw, bits, i);
            }
            assertTrue(Arrays.equals(values, got), "bits " + bits);
        }
        assertEquals(4, SolidityDecoder.bitsFor(2));
        assertEquals(4, SolidityDecoder.bitsFor(16));
        assertEquals(5, SolidityDecoder.bitsFor(17));
        assertEquals(9, SolidityDecoder.bitsFor(257));
    }
}
