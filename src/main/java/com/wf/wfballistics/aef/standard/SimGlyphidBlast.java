package com.wf.wfballistics.aef.standard;

import com.wf.wfballistics.aef.ExplosionAEF;
import com.wf.wfballistics.entity.glyphid.sim.SimGlyphid;
import com.wf.wfballistics.entity.glyphid.sim.SimGlyphidRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.List;

/**
 * Area damage against the glyphids that have no entity to be found by an entity query.
 *
 * <p>Without this the sim tier is an exploit: a swarm crossing open ground would be immune to the one weapon
 * built to answer it, and the mod's own nuke would blow a hole through two hundred glyphids and kill none of
 * them. Worse, it would be invisible — the blast would kill the front rank, which is real, and the rest
 * would walk out of the fireball.
 *
 * <p>It is also where the tier pays off hardest. A blast over three hundred bodies is three hundred
 * {@code hurt} calls through damage sources, attribute lookups, resistance handlers, hurt animations, sounds
 * and death loot. Against records it is a distance test, one raycast and a float subtraction, and the ones
 * that live promote themselves back to entities on the next tier pass — so what survives a nuke is a real,
 * wounded, angry swarm, and what does not never had to exist.
 *
 * <p><b>Cover is still checked.</b> One ray per record inside the radius, not the seven-node sample the
 * entity path uses: a record has no bounding box for the nodes to be offset around, and the difference
 * between one ray and seven is a fraction of a hit point on something that is at least sixty-four blocks
 * from anybody who could see it. Skipping cover entirely was the alternative and it is wrong in the
 * direction that matters — a swarm sheltering behind a hill would die anyway.
 */
public final class SimGlyphidBlast {

    /**
     * Height above the record's feet the cover ray is aimed at, roughly where a glyphid's back is.
     */
    private static final double EYE = 0.6;

    private SimGlyphidBlast() {
    }

    /**
     * Damage the records this blast reaches. No-op off the server and where there are none.
     *
     * @param processor the blast's own entity processor, so the falloff curve, the range mutator and the
     *                  blast shape are the ones it would have used on an entity
     */
    public static void damage(EntityProcessorCross processor, ExplosionAEF explosion, Level level,
                              double x, double y, double z, float size) {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        List<SimGlyphid> all = SimGlyphidRegistry.get(server).view();
        if (all.isEmpty()) {
            return;
        }
        float radius = processor.blastRadius(explosion, size);
        double limit = radius * radius;
        Vec3 centre = new Vec3(x, y, z);

        for (int i = all.size() - 1; i >= 0; i--) {
            SimGlyphid sim = all.get(i);
            double dx = sim.x - x;
            double dy = sim.y + EYE - y;
            double dz = sim.z - z;
            double distanceSq = dx * dx + dy * dy + dz * dz;
            if (distanceSq > limit) {
                continue;
            }
            if (!processor.withinShape(explosion, sim.x, sim.y + EYE, sim.z, x, y, z)) {
                continue;
            }

            double distanceScaled = Math.sqrt(distanceSq) / radius;
            double density = seen(server, centre, sim);
            if (density <= 0.0) {
                continue;
            }
            double knockback = (1.0 - distanceScaled) * density;
            float hit = processor.calculateDamage(distanceScaled, density, knockback, radius);
            if (hit <= 0.0F) {
                continue;
            }
            if (sim.hurt(hit)) {
                // Dead where it stood. No loot and no death animation, deliberately: a record dies where
                // nobody is, and inventing an item drop for it would be inventing one nobody collects.
                all.remove(i);
            }
        }
        SimGlyphidRegistry.get(server).setDirty();
    }

    /**
     * @return 1 with a clear line to the record, 0 with a block in the way.
     */
    private static double seen(ServerLevel level, Vec3 centre, SimGlyphid sim) {
        Vec3 target = new Vec3(sim.x, sim.y + EYE, sim.z);
        return level.clip(new ClipContext(centre, target, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, CollisionContext.empty())).getType() == HitResult.Type.BLOCK
                ? 0.0 : 1.0;
    }
}
