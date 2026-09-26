package com.wf.wflib.round;

import com.wf.wflib.kinetic.KineticPreset;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.UUID;

/** Every round in one level, struct of arrays; removal swaps the last round in. */
final class RoundBatch {

    /** Sentinel for {@link #chunk}: no chunk held. */
    static final long NO_CHUNK = Long.MIN_VALUE;

    int size;
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
    long[] chunk = new long[64];
    /** Ticks of fuse left once landed; -1 = flying. */
    int[] rest = new int[64];
    float[] burrow = new float[64];

    int add(long key, KineticPreset preset, double x, double y, double z, double vx, double vy, double vz,
            int shooter, @Nullable UUID faction) {
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
            this.chunk = Arrays.copyOf(this.chunk, n);
            this.rest = Arrays.copyOf(this.rest, n);
            this.burrow = Arrays.copyOf(this.burrow, n);
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
        this.chunk[i] = NO_CHUNK;
        this.rest[i] = -1;
        this.burrow[i] = 0.0f;
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
            this.chunk[i] = this.chunk[last];
            this.rest[i] = this.rest[last];
            this.burrow[i] = this.burrow[last];
        }
        this.preset[last] = null;
        this.faction[last] = null;
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
