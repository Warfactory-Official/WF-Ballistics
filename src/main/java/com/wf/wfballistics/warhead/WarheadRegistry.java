package com.wf.wfballistics.warhead;

import com.wf.wfballistics.MissileEntity;
import com.wf.wfballistics.aef.ExplosionAEF;
import com.wf.wfballistics.aef.standard.BlockAllocatorStandard;
import com.wf.wfballistics.aef.standard.BlockProcessorStandard;
import com.wf.wfballistics.aef.standard.PlayerProcessorStandard;
import com.wf.wfballistics.entity.BombletEntity;
import com.wf.wfballistics.fx.ExplosionCreator;
import com.wf.wfballistics.util.FragmentationUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Registry of missile warheads, keyed by a short stable id (persisted on the {@link MissileEntity} so the
 * payload survives save/load and can be picked at runtime). Add-ons register their own via {@link #register}.
 */
public final class WarheadRegistry {

    public static final int STANDARD_BLAST_RADIUS = 50;
    /**
     * Standard high-explosive: a large crater blast plus the big explosion FX.
     */
    public static final Detonation STANDARD = (missile, pos) -> {
        Level level = missile.level();
        if (level.isClientSide) {
            return;
        }
        var xnt = new ExplosionAEF(level, pos.x, pos.y, pos.z, 15F);

        xnt.setBlockAllocator(new BlockAllocatorStandard(32));
        xnt.setBlockProcessor(new BlockProcessorStandard().setNoDrop());
        xnt.setPlayerProcessor(new PlayerProcessorStandard());
        xnt.explode();
        ExplosionCreator.composeEffectLarge(level, pos.x, pos.y, pos.z);
    };
    public static final InterceptDetonation STANDARD_INTERCEPT = (missile, pos) -> {
        Level level = missile.level();
        if (level.isClientSide) {
            return;
        }
        ExplosionCreator.composeEffectSmall(level, pos.x, pos.y, pos.z);
    };

    public static final Detonation MININUKE = (missile, pos) ->
            com.wf.wfballistics.aef.nuke.MiniNuke.detonate(missile.level(), pos,
                    com.wf.wfballistics.aef.nuke.MiniNuke.medium());

    public static final Detonation FRAGMENTATION = (missile, pos) -> {
        Level level = missile.level();
        if (level.isClientSide) {
            return;
        }
        FragmentationUtil.cone(level, pos, new Vec3(0.0, -1.0, 0.0),
                Math.toRadians(60.0), missile.getFragmentCount(), 1.2, 0.4,
                "standard", BombletEntity.STANDARD, BombletEntity.DEFAULT_FUSE, null);
        ExplosionCreator.composeEffectSmall(level, pos.x, pos.y, pos.z);
    };

    public static final Detonation INTERCEPTOR = (missile, pos) -> {
        Level level = missile.level();
        if (level.isClientSide) {
            return;
        }
        ExplosionCreator.composeEffectSmall(level, pos.x, pos.y, pos.z);
    };

    public static final Detonation INERT = (missile, pos) -> {
    };
    private static final String DEFAULT_ID = "standard";
    private static final Map<String, Detonation> WARHEADS = new LinkedHashMap<>();
    // Optional per-warhead intercept effect (see getIntercept); a warhead absent here runs its Detonation.
    private static final Map<String, InterceptDetonation> INTERCEPTS = new LinkedHashMap<>();

    static {
        register(DEFAULT_ID, STANDARD, STANDARD_INTERCEPT);
        register("mininuke", MININUKE);
        register("fragmentation", FRAGMENTATION);
        register(RecursiveFrag.ID, RecursiveFrag::detonate);
        register(GasWarhead.ID, GasWarhead::detonate);
        register(FireWarhead.ID, FireWarhead::detonate);
        register("interceptor", INTERCEPTOR, INTERCEPTOR::detonate);
        register("inert", INERT);
    }

    private WarheadRegistry() {
    }

    public static void register(String id, Detonation detonation) {
        WARHEADS.put(id, detonation);
    }

    /**
     * Register a warhead together with a custom {@link InterceptDetonation} — the (typically cheaper,
     * "neutralised") effect used when the missile is destroyed mid-air by an interceptor or a colliding
     * missile instead of reaching its target. Warheads registered without one fall back to running their
     * normal {@link Detonation} on intercept (see {@link #getIntercept}).
     */
    public static void register(String id, Detonation detonation, InterceptDetonation intercept) {
        WARHEADS.put(id, detonation);
        INTERCEPTS.put(id, intercept);
    }

    /**
     * @return the warhead for {@code id}, falling back to {@link #STANDARD} when unknown.
     */
    public static Detonation get(String id) {
        return WARHEADS.getOrDefault(id, STANDARD);
    }

    /**
     * @return the intercept effect for {@code id}: its registered {@link InterceptDetonation} if it has one,
     * otherwise a fallback that runs the full {@link Detonation} — so, by default, an intercept detonates the
     * warhead exactly as a target impact would.
     */
    public static InterceptDetonation getIntercept(String id) {
        InterceptDetonation intercept = INTERCEPTS.get(id);
        if (intercept != null) {
            return intercept;
        }
        Detonation detonation = get(id);
        return detonation::detonate;
    }

    public static boolean exists(String id) {
        return WARHEADS.containsKey(id);
    }

    /**
     * @return all registered ids, in registration order.
     */
    public static Set<String> ids() {
        return Collections.unmodifiableSet(WARHEADS.keySet());
    }

    public static String defaultId() {
        return DEFAULT_ID;
    }

    /**
     * What a warhead does when the missile goes off.
     */
    @FunctionalInterface
    public interface Detonation {
        void detonate(MissileEntity missile, Vec3 pos);
    }

    /**
     * What a warhead does when its missile is destroyed mid-air by an interceptor or a colliding missile,
     * Primarily used to cheapen some expensive explosions
     */
    @FunctionalInterface
    public interface InterceptDetonation {
        void detonate(MissileEntity missile, Vec3 pos);
    }
}
