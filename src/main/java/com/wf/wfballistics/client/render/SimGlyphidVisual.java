package com.wf.wfballistics.client.render;

import com.wf.wfballistics.client.model.GlyphidModel;
import com.wf.wfballistics.client.model.GlyphidPoses;
import com.wf.wfballistics.client.model.GlyphidRig;
import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import com.wf.wfballistics.entity.glyphid.GlyphidCaste;
import dev.engine_room.flywheel.api.instance.Instancer;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.visual.EffectVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.visual.AbstractVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws every glyphid that has no entity, in one pass.
 *
 * <p>Same mesh, same skins, same pose tables and the same twenty-seven instance slots per bug as
 * {@code GlyphidVisual} — see {@link GlyphidRig}, which is the half of that class both tiers share. A record
 * and a body are meant to be indistinguishable on screen, and the only way to be sure of that is for them to
 * be drawn by the same code.
 *
 * <p><b>A pool per caste, not an object per glyphid.</b> Records appear and disappear as a swarm crosses the
 * promotion boundary; creating and deleting flywheel instances at that rate would churn the instancer's
 * buffers every few seconds. Instead each caste keeps a growing pool of rigs, a frame fills as many as it
 * needs and collapses the rest to nothing, and the pool settles at the size of the largest swarm seen.
 *
 * <p>What a record does not have, it does not draw: no bite (nothing to bite), no corpse roll (it promotes
 * before it can die), no flight lean (a flying glyphid is never a record), no infestation overlay. The walk
 * cycle and the armour plates are the two that survive, because both are visible at the distance a record
 * lives at.
 */
public class SimGlyphidVisual extends AbstractVisual implements SimpleDynamicVisual,
        EffectVisual<SimGlyphidEffect> {

    /**
     * A record is never mid-bite, so every one of them shares the jaw pose at phase zero. Looked up once
     * rather than per glyphid per frame.
     */
    private static final float NO_BITE = 0.0f;

    /**
     * One growable pool of rigs per caste, indexed by {@link GlyphidCaste#ordinal()}.
     */
    private final List<TransformedInstance[]>[] pools;
    private final int[] used;
    private final Matrix4f root = new Matrix4f();

    @SuppressWarnings("unchecked")
    public SimGlyphidVisual(VisualizationContext ctx, SimGlyphidEffect effect, float partialTick) {
        super(ctx, (Level) effect.level(), partialTick);
        this.pools = new List[GlyphidCaste.VALUES.length];
        this.used = new int[GlyphidCaste.VALUES.length];
        for (int i = 0; i < pools.length; i++) {
            pools[i] = new ArrayList<>();
        }
    }

    @Override
    public void beginFrame(Context context) {
        SimGlyphids.Ghost[] ghosts = SimGlyphids.ghosts();
        java.util.Arrays.fill(used, 0);
        if (ghosts.length > 0) {
            float alpha = SimGlyphids.alpha(context.partialTick());
            Vec3i origin = renderOrigin();
            Matrix4f[] bite = GlyphidPoses.bite(NO_BITE);
            for (SimGlyphids.Ghost ghost : ghosts) {
                draw(ghost, alpha, origin, bite);
            }
        }
        // Anything the pools held over from a bigger frame is collapsed rather than deleted, so a swarm that
        // shrinks and grows again reuses the same instances instead of churning the instancer.
        for (int caste = 0; caste < pools.length; caste++) {
            List<TransformedInstance[]> pool = pools[caste];
            for (int i = used[caste]; i < pool.size(); i++) {
                GlyphidRig.hide(pool.get(i));
            }
        }
    }

    private void draw(SimGlyphids.Ghost ghost, float alpha, Vec3i origin, Matrix4f[] bite) {
        TransformedInstance[] parts = claim(ghost.caste());
        if (parts.length == 0) {
            return;
        }
        float x = (float) (ghost.lerpX(alpha) - origin.getX());
        float y = (float) (ghost.lerpY(alpha) - origin.getY());
        float z = (float) (ghost.lerpZ(alpha) - origin.getZ());

        Matrix4f matrix = root.translation(x, y, z)
                .rotateY((float) Math.toRadians(180.0f - ghost.lerpYaw(alpha)))
                .scale(-1.0f, -1.0f, 1.0f);
        GlyphidRig.mount(matrix, (float) ghost.caste().scale());

        GlyphidRig.place(parts, matrix, bite, GlyphidPoses.walk(ghost.walk()),
                EntityGlyphid.FULL_ARMOR, ghost.light());
    }

    /**
     * @return the next free rig of this caste, growing the pool if the swarm has never been this big.
     */
    private TransformedInstance[] claim(GlyphidCaste caste) {
        int ordinal = caste.ordinal();
        List<TransformedInstance[]> pool = pools[ordinal];
        int slot = used[ordinal]++;
        if (slot < pool.size()) {
            return pool.get(slot);
        }
        Model[] models = GlyphidModel.parts(caste);
        TransformedInstance[] parts = new TransformedInstance[models.length];
        for (int i = 0; i < models.length; i++) {
            Instancer<TransformedInstance> instancer =
                    instancerProvider().instancer(InstanceTypes.TRANSFORMED, models[i]);
            parts[i] = instancer.createInstance();
        }
        pool.add(parts);
        return parts;
    }

    @Override
    protected void _delete() {
        for (List<TransformedInstance[]> pool : pools) {
            for (TransformedInstance[] parts : pool) {
                for (TransformedInstance part : parts) {
                    part.delete();
                }
            }
            pool.clear();
        }
    }
}
