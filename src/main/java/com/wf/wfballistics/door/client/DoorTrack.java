package com.wf.wfballistics.door.client;

import java.util.ArrayList;
import java.util.List;

/** One channel of a door's motion: a list of keyframes read at a fraction of the door's whole travel. */
public final class DoorTrack {

    /** Interpolations the door table actually uses. NTM has fifteen; twelve of them no door reaches. */
    public static final byte LINEAR = 0;
    /** Quarter sine, still at the start and moving at the end. */
    public static final byte SIN_UP = 1;
    /** Half sine: still at both ends, quickest in the middle. */
    public static final byte SIN_FULL = 2;
    /** {@code 3t^2 - 2t^3}. Not the same curve as {@link #SIN_FULL}, and NTM uses both. */
    public static final byte SMOOTHSTEP = 3;

    private final float[] values;
    private final float[] spans;
    private final byte[] easing;
    private final boolean mirrored;
    /** How long the whole channel lasts, so {@link #at} can turn clip seconds back into a fraction. */
    private final float spanSeconds;

    private DoorTrack(float[] values, float[] spans, byte[] easing, boolean mirrored, float spanSeconds) {
        this.values = values;
        this.spans = spans;
        this.easing = easing;
        this.mirrored = mirrored;
        this.spanSeconds = spanSeconds;
    }

    /** The clip length this track was written against, in seconds. */
    public float spanSeconds() {
        return spanSeconds;
    }

    /** The same curve read from the far end. */
    public DoorTrack reversed() {
        return new DoorTrack(values, spans, easing, !mirrored, spanSeconds);
    }

    /** A channel that never moves. */
    public static DoorTrack constant(float value) {
        return new DoorTrack(new float[] {value}, new float[] {0.0f}, new byte[] {LINEAR}, false, 1.0f);
    }

    public static Builder over(int timeToOpenTicks) {
        return new Builder(timeToOpenTicks * 50.0f);
    }

    /** The channel's value at {@code clipSeconds}, which is the door's open fraction times its span. */
    public float at(float clipSeconds) {
        float unit = spanSeconds <= 0.0f ? clipSeconds : clipSeconds / spanSeconds;
        if (mirrored) {
            unit = 1.0f - unit;
        }
        float start = 0.0f;
        float end = 0.0f;
        int current = -1;
        int previous = -1;
        for (int i = 0; i < values.length; i++) {
            start = end;
            end += spans[i];
            previous = current;
            current = i;
            if (unit < end) {
                break;
            }
        }
        if (current < 0) {
            return 0.0f;
        }
        if (unit >= end || spans[current] <= 0.0f) {
            return values[current];
        }
        float to = values[current];
        float from = previous < 0 ? 0.0f : values[previous];
        if (Math.abs(to - from) < 1.0e-6f) {
            return to;
        }
        return from + (to - from) * ease(easing[current], (unit - start) / spans[current]);
    }

    private static float ease(byte kind, float t) {
        return switch (kind) {
            case SIN_UP -> 1.0f - (float) Math.cos(t * Math.PI * 0.5);
            case SIN_FULL -> (1.0f - (float) Math.cos(t * Math.PI)) * 0.5f;
            case SMOOTHSTEP -> t * t * (3.0f - 2.0f * t);
            default -> t;
        };
    }

    public static final class Builder {

        private final float totalMillis;
        private final List<Float> values = new ArrayList<>();
        private final List<Float> spans = new ArrayList<>();
        private final List<Byte> easing = new ArrayList<>();

        private Builder(float totalMillis) {
            this.totalMillis = totalMillis;
        }

        /** The value the channel starts at, taking no time to get there. */
        public Builder from(float value) {
            return add(value, 0, LINEAR);
        }

        public Builder to(float value, int millis) {
            return add(value, millis, LINEAR);
        }

        public Builder to(float value, int millis, byte interpolation) {
            return add(value, millis, interpolation);
        }

        /** Stays where it is for a while. */
        public Builder hold(float value, int millis) {
            return add(value, millis, LINEAR);
        }

        private Builder add(float value, int millis, byte interpolation) {
            values.add(value);
            spans.add(millis / totalMillis);
            easing.add(interpolation);
            return this;
        }

        public DoorTrack build() {
            int n = values.size();
            float[] v = new float[n];
            float[] s = new float[n];
            byte[] e = new byte[n];
            for (int i = 0; i < n; i++) {
                v[i] = values.get(i);
                s[i] = spans.get(i);
                e[i] = easing.get(i);
            }
            return new DoorTrack(v, s, e, false, totalMillis / 1000.0f);
        }
    }
}
