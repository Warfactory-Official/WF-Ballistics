package com.wf.wfballistics.warhead;

import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.aef.ExplosionAEF;
import com.wf.wfballistics.aef.nuke.MiniNuke;
import com.wf.wfballistics.aef.standard.BlockAllocatorStandard;
import com.wf.wfballistics.aef.standard.BlockProcessorStandard;
import com.wf.wfballistics.aef.standard.EntityProcessorCross;
import com.wf.wfballistics.aef.standard.PlayerProcessorStandard;
import com.wf.wfballistics.entity.BombletEntity;
import com.wf.wfballistics.fx.ExplosionCreator;
import com.wf.wfballistics.util.FragmentationUtil;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class WarheadRegistry {

    public static final int STANDARD_BLAST_RADIUS = 50;
    public static final float STANDARD_BLAST_SIZE = 15F;

    public static final Detonation STANDARD = (source, pos) -> {
        Level level = source.level();
        if (level.isClientSide) {
            return;
        }
        var xnt = new ExplosionAEF(level, pos.x, pos.y, pos.z, STANDARD_BLAST_SIZE);

        xnt.setBlockAllocator(new BlockAllocatorStandard(32));
        xnt.setBlockProcessor(new BlockProcessorStandard().setNoDrop());
        xnt.setEntityProcessor(new EntityProcessorCross());
        xnt.setPlayerProcessor(new PlayerProcessorStandard());
        xnt.explode();
        ExplosionCreator.composeEffectLarge(level, pos.x, pos.y, pos.z);
    };
    public static final InterceptDetonation STANDARD_INTERCEPT = (source, pos) -> {
        Level level = source.level();
        if (level.isClientSide) {
            return;
        }
        ExplosionCreator.composeEffectSmall(level, pos.x, pos.y, pos.z);
    };

    public static final float SHAPED_CHARGE_SIZE = 6F;

    /**
     * Top-attack shaped charge (HEAT): fires a tight jet straight down, drilling a deep, narrow shaft through
     * hardened cover with only a shallow crater at the surface. Only blocks/entities in the downward cone are
     * hit — see {@link com.wf.wfballistics.aef.standard.BlockAllocatorShapedCharge}. Swap the axis for the
     * carrier's velocity if a directional (non top-attack) variant is ever needed.
     */
    public static final Detonation SHAPED_CHARGE = (source, pos) -> {
        Level level = source.level();
        if (level.isClientSide) {
            return;
        }
        new ExplosionAEF(level, pos.x, pos.y, pos.z, SHAPED_CHARGE_SIZE)
                .makeShapedCharge(new Vec3(0.0, -1.0, 0.0))
                .explode();
        ExplosionCreator.composeEffectSmall(level, pos.x, pos.y, pos.z);
    };

    public static final Detonation MININUKE = (source, pos) ->
            MiniNuke.detonate(source.level(), pos,
                    MiniNuke.medium());

    public static final Detonation FRAGMENTATION = (source, pos) -> {
        Level level = source.level();
        if (level.isClientSide) {
            return;
        }
        FragmentationUtil.cone(level, pos, new Vec3(0.0, -1.0, 0.0),
                Math.toRadians(60.0), source.getFragmentCount(), 1.2, 0.4,
                BombletWarhead.ID, BombletWarhead.STANDARD, BombletEntity.DEFAULT_FUSE, null);
        ExplosionCreator.composeEffectSmall(level, pos.x, pos.y, pos.z);
    };

    public static final Detonation INTERCEPTOR = (source, pos) -> {
        Level level = source.level();
        if (level.isClientSide) {
            return;
        }
        ExplosionCreator.composeEffectSmall(level, pos.x, pos.y, pos.z);
    };

    public static final Detonation INERT = (source, pos) -> {
    };

    private static final ResourceLocation DEFAULT_ID = rl("standard");
    private static final Map<ResourceLocation, Detonation> WARHEADS = new LinkedHashMap<>();
    private static final Map<ResourceLocation, InterceptDetonation> INTERCEPTS = new LinkedHashMap<>();
    private static final Map<ResourceLocation, Float> BLAST_SIZE = new LinkedHashMap<>();

    static {
        register(DEFAULT_ID, STANDARD, STANDARD_INTERCEPT);
        register(rl("mininuke"), MININUKE);
        register(rl("fragmentation"), FRAGMENTATION);
        register(rl("shaped_charge"), SHAPED_CHARGE);
        register(RecursiveFrag.ID, RecursiveFrag::detonate);
        register(GasWarhead.ID, GasWarhead::detonate);
        register(FireWarhead.ID, FireWarhead::detonate);
        register(FireCluster.ID, FireCluster::detonate);
        register(rl("interceptor"), INTERCEPTOR, INTERCEPTOR::detonate);
        register(rl("inert"), INERT);
        register(rl("emp"), EMPWarhead::detonate);
        register(BombletWarhead.ID, BombletWarhead.STANDARD);
        register(BombletWarhead.FIRE_ID, BombletWarhead.FIRE);

        BLAST_SIZE.put(DEFAULT_ID, STANDARD_BLAST_SIZE);
        BLAST_SIZE.put(FireWarhead.ID, 8F);
        BLAST_SIZE.put(rl("shaped_charge"), SHAPED_CHARGE_SIZE);
    }

    private WarheadRegistry() {
    }

    public static ResourceLocation rl(String path) {
        return new ResourceLocation(WFBallistics.MODID, path);
    }

    public static ResourceLocation parse(String id) {
        if (id == null || id.isEmpty()) {
            return DEFAULT_ID;
        }
        ResourceLocation parsed = id.indexOf(':') >= 0 ? ResourceLocation.tryParse(id) : rl(id);
        return parsed != null ? parsed : DEFAULT_ID;
    }

    public static void register(ResourceLocation id, Detonation detonation) {
        WARHEADS.put(id, detonation);
    }

    public static void register(ResourceLocation id, Detonation detonation, InterceptDetonation intercept) {
        WARHEADS.put(id, detonation);
        INTERCEPTS.put(id, intercept);
    }

    public static Detonation get(ResourceLocation id) {
        return WARHEADS.getOrDefault(id, STANDARD);
    }

    public static Detonation get(ResourceLocation id, Detonation fallback) {
        return WARHEADS.getOrDefault(id, fallback);
    }

    public static InterceptDetonation getIntercept(ResourceLocation id) {
        InterceptDetonation intercept = INTERCEPTS.get(id);
        if (intercept != null) {
            return intercept;
        }
        Detonation detonation = get(id);
        return detonation::detonate;
    }

    public static boolean exists(ResourceLocation id) {
        return WARHEADS.containsKey(id);
    }

    public static float blastSize(ResourceLocation id) {
        return BLAST_SIZE.getOrDefault(id, 0f);
    }

    public static int peakEntityDamage(ResourceLocation id) {
        float size = blastSize(id);
        return size <= 0f ? 0 : (int) (8.0f * size + 1.0f);
    }

    public static Set<ResourceLocation> ids() {
        return Collections.unmodifiableSet(WARHEADS.keySet());
    }

    public static ResourceLocation defaultId() {
        return DEFAULT_ID;
    }

    @FunctionalInterface
    public interface Detonation {
        void detonate(WarheadCarrier source, Vec3 pos);
    }

    @FunctionalInterface
    public interface InterceptDetonation {
        void detonate(WarheadCarrier source, Vec3 pos);
    }
}
