package com.wf.wfballistics.warhead;

import com.wf.wfballistics.MissileEntity;
import com.wf.wfballistics.aef.ExplosionAEF;
import com.wf.wfballistics.entity.BombletEntity;
import com.wf.wfballistics.entity.EntityNukeExplosionMK5;
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
 *
 * <p>A warhead is a {@link Detonation}: given the missile and the impact position it produces the effect
 * (blast, mini-nuke, bomblet scatter, …). Registration order is preserved so a UI can list them stably.
 */
public final class WarheadRegistry {

    /** What a warhead does when the missile goes off. */
    @FunctionalInterface
    public interface Detonation {
        void detonate(MissileEntity missile, Vec3 pos);
    }

    public static final int STANDARD_BLAST_RADIUS = 50;
    private static final String DEFAULT_ID = "standard";

    /** Standard high-explosive: a large crater blast plus the big explosion FX. */
    public static final Detonation STANDARD = (missile, pos) -> {
        Level level = missile.level();
        if (level.isClientSide) {
            return;
        }
        new ExplosionAEF(level, pos.x,pos.y, pos.z, STANDARD_BLAST_RADIUS).makeStandard().explode();
        ExplosionCreator.composeEffectLarge(level, pos.x, pos.y, pos.z);
    };

    /** Compact tactical nuke. */
    public static final Detonation MININUKE = (missile, pos) ->
            com.wf.wfballistics.aef.nuke.MiniNuke.detonate(missile.level(), pos,
                    com.wf.wfballistics.aef.nuke.MiniNuke.medium());

    /** Airburst fragmentation: rains a downward cone of bomblets (count taken from the missile). */
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

    /** Dud: removes the missile with no effect. */
    public static final Detonation INERT = (missile, pos) -> {
    };

    private static final Map<String, Detonation> WARHEADS = new LinkedHashMap<>();

    static {
        register(DEFAULT_ID, STANDARD);
        register("mininuke", MININUKE);
        register("fragmentation", FRAGMENTATION);
        register(RecursiveFrag.ID, RecursiveFrag::detonate);
        register(GasWarhead.ID, GasWarhead::detonate);
        register("inert", INERT);
    }

    private WarheadRegistry() {
    }

    public static void register(String id, Detonation detonation) {
        WARHEADS.put(id, detonation);
    }

    /** @return the warhead for {@code id}, falling back to {@link #STANDARD} when unknown. */
    public static Detonation get(String id) {
        return WARHEADS.getOrDefault(id, STANDARD);
    }

    public static boolean exists(String id) {
        return WARHEADS.containsKey(id);
    }

    /** @return all registered ids, in registration order. */
    public static Set<String> ids() {
        return Collections.unmodifiableSet(WARHEADS.keySet());
    }

    public static String defaultId() {
        return DEFAULT_ID;
    }
}
