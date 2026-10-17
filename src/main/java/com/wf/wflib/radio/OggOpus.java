package com.wf.wflib.radio;

import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusException;

import java.io.IOException;
import java.util.Arrays;

/**
 * One Ogg Opus file (RFC 7845) held in memory, first logical stream only. Positions in 48 kHz samples after
 * pre-skip. {@link #decoder} = 16-bit PCM at 48 kHz, {@code channels} 1 (downmixed) or 2.
 */
public final class OggOpus {
    public static final int RATE = 48_000;
    /** RFC 7845 4.6: decode from >= 80 ms before a seek target. */
    private static final int PREROLL = 3840;
    /** 120 ms, largest Opus packet. */
    private static final int MAX_FRAME = 5760;

    private final byte[] data;
    /** Page start offsets + end granule (-1 = no packet ends on the page), audio pages only. */
    private final int[] pageOffset;
    private final long[] pageGranule;
    private final int streamChannels;
    private final int preSkip;
    /** Output gain, linear. */
    private final float gain;
    /** Samples after pre-skip. */
    private final long length;

    private OggOpus(byte[] data, int[] pageOffset, long[] pageGranule, int streamChannels, int preSkip, float gain,
                    long length) {
        this.data = data;
        this.pageOffset = pageOffset;
        this.pageGranule = pageGranule;
        this.streamChannels = streamChannels;
        this.preSkip = preSkip;
        this.gain = gain;
        this.length = length;
    }

    /** @throws IOException not Ogg Opus, or truncated */
    public static OggOpus parse(byte[] data) throws IOException {
        int[] offsets = new int[64];
        long[] granules = new long[64];
        int pages = 0;
        int serial = 0;
        int headers = 0;
        int channels = 0;
        int preSkip = 0;
        float gain = 1;
        long last = -1;
        for (int at = 0; at < data.length; ) {
            if (at + 27 > data.length || data[at] != 'O' || data[at + 1] != 'g' || data[at + 2] != 'g'
                    || data[at + 3] != 'S') {
                throw new IOException("bad Ogg page at " + at);
            }
            int segments = data[at + 26] & 0xFF;
            int body = at + 27 + segments;
            int size = 0;
            for (int i = 0; i < segments; i++) {
                size += data[at + 27 + i] & 0xFF;
            }
            if (body + size > data.length) {
                throw new IOException("truncated Ogg page at " + at);
            }
            int pageSerial = le32(data, at + 14);
            if (headers == 0) {
                serial = pageSerial;
                if (size < 19 || !ascii(data, body, "OpusHead")) {
                    throw new IOException("not Opus: no OpusHead");
                }
                channels = data[body + 9] & 0xFF;
                preSkip = le16(data, body + 10);
                gain = (float) Math.pow(10, (short) le16(data, body + 16) / (20.0 * 256));
                if (channels < 1 || channels > 2) {
                    throw new IOException(channels + " channels; mono or stereo only");
                }
                headers = 1;
            } else if (pageSerial == serial) {
                long granule = le64(data, at + 6);
                if (headers == 1) {
                    // OpusTags: ends on the first page with granule 0.
                    headers = granule == 0 ? 2 : 1;
                } else {
                    if (pages == offsets.length) {
                        offsets = Arrays.copyOf(offsets, pages * 2);
                        granules = Arrays.copyOf(granules, pages * 2);
                    }
                    offsets[pages] = at;
                    granules[pages++] = granule;
                    if (granule != -1) {
                        last = granule;
                    }
                }
            }
            at = body + size;
        }
        if (pages == 0 || last <= preSkip) {
            throw new IOException("no audio");
        }
        return new OggOpus(data, Arrays.copyOf(offsets, pages), Arrays.copyOf(granules, pages), channels, preSkip,
                gain, last - preSkip);
    }

    /** Samples per channel at 48 kHz. */
    public long length() {
        return length;
    }

    public int streamChannels() {
        return streamChannels;
    }

    /** Decoder from {@code position} (samples, clamped to [0, length)). */
    public Reader reader(long position, int channels) throws IOException {
        return new Reader(Math.clamp(position, 0, length - 1), channels);
    }

    /** Sequential PCM; not thread-safe. */
    public final class Reader {
        private final OpusDecoder decoder;
        private final int channels;
        private final short[] pcm;
        private final byte[] packet = new byte[65_536];
        private int page;
        private int segment;
        /** Granule (pre-skip included) of the next packet's first sample. */
        private long cursor;
        /** Granule below which output is dropped (pre-skip, seek preroll). */
        private final long dropUntil;
        private int pendingAt;
        private int pendingLeft;

        private Reader(long position, int channels) throws IOException {
            if (channels < 1 || channels > 2) {
                throw new IllegalArgumentException("channels " + channels);
            }
            try {
                this.decoder = new OpusDecoder(RATE, channels);
            } catch (OpusException e) {
                throw new IOException(e);
            }
            this.channels = channels;
            this.pcm = new short[MAX_FRAME * channels];
            this.dropUntil = position + preSkip;
            long from = Math.max(0, dropUntil - PREROLL);
            // Last page whose first fresh packet starts <= from.
            int best = 0;
            long bestStart = 0;
            for (int p = 0; p < pageOffset.length; p++) {
                if (pageGranule[p] == -1) {
                    continue;
                }
                long start = pageGranule[p] - freshSamples(p);
                if (start > from) {
                    break;
                }
                best = p;
                bestStart = start;
            }
            this.page = best;
            this.cursor = bestStart;
            this.segment = continuedSegments(best);
        }

        /** Lacing values of the packet tail continued from the previous page; 0 if none. */
        private int continuedSegments(int p) {
            int at = pageOffset[p];
            if ((data[at + 5] & 0x01) == 0) {
                return 0;
            }
            int segments = data[at + 26] & 0xFF;
            int s = 0;
            while (s < segments && (data[at + 27 + s] & 0xFF) == 255) {
                s++;
            }
            return Math.min(s + 1, segments);
        }

        /** Samples of the packets that start and end on page {@code p}. */
        private long freshSamples(int p) {
            int at = pageOffset[p];
            int segments = data[at + 26] & 0xFF;
            int body = at + 27 + segments;
            int s = 0;
            int b = body;
            for (int skip = continuedSegments(p); s < skip; s++) {
                b += data[at + 27 + s] & 0xFF;
            }
            long total = 0;
            int packetStart = b;
            for (; s < segments; s++) {
                int lace = data[at + 27 + s] & 0xFF;
                b += lace;
                if (lace < 255) {
                    total += packetSamples(data, packetStart, b - packetStart);
                    packetStart = b;
                }
            }
            return total;
        }

        /**
         * Decodes into {@code out} (interleaved) from sample {@code offset}, at most {@code max} samples per channel.
         * @return samples per channel written; 0 = end of track
         */
        public int read(short[] out, int offset, int max) throws IOException {
            int written = 0;
            int capacity = max;
            long end = length + preSkip;
            while (written < capacity) {
                if (pendingLeft == 0) {
                    int n = decodeNext();
                    if (n < 0) {
                        break;
                    }
                    long first = cursor;
                    cursor += n;
                    long lo = Math.max(first, dropUntil);
                    long hi = Math.min(cursor, end);
                    pendingAt = (int) (lo - first);
                    pendingLeft = (int) Math.max(0, hi - lo);
                    continue;
                }
                int count = Math.min(pendingLeft, capacity - written);
                for (int i = 0; i < count * channels; i++) {
                    out[(offset + written) * channels + i] = gain == 1 ? pcm[pendingAt * channels + i]
                            : (short) Math.clamp((int) (pcm[pendingAt * channels + i] * gain), Short.MIN_VALUE,
                            Short.MAX_VALUE);
                }
                written += count;
                pendingAt += count;
                pendingLeft -= count;
            }
            return written;
        }

        /** @return samples per channel of the next packet, -1 at end */
        private int decodeNext() throws IOException {
            int len = 0;
            while (page < pageOffset.length) {
                int at = pageOffset[page];
                int segments = data[at + 26] & 0xFF;
                if (segment >= segments) {
                    page++;
                    segment = 0;
                    continue;
                }
                int b = at + 27 + segments;
                for (int i = 0; i < segment; i++) {
                    b += data[at + 27 + i] & 0xFF;
                }
                int lace = data[at + 27 + segment] & 0xFF;
                System.arraycopy(data, b, packet, len, lace);
                len += lace;
                segment++;
                if (lace < 255) {
                    try {
                        return decoder.decode(packet, 0, len, pcm, 0, MAX_FRAME, false);
                    } catch (OpusException e) {
                        throw new IOException(e);
                    }
                }
            }
            return -1;
        }
    }

    /** RFC 6716 3.1: TOC frame size x frame count. */
    static int packetSamples(byte[] d, int at, int len) {
        if (len < 1) {
            return 0;
        }
        int toc = d[at] & 0xFF;
        int config = toc >> 3;
        int frame = config < 12 ? new int[]{480, 960, 1920, 2880}[config & 3]
                : config < 16 ? ((config & 1) == 0 ? 480 : 960)
                : 120 << (config & 3);
        int frames = switch (toc & 3) {
            case 0 -> 1;
            case 1, 2 -> 2;
            default -> len < 2 ? 0 : d[at + 1] & 0x3F;
        };
        return frame * frames;
    }

    private static boolean ascii(byte[] d, int at, String s) {
        for (int i = 0; i < s.length(); i++) {
            if (d[at + i] != s.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int le16(byte[] d, int at) {
        return (d[at] & 0xFF) | (d[at + 1] & 0xFF) << 8;
    }

    private static int le32(byte[] d, int at) {
        return le16(d, at) | le16(d, at + 2) << 16;
    }

    private static long le64(byte[] d, int at) {
        return (le32(d, at) & 0xFFFFFFFFL) | (long) le32(d, at + 4) << 32;
    }
}
