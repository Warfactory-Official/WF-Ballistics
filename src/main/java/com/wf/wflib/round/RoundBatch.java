package com.wf.wflib.round;

import com.wf.wflib.kinetic.KineticPreset;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.UUID;

/** Every round in one level, struct of arrays; removal swaps the last round in. */
final class RoundBatch {

    static final long[] NO_CHUNKS = new long[0];
    static final UUID[] NO_VIEWERS = new UUID[0];

    int size;
    long resolveNanos;
    long[] key = new long[64];
    KineticPreset[] preset = new KineticPreset[64];
    double[] x = new double[64];
    double[] y = new double[64];
    double[] z = new double[64];
    double[] vx = new double[64];
    double[] vy = new double[64];
    double[] vz = new double[64];
    int[] life = new int[64];
    int[] pen = new int[64];
    int[] shooter = new int[64];
    UUID[] faction = new UUID[64];
    /** Chunks (packed) the round holds tickets for. */
    long[][] chunks = new long[64][];
    /** Ticks of fuse left once landed; -1 = flying. */
    int[] rest = new int[64];
    float[] burrow = new float[64];
    float[] damageScale = new float[64];
    /** Lag-comp rewind; 0 = live sweep. */
    int[] rewind = new int[64];
    int[] seq = new int[64];
    /** Players sent a spawn or resync: they hold a client copy. */
    UUID[][] viewers = new UUID[64][];
    /** Last step flew a segment over unloaded chunks only. */
    boolean[] dark = new boolean[64];

    int add(long key, KineticPreset preset, double x, double y, double z, double vx, double vy, double vz,
            int shooter, @Nullable UUID faction, float damageScale, int rewind, int seq) {
        if (this.size == this.key.length) {
            int n = this.size * 2;
            this.key = Arrays.copyOf(this.key, n);
            this.preset = Arrays.copyOf(this.preset, n);
            this.x = Arrays.copyOf(this.x, n);
            this.y = Arrays.copyOf(this.y, n);
            this.z = Arrays.copyOf(this.z, n);
            this.vx = Arrays.copyOf(this.vx, n);
            this.vy = Arrays.copyOf(this.vy, n);
            this.vz = Arrays.copyOf(this.vz, n);
            this.life = Arrays.copyOf(this.life, n);
            this.pen = Arrays.copyOf(this.pen, n);
            this.shooter = Arrays.copyOf(this.shooter, n);
            this.faction = Arrays.copyOf(this.faction, n);
            this.chunks = Arrays.copyOf(this.chunks, n);
            this.rest = Arrays.copyOf(this.rest, n);
            this.burrow = Arrays.copyOf(this.burrow, n);
            this.damageScale = Arrays.copyOf(this.damageScale, n);
            this.rewind = Arrays.copyOf(this.rewind, n);
            this.seq = Arrays.copyOf(this.seq, n);
            this.viewers = Arrays.copyOf(this.viewers, n);
            this.dark = Arrays.copyOf(this.dark, n);
        }
        int i = this.size++;
        this.key[i] = key;
        this.preset[i] = preset;
        this.x[i] = x;
        this.y[i] = y;
        this.z[i] = z;
        this.vx[i] = vx;
        this.vy[i] = vy;
        this.vz[i] = vz;
        this.life[i] = preset.lifeTicks();
        this.pen[i] = preset.penetration();
        this.shooter[i] = shooter;
        this.faction[i] = faction;
        this.chunks[i] = NO_CHUNKS;
        this.rest[i] = -1;
        this.burrow[i] = 0.0f;
        this.damageScale[i] = damageScale;
        this.rewind[i] = rewind;
        this.seq[i] = seq;
        this.viewers[i] = NO_VIEWERS;
        this.dark[i] = false;
        return i;
    }

    void remove(int i) {
        int last = --this.size;
        if (i != last) {
            this.key[i] = this.key[last];
            this.preset[i] = this.preset[last];
            this.x[i] = this.x[last];
            this.y[i] = this.y[last];
            this.z[i] = this.z[last];
            this.vx[i] = this.vx[last];
            this.vy[i] = this.vy[last];
            this.vz[i] = this.vz[last];
            this.life[i] = this.life[last];
            this.pen[i] = this.pen[last];
            this.shooter[i] = this.shooter[last];
            this.faction[i] = this.faction[last];
            this.chunks[i] = this.chunks[last];
            this.rest[i] = this.rest[last];
            this.burrow[i] = this.burrow[last];
            this.damageScale[i] = this.damageScale[last];
            this.rewind[i] = this.rewind[last];
            this.seq[i] = this.seq[last];
            this.viewers[i] = this.viewers[last];
            this.dark[i] = this.dark[last];
        }
        this.preset[last] = null;
        this.faction[last] = null;
        this.viewers[last] = null;
        this.chunks[last] = null;
    }

    int indexOf(long key) {
        for (int i = 0; i < this.size; i++) {
            if (this.key[i] == key) {
                return i;
            }
        }
        return -1;
    }
}
