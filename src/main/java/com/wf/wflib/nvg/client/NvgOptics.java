package com.wf.wflib.nvg.client;

import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.core.registries.BuiltInRegistries;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tube optics per goggle item, {@code assets/<ns>/nvg/<item path>.json}. Schema: wflib {@code docs/nvg.md}.
 *
 * @param tubes       eyepieces, screen-height units from the screen centre (1-4)
 * @param resolution  photocathode image height / screen height
 * @param spectral    response to visible r, g, b and to IR (linear)
 * @param persistence phosphor lag: previous frame kept {@code max(now, prev * persistence)}
 */
public record NvgOptics(List<Tube> tubes, float resolution, float gainMax, float snr, float ebi, float halo,
                        boolean autogate, float abcRate, float distortion, float vignette, float honeycomb,
                        List<Float> phosphor, float persistence, List<Float> spectral) {

    public record Tube(float x, float y, float r) {
        static final Codec<Tube> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.FLOAT.fieldOf("x").forGetter(Tube::x),
                Codec.FLOAT.fieldOf("y").forGetter(Tube::y),
                Codec.floatRange(0.01f, 1.0f).fieldOf("r").forGetter(Tube::r)
        ).apply(i, Tube::new));
    }

    public static final int MAX_TUBES = 4;

    public static final Codec<NvgOptics> CODEC = RecordCodecBuilder.create(i -> i.group(
            Tube.CODEC.listOf(1, MAX_TUBES).fieldOf("tubes").forGetter(NvgOptics::tubes),
            Codec.floatRange(0.05f, 1.0f).fieldOf("resolution").forGetter(NvgOptics::resolution),
            Codec.floatRange(1.0f, 100000.0f).fieldOf("gain_max").forGetter(NvgOptics::gainMax),
            Codec.floatRange(1.0f, 1000.0f).fieldOf("snr").forGetter(NvgOptics::snr),
            Codec.floatRange(0.0f, 1.0f).fieldOf("ebi").forGetter(NvgOptics::ebi),
            Codec.floatRange(0.0f, 1.0f).fieldOf("halo").forGetter(NvgOptics::halo),
            Codec.BOOL.fieldOf("autogate").forGetter(NvgOptics::autogate),
            Codec.floatRange(0.01f, 100.0f).fieldOf("abc_rate").forGetter(NvgOptics::abcRate),
            Codec.floatRange(-1.0f, 1.0f).fieldOf("distortion").forGetter(NvgOptics::distortion),
            Codec.floatRange(0.0f, 1.0f).fieldOf("vignette").forGetter(NvgOptics::vignette),
            Codec.floatRange(0.0f, 1.0f).fieldOf("honeycomb").forGetter(NvgOptics::honeycomb),
            Codec.FLOAT.listOf(3, 3).fieldOf("phosphor").forGetter(NvgOptics::phosphor),
            Codec.floatRange(0.0f, 0.99f).fieldOf("persistence").forGetter(NvgOptics::persistence),
            Codec.FLOAT.listOf(4, 4).fieldOf("spectral").forGetter(NvgOptics::spectral)
    ).apply(i, NvgOptics::new));

    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile Map<ResourceLocation, NvgOptics> byItem = Map.of();

    /** Null => goggle without optics JSON (logged at load). */
    @Nullable
    public static NvgOptics of(Item item) {
        return byItem.get(BuiltInRegistries.ITEM.getKey(item));
    }

    public static final class Loader extends SimpleJsonResourceReloadListener {
        public Loader() {
            super(new com.google.gson.Gson(), "nvg");
        }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
            Map<ResourceLocation, NvgOptics> loaded = new HashMap<>();
            files.forEach((id, json) -> CODEC.parse(JsonOps.INSTANCE, json)
                    .resultOrPartial(error -> LOGGER.error("nvg optics {}: {}", id, error))
                    .ifPresent(optics -> loaded.put(id, optics)));
            byItem = Map.copyOf(loaded);
        }
    }
}
