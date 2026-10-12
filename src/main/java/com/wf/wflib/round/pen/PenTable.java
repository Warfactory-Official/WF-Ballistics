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

import java.util.Optional;

/**
 * Block penetration resistance, mm steel-eq per metre, impulse toughness, kg m/s, and ricochet critical grazing
 * angle, deg: data map {@code wflib:penetration} ({@code data/<ns>/data_maps/block/penetration.json}, block or tag ->
 * {@code {"resistance": mm, "toughness": kg m/s, "ricochet": deg}}, synced; toughness absent =>
 * {@link PenMaterial#TOUGHNESS_PER_RESISTANCE} x resistance; ricochet absent => the {@link PenMaterial} class's) >
 * {@link PenMaterial} class. Read by {@link BlockPen} (pierce, client prediction), {@link BlockDamage},
 * {@link Ricochet} and the drill.
 */
@EventBusSubscriber(modid = WFLib.MODID)
public final class PenTable {

    public static final DataMapType<Block, Resistance> PENETRATION = DataMapType.builder(
                    ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "penetration"), Registries.BLOCK, Resistance.CODEC)
            .synced(Resistance.CODEC, false).build();
    private static final Logger LOGGER = LogUtils.getLogger();
    /** By {@code Block.BLOCK_STATE_REGISTRY} id; null => resolve per call. */
    private static volatile float[] table;
    private static volatile float[] toughnessTable;
    private static volatile float[] ricochetTable;

    private PenTable() {
    }

    /**
     * @param resistance mm steel-eq per metre of ray inside the collision shape
     * @param toughness  round impulse that destroys it, kg m/s
     * @param ricochet   critical grazing angle, deg
     */
    public record Resistance(float resistance, Optional<Float> toughness, Optional<Float> ricochet) {
        public static final Codec<Resistance> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.floatRange(0.0f, Float.MAX_VALUE).fieldOf("resistance").forGetter(Resistance::resistance),
                Codec.floatRange(0.0f, Float.MAX_VALUE).optionalFieldOf("toughness").forGetter(Resistance::toughness),
                Codec.floatRange(0.0f, 90.0f).optionalFieldOf("ricochet").forGetter(Resistance::ricochet)
        ).apply(i, Resistance::new));
    }

    public static float resistance(BlockState state) {
        float[] t = table;
        int id = Block.getId(state);
        return t != null && id < t.length ? t[id] : resolve(state);
    }

    /** Round impulse that destroys {@code state}, kg m/s; infinite => never. */
    public static float toughness(BlockState state) {
        float[] t = toughnessTable;
        int id = Block.getId(state);
        return t != null && id < t.length ? t[id] : resolveToughness(state);
    }

    /** Critical grazing angle, deg: shallower => {@link Ricochet} may skip the round off. */
    public static float ricochet(BlockState state) {
        float[] t = ricochetTable;
        int id = Block.getId(state);
        return t != null && id < t.length ? t[id] : resolveRicochet(state);
    }

    static float resolve(BlockState state) {
        Resistance r = data(state);
        return r != null ? r.resistance() : PenMaterial.resolve(state).resistance;
    }

    static float resolveToughness(BlockState state) {
        Resistance r = data(state);
        return r == null ? PenMaterial.resolve(state).toughness
                : r.toughness().orElse(PenMaterial.TOUGHNESS_PER_RESISTANCE * r.resistance());
    }

    static float resolveRicochet(BlockState state) {
        Resistance r = data(state);
        return r != null && r.ricochet().isPresent() ? r.ricochet().get() : PenMaterial.resolve(state).ricochet;
    }

    @SuppressWarnings("deprecation")
    private static Resistance data(BlockState state) {
        return state.getBlock().builtInRegistryHolder().getData(PENETRATION);
    }

    /**
     * Rebuilt state by state; states added after it (none in 1.21) resolve per call. {@code server} => decoded
     * unloaded columns dropped: they bake the old values.
     */
    static void rebuild(String cause, boolean server) {
        long t0 = System.nanoTime();
        float[] t = new float[Block.BLOCK_STATE_REGISTRY.size()];
        float[] k = new float[t.length];
        float[] r = new float[t.length];
        for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
            t[Block.getId(state)] = resolve(state);
            k[Block.getId(state)] = resolveToughness(state);
            r[Block.getId(state)] = resolveRicochet(state);
        }
        table = t;
        toughnessTable = k;
        ricochetTable = r;
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
