package com.wf.wflib.nvg;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.Mth;

/**
 * @param gain fraction of the tube's {@code gain_max}, {@code [GAIN_MIN, 1]}
 * @param ir   illuminator switched on; emits only while {@code down}
 */
public record NvgState(boolean down, boolean ir, float gain) {
    public static final float GAIN_MIN = 0.1f;
    public static final float GAIN_STEP = 0.1f;
    public static final NvgState UP = new NvgState(false, false, 0.7f);

    public static final Codec<NvgState> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.BOOL.fieldOf("down").forGetter(NvgState::down),
            Codec.BOOL.fieldOf("ir").forGetter(NvgState::ir),
            Codec.floatRange(GAIN_MIN, 1.0f).fieldOf("gain").forGetter(NvgState::gain)
    ).apply(i, NvgState::new));

    public static final StreamCodec<ByteBuf, NvgState> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, NvgState::down,
            ByteBufCodecs.BOOL, NvgState::ir,
            ByteBufCodecs.FLOAT, NvgState::gain,
            NvgState::new);

    public NvgState withDown(boolean value) {
        return new NvgState(value, ir, gain);
    }

    public NvgState withIr(boolean value) {
        return new NvgState(down, value, gain);
    }

    public NvgState stepGain(int steps) {
        return new NvgState(down, ir, Mth.clamp(Math.round((gain + steps * GAIN_STEP) * 10.0f) / 10.0f, GAIN_MIN, 1.0f));
    }
}
