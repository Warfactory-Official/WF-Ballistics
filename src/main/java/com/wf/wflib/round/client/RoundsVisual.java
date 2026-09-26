package com.wf.wflib.round.client;

import com.wf.gemrender.gltf.GemRenderGltfModel;
import com.wf.gemrender.gltf.GltfAnimation;
import com.wf.gemrender.render.GemRenderInstance;
import com.wf.gemrender.render.GemRenderInstanceTypes;
import com.wf.gemrender.render.PoseCache;
import com.wf.wflib.MissileModels;
import com.wf.wflib.attitude.MissileAttitudeRegistry;
import com.wf.wflib.client.model.PartRigs;
import dev.engine_room.flywheel.api.task.Plan;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visual.EffectVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.task.SimplePlan;
import dev.engine_room.flywheel.lib.visual.AbstractVisual;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** One GemRender instance per modelled round, posed nose-to-velocity every frame. */
final class RoundsVisual extends AbstractVisual implements EffectVisual<RoundsEffect>, DynamicVisual {

    private static final GltfAnimation[] NO_LAYERS = new GltfAnimation[0];
    private static final float[] NO_TIMES = new float[0];

    private final Long2ObjectMap<GemRenderInstance> instances = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet seen = new LongOpenHashSet();
    private final Matrix4f pose = new Matrix4f();
    private final Vector3f heading = new Vector3f();
    private final Quaternionf orientation = new Quaternionf();
    private final BlockPos.MutableBlockPos lightPos = new BlockPos.MutableBlockPos();

    RoundsVisual(VisualizationContext ctx, RoundsEffect effect, float partialTick) {
        super(ctx, (Level) effect.level(), partialTick);
    }

    @Override
    public Plan<Context> planFrame() {
        return SimplePlan.of(context -> this.frame(context.partialTick()));
    }

    private void frame(float pt) {
        Vec3i origin = this.renderOrigin();
        this.seen.clear();
        for (ClientRounds.Round r : ClientRounds.live()) {
            if (r.preset.modelId() == null || RoundRenderers.get(r.preset.id()) != null) {
                continue;
            }
            PartRigs.Rig rig = PartRigs.missile(r.preset.modelId());
            if (rig == null) {
                continue;
            }
            GemRenderInstance instance = this.instances.get(r.key);
            if (instance == null) {
                instance = this.instancerProvider().instancer(GemRenderInstanceTypes.SKINNED, rig.model().model())
                        .createInstance();
                instance.colorArgb(0xFFFFFFFF);
                this.instances.put(r.key, instance);
            }
            this.seen.add(r.key);
            this.heading.set((float) r.vx, (float) r.vy, (float) r.vz);
            if (this.heading.lengthSquared() > 1.0e-8f) {
                this.heading.normalize();
                MissileAttitudeRegistry.get(MissileModels.attitudeId(r.preset.modelId()))
                        .orientation(this.heading, this.orientation);
            }
            this.pose.translation((float) (r.ix(pt) - origin.getX()), (float) (r.iy(pt) - origin.getY()),
                    (float) (r.iz(pt) - origin.getZ())).rotate(this.orientation);
            GemRenderGltfModel gltf = rig.model();
            PoseCache.Pose posed = PoseCache.getInstance().pose(gltf.layout(), gltf.bounds(), gltf.morphs(),
                    NO_LAYERS, NO_TIMES, 0);
            this.lightPos.set(r.x, r.y, r.z);
            instance.pose.set(this.pose);
            instance.boneBase = posed.boneBase();
            instance.morphBase = posed.morphBase();
            instance.boneSphere.set(posed.sphere());
            instance.light(LevelRenderer.getLightColor(this.level, this.lightPos));
            instance.setChanged();
        }
        this.instances.long2ObjectEntrySet().removeIf(e -> {
            if (this.seen.contains(e.getLongKey())) {
                return false;
            }
            e.getValue().delete();
            return true;
        });
    }

    @Override
    protected void _delete() {
        this.instances.values().forEach(GemRenderInstance::delete);
        this.instances.clear();
    }
}
