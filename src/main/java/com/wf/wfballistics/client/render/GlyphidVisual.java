package com.wf.wfballistics.client.render;

import com.wf.gemrender.gltf.GemRenderGltfModel;
import com.wf.gemrender.gltf.GltfAnimation;
import com.wf.gemrender.render.GemRenderInstance;
import com.wf.gemrender.render.GemRenderInstanceTypes;
import com.wf.gemrender.render.PoseCache;
import com.wf.gemrender.render.PoseLod;
import com.wf.gemrender.render.PosedBound;
import com.wf.wfballistics.client.model.GlyphidModel;
import com.wf.wfballistics.client.model.GlyphidRig;
import com.wf.wfballistics.drone.flight.FlightAttitude;
import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import com.wf.wfballistics.entity.glyphid.GlyphidCaste;
import dev.engine_room.flywheel.api.task.Plan;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.task.SimplePlan;
import dev.engine_room.flywheel.lib.visual.AbstractEntityVisual;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector4f;

/**
 * Draws a glyphid: one shared rig, the caste's skin and size, six legs walking, jaws that snap when it bites, and
 * armour plates that vanish as they are shot off.
 */
public class GlyphidVisual extends AbstractEntityVisual<EntityGlyphid> implements DynamicVisual {

    /** How far a corpse has rolled over by, at most. */
    private static final float DEATH_ROLL = 90.0f;
    /** Ticks the nuclear caste takes to swell to its full size. */
    private static final float SWELL_TICKS = 95.0f;

    private static final int WALK_LAYER = 0;
    private static final int BITE_LAYER = 1;
    private static final int ARMOR_LAYER = 2;

    private final GlyphidCaste caste;
    private final float scale;

    private GemRenderInstance instance;
    /** The infestation overlay, or null until this glyphid is seen to be infected. */
    private GemRenderInstance infested;
    /** Which model the instances were made against. */
    private GlyphidModel.Skin skin;

    /**
     * Per-frame scratch, owned rather than shared: flywheel runs visuals across several threads at once, so a
     * static scratch would be two glyphids writing the same transform.
     */
    private final Matrix4f root = new Matrix4f();
    private final Quaternionf lean = new Quaternionf();
    /** The bound of the animal as it was last posed, in model space, and whether it has been posed at all. */
    private final Vector4f bound = new Vector4f();
    private boolean bounded;
    private final GltfAnimation[] layers = new GltfAnimation[3];
    private final float[] times = new float[3];

    /** State resampled once a tick rather than once a frame. */
    private int sampledTick = Integer.MIN_VALUE;
    private int packedLight;
    private byte armor = EntityGlyphid.FULL_ARMOR;
    private boolean flying;
    private float roll;
    private float pitch;
    /** The tick this glyphid was first seen dying on, or -1. */
    private int deathStartTick = -1;

    public GlyphidVisual(VisualizationContext context, EntityGlyphid entity, GlyphidCaste caste) {
        super(context, entity, 0.0f);
        this.caste = caste;
        this.scale = (float) entity.getGlyphidScale();

        sampleTick();
        updatePose(0.0f, 0);
    }

    /**
     * Re-read everything that only moves on a tick boundary.
     */
    private void sampleTick() {
        sampledTick = entity.tickCount;
        armor = entity.armor();
        flying = entity.isAirborne();
        if (flying) {
            roll = entity.getRoll();
            pitch = entity.getPitch();
        }
        if (deathStartTick < 0 && entity.isDeadOrDying()) {
            deathStartTick = entity.tickCount;
        }
        packedLight = computePackedLight(0.0f);
    }

    private void updatePose(float partialTick, int lod) {
        GlyphidModel.Skin current = GlyphidModel.body();
        if (current == null) {
            release();
            return;
        }
        if (skin != current) {
            release();
            skin = current;
            instance = instancerProvider()
                    .instancer(GemRenderInstanceTypes.SKINNED, current.model()
                            .model())
                    .createInstance();
            instance.colorArgb(0xFFFFFFFF);
            instance.variant(current.variant(caste));
        }

        if (entity.tickCount != sampledTick) {
            sampleTick();
        }

        Vec3i origin = renderOrigin();
        float x = (float) (Mth.lerp(partialTick, entity.xOld, entity.getX()) - origin.getX());
        float y = (float) (Mth.lerp(partialTick, entity.yOld, entity.getY()) - origin.getY());
        float z = (float) (Mth.lerp(partialTick, entity.zOld, entity.getZ()) - origin.getZ());
        float yaw = Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot);

        Matrix4f matrix = root.translation(x, y, z);

        if (flying) {
            float heading = (float) ((entity.getYRot() + 90.0f) * (Math.PI / 180.0));
            double tiltX = roll * Math.cos(heading) + pitch * Math.sin(heading);
            double tiltZ = pitch * Math.cos(heading) - roll * Math.sin(heading);
            matrix.rotate(FlightAttitude.leanRotation(tiltX, tiltZ, lean));
        }

        matrix.rotateY((float) Math.toRadians(180.0f - yaw));

        if (entity.deathTime > 0) {
            float fall = Math.min(Mth.sqrt((entity.deathTime + partialTick - 1.0f) / 20.0f * 1.6f), 1.0f);
            matrix.rotateZ((float) Math.toRadians(fall * DEATH_ROLL));
        }

        matrix.scale(-1.0f, -1.0f, 1.0f);
        if (caste == GlyphidCaste.NUCLEAR && deathStartTick >= 0) {
            swell(matrix, entity.tickCount - deathStartTick + partialTick);
        }
        GlyphidRig.mount(matrix, scale);

        layers[WALK_LAYER] = current.walk()
                .clip();
        times[WALK_LAYER] = current.walk()
                .timeAt(entity.walkAnimation.position(partialTick));
        layers[BITE_LAYER] = current.bite()
                .clip();
        times[BITE_LAYER] = current.bite()
                .timeAt(entity.getAttackAnim(partialTick));
        layers[ARMOR_LAYER] = current.armor(armor);
        times[ARMOR_LAYER] = 0.0f;

        GemRenderGltfModel gltf = current.model();
        PoseCache.Pose posed = PoseCache.getInstance()
                .pose(gltf.layout(), gltf.bounds(), gltf.morphs(), layers, times, lod);

        bound.set(posed.sphere());
        bounded = true;

        write(instance, matrix, posed, packedLight);
        overlay(matrix, posed, packedLight);
    }

    /**
     * The nuclear caste's death: it inflates and flickers over its fuse, which is the only warning anything
     * standing next to it gets.
     */
    private static void swell(Matrix4f matrix, float dying) {
        float swell = dying / SWELL_TICKS;
        float flash = 1.0f + Mth.sin(swell * 100.0f) * swell * 0.01f;
        swell = Mth.clamp(swell, 0.0f, 1.0f);
        swell *= swell;
        swell *= swell;
        float horizontal = (1.0f + swell * 0.4f) * flash;
        matrix.scale(horizontal, (1.0f + swell * 0.1f) / flash, horizontal);
    }

    /** The infestation growing through an infected glyphid, drawn over whatever skin its caste already wears. */
    private void overlay(Matrix4f pose, PoseCache.Pose posed, int light) {
        if (entity.subtype() != EntityGlyphid.TYPE_INFECTED) {
            if (infested != null) {
                infested.delete();
                infested = null;
            }
            return;
        }

        if (infested == null) {
            GlyphidModel.Skin overlay = GlyphidModel.infested();
            if (overlay == null) {
                return;
            }
            // Biased after the body so the decal lands on a depth the body has already written.
            infested = instancerProvider()
                    .instancer(GemRenderInstanceTypes.SKINNED, overlay.model()
                            .model(), 1)
                    .createInstance();
            infested.colorArgb(0xFFFFFFFF);
        }
        write(infested, pose, posed, light);
    }

    private static void write(GemRenderInstance instance, Matrix4f pose, PoseCache.Pose posed, int light) {
        instance.pose.set(pose);
        instance.boneBase = posed.boneBase();
        instance.morphBase = posed.morphBase();
        instance.boneSphere.set(posed.sphere());
        instance.light(light);
        instance.setChanged();
    }

    /** Whether the animal is on screen, tested against the model rather than against its hitbox. */
    @Override
    public boolean isVisible(FrustumIntersection frustum) {
        return super.isVisible(frustum) || (bounded && PosedBound.test(frustum, root, bound));
    }

    private void release() {
        if (instance != null) {
            instance.delete();
            instance = null;
        }
        if (infested != null) {
            infested.delete();
            infested = null;
        }
        skin = null;
        bounded = false;
    }

    @Override
    protected void _delete() {
        release();
    }

    @Override
    public Plan<Context> planFrame() {
        return SimplePlan.of(context -> {
            Vec3 camera = context.camera()
                    .getPosition();
            updatePose(context.partialTick(), PoseLod.getInstance()
                    .levelAt(distanceSquared(camera.x, camera.y, camera.z)));
        });
    }
}
