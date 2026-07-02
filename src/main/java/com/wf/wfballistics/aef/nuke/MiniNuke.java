package com.wf.wfballistics.aef.nuke;

import com.wf.wfballistics.aef.ExplosionAEF;
import com.wf.wfballistics.aef.standard.BlockAllocatorStandard;
import com.wf.wfballistics.aef.standard.BlockMutatorFire;
import com.wf.wfballistics.aef.standard.BlockProcessorStandard;
import com.wf.wfballistics.entity.EntityNukeTorex;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The mini-nuke ("muke") system: a compact nuclear blast — a fire-leaving, drop-free crater, an
 * obstruction-checked entity kill radius, and a small mushroom cloud. Port of HBM's {@code ExplosionNukeSmall}
 * plus its {@code MukeParams} presets.
 *
 * <p>Adapted to WF: HBM's radiation payload and metal shrapnel are dropped (no such systems here), and HBM's
 * dedicated "Muke" mushroom particle maps to a small-scale {@link EntityNukeTorex}. Block destruction is
 * synchronous — cheap at mini-nuke radii — while the {@code miniNuke = false} "fatman" variant hands off to
 * the threaded, budget-per-tick {@link Nuke#detonate big-nuke} path.
 *
 * <pre>{@code MiniNuke.detonate(level, hitPos, MiniNuke.medium());}</pre>
 */
public final class MiniNuke {

    private MiniNuke() { }

    public static void detonate(Level level, Vec3 center, MukeParams p) {
        if (level.isClientSide) {
            return;
        }

        // The "fatman" variant is a full-size nuke: hand off to the threaded MK5 + Torex path.
        if (!p.miniNuke) {
            Nuke.detonate(level, center, (int) p.blastRadius);
            return;
        }

        // Block destruction: a drop-free crater that leaves fire. No entity processor (NOHURT — the kill
        // radius below handles damage) and no SFX (the mushroom below is the visual). Mini-nuke radii keep
        // the synchronous ray-march cheap.
        if (!p.safe) {
            BlockProcessorStandard processor = new BlockProcessorStandard().setNoDrop();
            if (p.fire) {
                processor.withBlockEffect(new BlockMutatorFire());
            }
            new ExplosionAEF(level, center.x, center.y, center.z, p.blastRadius)
                    .setBlockAllocator(new BlockAllocatorStandard(p.resolution))
                    .setBlockProcessor(processor)
                    .explode();
        }

        // Entity kill radius (obstruction-checked, like the big nuke's damage pass).
        if (p.killRadius > 0) {
            ExplosionNukeGeneric.dealDamage(level, center, p.killRadius);
        }

        // Visual: a small mushroom cloud (flash + mushroom + shockwave clouds + boom) — WF's stand-in for
        // HBM's "Muke" particle. The Torex clamps small scales, so a mini nuke gets a compact cloud.
        level.addFreshEntity(new EntityNukeTorex(level, center.add(0.0, 1.5, 0.0), p.blastRadius));
    }

    // --- HBM MukeParams presets (radiation + shrapnel dropped; resolution lowered for the synchronous pass) ---

    /** Tiny "tots" charge. */
    public static MukeParams tots() {
        MukeParams p = new MukeParams();
        p.blastRadius = 10F;
        p.killRadius = 30F;
        p.resolution = 24;
        return p;
    }

    /** Low-yield mini nuke. */
    public static MukeParams low() {
        MukeParams p = new MukeParams();
        p.blastRadius = 15F;
        p.killRadius = 45F;
        return p;
    }

    /** Medium-yield mini nuke (the default missile warhead uses this). */
    public static MukeParams medium() {
        MukeParams p = new MukeParams();
        p.blastRadius = 20F;
        p.killRadius = 55F;
        return p;
    }

    /** Damage + visual only, no terrain damage. */
    public static MukeParams safe() {
        MukeParams p = new MukeParams();
        p.safe = true;
        p.killRadius = 45F;
        return p;
    }

    /** Full-size "fatman": delegates to the threaded {@link Nuke} + Torex. */
    public static MukeParams fatman() {
        MukeParams p = new MukeParams();
        p.miniNuke = false;
        p.blastRadius = 35F;
        return p;
    }

    /** Tunables for a mini-nuke blast (WF port of HBM's {@code MukeParams}). */
    public static class MukeParams {
        /** {@code false} = full-size "fatman" nuke (delegates to {@link Nuke#detonate}). */
        public boolean miniNuke = true;
        /** {@code true} = no terrain destruction (kill radius + visual only). */
        public boolean safe = false;
        /** Crater power, in vanilla explosion units. */
        public float blastRadius = 15F;
        /** Entity kill radius, in blocks. */
        public float killRadius = 45F;
        /** Leave fire in the cleared crater. */
        public boolean fire = true;
        /** Block-allocator ray resolution; higher = smoother sphere but a heavier synchronous pass. */
        public int resolution = 32;
    }
}
