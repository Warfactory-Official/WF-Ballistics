package com.wf.wflib.audio.client;

import com.wf.wflib.audio.AudioCategory;
import com.wf.wflib.config.WFClientConfig;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.Arrays;

/** Category volumes, cached per client tick ({@link #refresh}); Sound options sliders. */
public final class AudioOptions {
    private static final float[] VOLUME = new float[AudioCategory.values().length];
    private static float cabinMuffle = 1;

    static {
        Arrays.fill(VOLUME, 1);
    }

    private AudioOptions() {
    }

    public static float volume(AudioCategory c) {
        return VOLUME[c.ordinal()];
    }

    public static float cabinMuffle() {
        return cabinMuffle;
    }

    /** Unbaked spec (before config load) => keeps last values. */
    static void refresh() {
        if (!WFClientConfig.SPEC.isLoaded()) {
            return;
        }
        for (AudioCategory c : AudioCategory.values()) {
            VOLUME[c.ordinal()] = WFClientConfig.AUDIO_VOLUME[c.ordinal()].get().floatValue();
        }
        cabinMuffle = WFClientConfig.CABIN_MUFFLE.get().floatValue();
    }

    public static OptionInstance<?>[] sliders() {
        AudioCategory[] cats = AudioCategory.values();
        OptionInstance<?>[] out = new OptionInstance<?>[cats.length + 1];
        for (AudioCategory c : cats) {
            var value = WFClientConfig.AUDIO_VOLUME[c.ordinal()];
            out[c.ordinal()] = slider("soundCategory.wflib." + c.getSerializedName(), value);
        }
        out[cats.length] = slider("options.wflib.cabinMuffle", WFClientConfig.CABIN_MUFFLE);
        return out;
    }

    private static OptionInstance<Double> slider(String key, ModConfigSpec.DoubleValue value) {
        return new OptionInstance<>(key, OptionInstance.noTooltip(),
                (caption, v) -> v == 0 ? Options.genericValueLabel(caption, CommonComponents.OPTION_OFF)
                        : Component.translatable("options.percent_value", caption, (int) (v * 100)),
                OptionInstance.UnitDouble.INSTANCE, value.get(), v -> {
            value.set(v);
            value.save();
            refresh();
        });
    }
}
