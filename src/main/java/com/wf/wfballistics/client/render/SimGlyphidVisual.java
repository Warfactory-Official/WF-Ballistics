package com.wf.wfballistics.client.render;

import com.wf.gemrender.gltf.GemRenderGltfModel;
import com.wf.gemrender.gltf.GltfAnimation;
import com.wf.gemrender.render.GemRenderInstance;
import com.wf.gemrender.render.GemRenderInstanceTypes;
import com.wf.gemrender.render.PoseCache;
import com.wf.wfballistics.client.model.GlyphidModel;
import com.wf.wfballistics.client.model.GlyphidRig;
import dev.engine_room.flywheel.api.visual.EffectVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.visual.AbstractVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/** Draws every glyphid that has no entity, in one pass. */
public class SimGlyphidVisual extends AbstractVisual implements SimpleDynamicVisual,
        EffectVisual<SimGlyphidEffect> {

    /**
     * A record is never mid-bite, and never missing a plate.
     */
    private static final float NO_BITE = 0.0f;

    /** One growable pool for every caste, since every caste is the same model. */
    private final List<GemRenderInstance> pool = new ArrayList<>();

    /** The model the pool's instances were made against; a reload replaces it and empties the pool. */
    private GlyphidModel.Skin skin;

    private int used;

    private final Matrix4f root = new Matrix4f();
    private final GltfAnimation[] layers = new GltfAnimation[3];
    private final float[] times = new float[3];

    public SimGlyphidVisual(VisualizationContext ctx, SimGlyphidEffect effect, float partialTick) {
        super(ctx, (Level) effect.level(), partialTick);
    }

    @Override
    public void beginFrame(Context context) {
        SimGlyphids.Ghost[] ghosts = SimGlyphids.ghosts();
        used = 0;
        if (ghosts.length > 0) {
            float alpha = SimGlyphids.alpha(context.partialTick());
            Vec3i origin = renderOrigin();
            for (SimGlyphids.Ghost ghost : ghosts) {
                draw(ghost, alpha, origin);
            }
        }
        for (int i = used; i < pool.size(); i++) {
            pool.get(i)
                    .setZeroTransform()
                    .setChanged();
        }
    }

    private void draw(SimGlyphids.Ghost ghost, float alpha, Vec3i origin) {
        GlyphidModel.Skin skin = GlyphidModel.body();
        if (skin == null) {
            return;
        }

        GemRenderInstance instance = claim(skin);
        float x = (float) (ghost.lerpX(alpha) - origin.getX());
        float y = (float) (ghost.lerpY(alpha) - origin.getY());
        float z = (float) (ghost.lerpZ(alpha) - origin.getZ());

        Matrix4f matrix = root.translation(x, y, z)
                .rotateY((float) Math.toRadians(180.0f - ghost.lerpYaw(alpha)))
                .scale(-1.0f, -1.0f, 1.0f);
        GlyphidRig.mount(matrix, (float) ghost.caste()
                .scale());

        layers[0] = skin.walk()
                .clip();
        times[0] = skin.walk()
                .timeAt(ghost.walk());
        layers[1] = skin.bite()
                .clip();
        times[1] = NO_BITE;
        layers[2] = null;
        times[2] = 0.0f;

        GemRenderGltfModel gltf = skin.model();
        PoseCache.Pose posed = PoseCache.getInstance()
                .pose(gltf.layout(), gltf.bounds(), gltf.morphs(), layers, times, 0);

        instance.pose.set(matrix);
        instance.boneBase = posed.boneBase();
        instance.morphBase = posed.morphBase();
        instance.boneSphere.set(posed.sphere());
        instance.variant(skin.variant(ghost.caste()));
        instance.light(ghost.light());
        instance.setChanged();
    }

    /**
     * @return the next free instance, growing the pool if the swarm has never been this big. A reload
     *      replaces the model behind the skin, which invalidates every instance made from the old one, so the
     *      pool is thrown away with it.
     */
    private GemRenderInstance claim(GlyphidModel.Skin current) {
        if (skin != current) {
            for (GemRenderInstance instance : pool) {
                instance.delete();
            }
            pool.clear();
            skin = current;
        }

        int slot = used++;
        if (slot < pool.size()) {
            return pool.get(slot);
        }

        GemRenderInstance instance = instancerProvider()
                .instancer(GemRenderInstanceTypes.SKINNED, current.model()
                        .model())
                .createInstance();
        instance.colorArgb(0xFFFFFFFF);
        pool.add(instance);
        return instance;
    }

    @Override
    protected void _delete() {
        for (GemRenderInstance instance : pool) {
            instance.delete();
        }
        pool.clear();
        skin = null;
    }
}
