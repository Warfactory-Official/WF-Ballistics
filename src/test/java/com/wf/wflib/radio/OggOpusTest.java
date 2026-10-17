package com.wf.wflib.radio;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fixtures: ffmpeg libopus, 3 s lavfi sines (1/8 full scale, rms ~2900; mono 440 Hz; stereo 440 L / 660 R). */
class OggOpusTest {
    private static OggOpus load(String name) throws IOException {
        try (InputStream in = OggOpusTest.class.getResourceAsStream("/radio/" + name)) {
            return OggOpus.parse(in.readAllBytes());
        }
    }

    private static short[] decode(OggOpus o, long from, int channels) throws IOException {
        OggOpus.Reader r = o.reader(from, channels);
        short[] out = new short[(int) (o.length() - from) * channels + 4096];
        int total = 0;
        while (true) {
            int n = r.read(out, total, Math.min(1000, (out.length / channels) - total));
            if (n == 0) {
                break;
            }
            total += n;
        }
        short[] trimmed = new short[total * channels];
        System.arraycopy(out, 0, trimmed, 0, trimmed.length);
        return trimmed;
    }

    private static double rms(short[] s, int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) {
            sum += (double) s[i] * s[i];
        }
        return Math.sqrt(sum / (to - from));
    }

    @Test
    void lengthIsTheEncodedDurationAndFullDecodeMatchesIt() throws IOException {
        OggOpus mono = load("mono_440.opus");
        assertEquals(1, mono.streamChannels());
        assertEquals(3 * OggOpus.RATE, mono.length(), 960);
        short[] pcm = decode(mono, 0, 1);
        assertEquals(mono.length(), pcm.length);
        assertTrue(rms(pcm, 4800, pcm.length - 4800) > 2000, "sine audible");
    }

    @Test
    void seekLandsOnTheSameSamplesAsAFullDecode() throws IOException {
        OggOpus mono = load("mono_440.opus");
        short[] full = decode(mono, 0, 1);
        int at = 72_013;
        short[] seek = decode(mono, at, 1);
        assertEquals(full.length - at, seek.length);
        double diff = 0;
        for (int i = 0; i < 4800; i++) {
            double d = full[at + i] - seek[i];
            diff += d * d;
        }
        diff = Math.sqrt(diff / 4800);
        assertTrue(diff < 0.05 * rms(full, at, at + 4800), "seek drift rms " + diff);
    }

    @Test
    void stereoDecodesToBothChannelsAndDownmixesToMono() throws IOException {
        OggOpus stereo = load("stereo.opus");
        assertEquals(2, stereo.streamChannels());
        short[] both = decode(stereo, 0, 2);
        assertEquals(stereo.length() * 2, both.length);
        short[] mono = decode(stereo, 0, 1);
        assertEquals(stereo.length(), mono.length);
        assertTrue(rms(mono, 4800, mono.length - 4800) > 2000, "downmix audible");
    }

    @Test
    void notOpusIsRefused() {
        assertThrows(IOException.class, () -> OggOpus.parse(new byte[]{'O', 'g', 'g', 'S'}));
        assertThrows(IOException.class, () -> OggOpus.parse("RIFF....WAVE".getBytes()));
    }

    @Test
    void stationIdsAreCatalogueKeysNotPaths() {
        assertTrue(RadioCarrier.validStation(""));
        assertTrue(RadioCarrier.validStation("radiotest:demo"));
        for (String bad : new String[]{"radiotest:../demo", "radiotest:a/b", "radiotest:x.opus", "Bad:demo",
                "radiotest:" + "a".repeat(80), ":", "radiotest:"}) {
            assertFalse(RadioCarrier.validStation(bad), bad);
        }
    }

    @Test
    void tocDurations() {
        assertEquals(960, OggOpus.packetSamples(new byte[]{(byte) (31 << 3)}, 0, 1));
        assertEquals(1920, OggOpus.packetSamples(new byte[]{(byte) (31 << 3 | 1)}, 0, 1));
        assertEquals(2880, OggOpus.packetSamples(new byte[]{(byte) (3 << 3)}, 0, 1));
        assertEquals(480 * 5, OggOpus.packetSamples(new byte[]{(byte) (12 << 3 | 3), 5}, 0, 2));
    }
}
