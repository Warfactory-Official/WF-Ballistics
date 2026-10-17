package com.wf.wflib.radio.client;

import com.wf.wflib.radio.OggOpus;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.AudioStream;
import org.lwjgl.BufferUtils;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

/** Endless PCM of one station from a sample position: track after track, looped; next track prefetched. */
final class StationStream implements AudioStream {
    private final RadioCatalog.Station station;
    private final int channels;
    private final AudioFormat format;
    private int track;
    private OggOpus.Reader reader;
    private CompletableFuture<OggOpus> next;
    private short[] scratch = new short[0];

    /** Blocks on the first track's load: caller off the sound thread. */
    StationStream(RadioCatalog.Station station, long sample, int channels) throws IOException {
        this.station = station;
        this.channels = channels;
        this.format = new AudioFormat(OggOpus.RATE, 16, channels, true, false);
        long[] at = station.locate(sample);
        this.track = (int) at[0];
        this.reader = load(this.track).reader(at[1], channels);
        prefetch();
    }

    private OggOpus load(int index) throws IOException {
        try (InputStream in = Minecraft.getInstance().getResourceManager()
                .open(station.tracks().get(index).file())) {
            return OggOpus.parse(in.readAllBytes());
        }
    }

    private void prefetch() {
        int index = (track + 1) % station.tracks().size();
        next = CompletableFuture.supplyAsync(() -> {
            try {
                return load(index);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }, Util.backgroundExecutor());
    }

    @Override
    public AudioFormat getFormat() {
        return format;
    }

    /** Sound thread. Empty buffer only when every track fails => source ends. */
    @Override
    public ByteBuffer read(int size) throws IOException {
        int frames = size / (2 * channels);
        if (scratch.length < frames * channels) {
            scratch = new short[frames * channels];
        }
        int filled = 0;
        int dry = 0;
        while (filled < frames && dry <= station.tracks().size()) {
            int n = reader == null ? 0 : reader.read(scratch, filled, frames - filled);
            if (n == 0) {
                dry++;
                advance();
                continue;
            }
            dry = 0;
            filled += n;
        }
        ByteBuffer out = BufferUtils.createByteBuffer(filled * channels * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < filled * channels; i++) {
            out.putShort(scratch[i]);
        }
        out.flip();
        return out;
    }

    /** Next track; a failed load leaves {@link #reader} null (skipped). */
    private void advance() throws IOException {
        track = (track + 1) % station.tracks().size();
        try {
            reader = next.join().reader(0, channels);
        } catch (java.util.concurrent.CompletionException e) {
            reader = null;
        }
        prefetch();
    }

    @Override
    public void close() {
        next.cancel(false);
    }
}
