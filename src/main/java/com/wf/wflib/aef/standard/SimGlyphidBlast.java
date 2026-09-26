package com.wf.wflib.aef.standard;

import com.wf.wflib.aef.ExplosionAEF;
import com.wf.wflib.entity.glyphid.sim.SimGlyphid;
import com.wf.wflib.entity.glyphid.sim.SimGlyphidManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.List;

/** Area damage against the glyphids that have no entity to be found by an entity query. */
public final class SimGlyphidBlast {

    /**
     * Height above the record's feet the cover ray is aimed at, roughly where a glyphid's back is.
     */
    private static final double EYE = 0.6;

    private SimGlyphidBlast() {
    }

    /**
     * Damage the records this blast reaches.
     *
     * @param processor the blast's own entity processor, so the falloff curve, the range mutator and the
     *      blast shape are the ones it would have used on an entity
     */
    public static void damage(EntityProcessorCross processor, ExplosionAEF explosion, Level level,
                              double x, double y, double z, float size) {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        List<SimGlyphid> all = SimGlyphidManager.tier(server).view();
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
                all.remove(i);
            }
        }
        SimGlyphidManager.tier(server).setDirty();
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
