package com.wf.wflib.audio;

import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.StringRepresentable;

import java.util.List;
import java.util.Optional;

/**
 * Engine audio: {@code start} / {@code stop} one-shots on the running edge, loops gated and shaped by curves over one
 * input each. Loop with gain <= {@link #SILENT} or out of range => not playing.
 */
public record EngineSound(Optional<SoundSpec> start, Optional<SoundSpec> stop, List<Loop> loops) {
    public static final float SILENT = 1e-3F;

    /** Engine state the owner reports, each 0..1. */
    public enum Input implements StringRepresentable {
        /** Spool: 0 off, rises through start-up. */
        POWER("power"),
        /** Shaft speed. */
        RPM("rpm"),
        /** Commanded thrust / throttle. */
        THROTTLE("throttle"),
        /** Ground or air speed over its maximum. */
        SPEED("speed");

        public static final Codec<Input> CODEC = StringRepresentable.fromEnum(Input::values);
        private final String key;

        Input(String key) {
            this.key = key;
        }

        @Override
        public String getSerializedName() {
            return key;
        }
    }

    /**
     * {@code volume}, {@code pitch}: of {@code input}; {@code aspect}: of the angle, degrees, between the emitter's
     * forward and the direction to the listener (0 ahead, 180 behind); {@code gate}: of {@link Input#POWER}, a factor
     * on volume (a loop shaped by throttle still dies with the engine); {@code fade}: gain slew per tick.
     */
    public record Loop(SoundSpec sound, Input input, Curve volume, Curve pitch, Curve aspect, Curve gate,
                       float fade) {
        public static final Codec<Loop> CODEC = RecordCodecBuilder.create(i -> i.group(
                SoundSpec.CODEC.fieldOf("sound").forGetter(Loop::sound),
                Input.CODEC.optionalFieldOf("input", Input.RPM).forGetter(Loop::input),
                Curve.CODEC.optionalFieldOf("volume", Curve.ONE).forGetter(Loop::volume),
                Curve.CODEC.optionalFieldOf("pitch", Curve.ONE).forGetter(Loop::pitch),
                Curve.CODEC.optionalFieldOf("aspect", Curve.ONE).forGetter(Loop::aspect),
                Curve.CODEC.optionalFieldOf("gate", Curve.ONE).forGetter(Loop::gate),
                Codec.floatRange(0.001F, 1).optionalFieldOf("fade", 0.1F).forGetter(Loop::fade)
        ).apply(i, Loop::new));
    }

    public static final Codec<EngineSound> CODEC = RecordCodecBuilder.create(i -> i.group(
            SoundSpec.CODEC.optionalFieldOf("start").forGetter(EngineSound::start),
            SoundSpec.CODEC.optionalFieldOf("stop").forGetter(EngineSound::stop),
            Loop.CODEC.listOf().optionalFieldOf("loops", List.of()).forGetter(EngineSound::loops)
    ).apply(i, EngineSound::new));

    /** Gson trees (pack POJOs). */
    public static EngineSound fromJson(JsonElement json) {
        return CODEC.parse(JsonOps.INSTANCE, json).getOrThrow(IllegalArgumentException::new);
    }

    /** Widest loop or one-shot range. */
    public float reach() {
        float r = 0;
        for (Loop l : loops) {
            r = Math.max(r, l.sound.range());
        }
        r = Math.max(r, start.map(SoundSpec::range).orElse(0F));
        return Math.max(r, stop.map(SoundSpec::range).orElse(0F));
    }
}
