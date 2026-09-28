package com.wf.wflib.round.pen;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.wf.wflib.WFLib;
import com.wf.wflib.round.terrain.AsyncTerrainSource;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.registries.datamaps.DataMapType;
import net.neoforged.neoforge.registries.datamaps.DataMapsUpdatedEvent;
import net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent;
import org.slf4j.Logger;

/**
 * Block penetration resistance, mm steel-eq per metre: data map {@code wflib:penetration}
 * ({@code data/<ns>/data_maps/block/penetration.json}, block or tag -> {@code {"resistance": mm}}, synced) > {@link
 * PenMaterial} class. Read by {@link BlockPen} (pierce, client prediction) and the drill.
 */
@EventBusSubscriber(modid = WFLib.MODID)
public final class PenTable {

    public static final DataMapType<Block, Resistance> PENETRATION = DataMapType.builder(
                    ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "penetration"), Registries.BLOCK, Resistance.CODEC)
            .synced(Resistance.CODEC, false).build();
    private static final Logger LOGGER = LogUtils.getLogger();
    /** By {@code Block.BLOCK_STATE_REGISTRY} id; null => resolve per call. */
    private static volatile float[] table;

    private PenTable() {
    }

    /** @param resistance mm steel-eq per metre of ray inside the collision shape */
    public record Resistance(float resistance) {
        public static final Codec<Resistance> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.floatRange(0.0f, Float.MAX_VALUE).fieldOf("resistance").forGetter(Resistance::resistance)
        ).apply(i, Resistance::new));
    }

    public static float resistance(BlockState state) {
        float[] t = table;
        int id = Block.getId(state);
        return t != null && id < t.length ? t[id] : resolve(state);
    }

    static float resolve(BlockState state) {
        @SuppressWarnings("deprecation")
        Resistance r = state.getBlock().builtInRegistryHolder().getData(PENETRATION);
        return r != null ? r.resistance() : PenMaterial.resolve(state).resistance;
    }

    /**
     * Rebuilt state by state; states added after it (none in 1.21) resolve per call. {@code server} => decoded
     * unloaded columns dropped: they bake the old values.
     */
    static void rebuild(String cause, boolean server) {
        long t0 = System.nanoTime();
        float[] t = new float[Block.BLOCK_STATE_REGISTRY.size()];
        for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
            t[Block.getId(state)] = resolve(state);
        }
        table = t;
        if (server) {
            AsyncTerrainSource.invalidateAll();
        }
        LOGGER.info("Penetration table ({}): {} states in {} us", cause, t.length, (System.nanoTime() - t0) / 1000);
    }

    @SubscribeEvent
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        if (event.shouldUpdateStaticData()) {
            rebuild("tags", event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD);
        }
    }

    @SubscribeEvent
    public static void onDataMapsUpdated(DataMapsUpdatedEvent event) {
        event.ifRegistry(Registries.BLOCK, registry -> rebuild("data map " + event.getCause(),
                event.getCause() == DataMapsUpdatedEvent.UpdateCause.SERVER_RELOAD));
    }

    @EventBusSubscriber(modid = WFLib.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Types {
        @SubscribeEvent
        public static void register(RegisterDataMapTypesEvent event) {
            event.register(PENETRATION);
        }
    }
}
