package com.wf.wflib.audio;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

import java.util.List;
import java.util.stream.IntStream;

/** Piecewise-linear {@code y(x)}, clamped past the ends. JSON: number (constant) or {@code [[x, y], ...]}, x ascending. */
public record Curve(float[] xs, float[] ys) {
    public static final Curve ONE = constant(1);

    private static final Codec<Curve> POINTS = Codec.FLOAT.listOf(2, 2).listOf(1, 64).comapFlatMap(Curve::points,
            c -> IntStream.range(0, c.xs.length).mapToObj(i -> List.of(c.xs[i], c.ys[i])).toList());

    public static final Codec<Curve> CODEC = Codec.either(Codec.FLOAT, POINTS).xmap(
            e -> e.map(Curve::constant, c -> c), c -> c.xs.length == 1 ? Either.left(c.ys[0]) : Either.right(c));

    public static Curve constant(float y) {
        return new Curve(new float[]{0}, new float[]{y});
    }

    private static DataResult<Curve> points(List<List<Float>> pts) {
        float[] xs = new float[pts.size()];
        float[] ys = new float[pts.size()];
        for (int i = 0; i < xs.length; i++) {
            xs[i] = pts.get(i).get(0);
            ys[i] = pts.get(i).get(1);
            if (i > 0 && xs[i] <= xs[i - 1]) {
                return DataResult.error(() -> "curve x not ascending: " + pts);
            }
        }
        return DataResult.success(new Curve(xs, ys));
    }

    public float at(float x) {
        int n = xs.length;
        if (x <= xs[0]) {
            return ys[0];
        }
        if (x >= xs[n - 1]) {
            return ys[n - 1];
        }
        int i = 1;
        while (xs[i] < x) {
            i++;
        }
        float t = (x - xs[i - 1]) / (xs[i] - xs[i - 1]);
        return ys[i - 1] + t * (ys[i] - ys[i - 1]);
    }
}
