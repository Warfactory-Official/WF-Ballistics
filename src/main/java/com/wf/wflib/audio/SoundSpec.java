package com.wf.wflib.audio;

import com.google.gson.JsonElement;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;

/**
 * One sound layer. Gain at distance {@code d} ({@link #gain}): 1 to {@code ref = max(0.15 range, from)}, then
 * {@code (e^-3n - e^-3) / (1 - e^-3)}, {@code n = (d - ref) / (range - ref)}; {@code from > 0} => 0 below
 * {@code from / 2}, linear to 1 at {@code from} (far layer of a near/far pair). Volume in [0, 1]: the engine clamps
 * there. Pitch = {@code pitch + U[0,1) pitchRandom}. {@code delay}: s after emission, before travel time.
 * JSON: event id string, or object.
 */
public record SoundSpec(ResourceLocation event, float volume, float pitch, float pitchRandom, float range, float delay,
                        float from) {
    public static final float DEFAULT_RANGE = 16;
    private static final double E3 = Math.exp(-3);

    private static final Codec<SoundSpec> OBJECT = RecordCodecBuilder.create(i -> i.group(
            ResourceLocation.CODEC.fieldOf("event").forGetter(SoundSpec::event),
            Codec.floatRange(0, 1).optionalFieldOf("volume", 1F).forGetter(SoundSpec::volume),
            Codec.floatRange(0, 4).optionalFieldOf("pitch", 1F).forGetter(SoundSpec::pitch),
            Codec.floatRange(0, 2).optionalFieldOf("pitch_random", 0F).forGetter(SoundSpec::pitchRandom),
            Codec.floatRange(1, 100_000).optionalFieldOf("range", DEFAULT_RANGE).forGetter(SoundSpec::range),
            Codec.floatRange(0, 60).optionalFieldOf("delay", 0F).forGetter(SoundSpec::delay),
            Codec.floatRange(0, 100_000).optionalFieldOf("from", 0F).forGetter(SoundSpec::from)
    ).apply(i, SoundSpec::new));

    public static final Codec<SoundSpec> CODEC = Codec.either(ResourceLocation.CODEC, OBJECT).xmap(
            e -> e.map(SoundSpec::of, s -> s), Either::right);

    public static final StreamCodec<ByteBuf, SoundSpec> STREAM_CODEC = StreamCodec.of((b, s) -> {
        ResourceLocation.STREAM_CODEC.encode(b, s.event);
        b.writeFloat(s.volume);
        b.writeFloat(s.pitch);
        b.writeFloat(s.pitchRandom);
        b.writeFloat(s.range);
        b.writeFloat(s.delay);
        b.writeFloat(s.from);
    }, b -> new SoundSpec(ResourceLocation.STREAM_CODEC.decode(b), b.readFloat(), b.readFloat(), b.readFloat(),
            b.readFloat(), b.readFloat(), b.readFloat()));

    public static final StreamCodec<ByteBuf, java.util.List<SoundSpec>> LIST_STREAM_CODEC =
            STREAM_CODEC.apply(ByteBufCodecs.list(16));

    public SoundSpec {
        if (range <= from) {
            throw new IllegalArgumentException(event + ": range " + range + " <= from " + from);
        }
    }

    public SoundSpec(ResourceLocation event, float volume, float pitch, float pitchRandom, float range, float delay) {
        this(event, volume, pitch, pitchRandom, range, delay, 0);
    }

    public static SoundSpec of(ResourceLocation event) {
        return new SoundSpec(event, 1, 1, 0, DEFAULT_RANGE, 0, 0);
    }

    public static SoundSpec of(ResourceLocation event, float volume, float range) {
        return new SoundSpec(event, volume, 1, 0, range, 0, 0);
    }

    /** Gson trees (pack POJOs). */
    public static SoundSpec fromJson(JsonElement json) {
        return CODEC.parse(JsonOps.INSTANCE, json).getOrThrow(IllegalArgumentException::new);
    }

    public SoundSpec withRange(float from, float range) {
        return new SoundSpec(event, volume, pitch, pitchRandom, range, delay, from);
    }

    public SoundSpec withVolume(float volume) {
        return new SoundSpec(event, volume, pitch, pitchRandom, range, delay, from);
    }

    public SoundSpec withPitch(float pitch) {
        return new SoundSpec(event, volume, pitch, pitchRandom, range, delay, from);
    }

    public float rollPitch(RandomSource random) {
        return pitch + random.nextFloat() * pitchRandom;
    }

    public float gain(double d) {
        if (d >= range) {
            return 0;
        }
        double ref = Math.max(0.15 * range, from);
        double fall = 1;
        if (d > ref) {
            double n = (d - ref) / (range - ref);
            fall = (Math.exp(-3 * n) - E3) / (1 - E3);
        }
        double fade = from > 0 ? Math.clamp((d - from / 2) / (from / 2), 0, 1) : 1;
        return (float) (volume * fall * fade);
    }

    /** Distance below which {@link #gain} is 0. */
    public float audibleFrom() {
        return from / 2;
    }
}
