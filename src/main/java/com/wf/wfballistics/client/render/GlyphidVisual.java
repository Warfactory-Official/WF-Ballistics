package com.wf.wfballistics.client.render;

import com.wf.wfballistics.client.model.GlyphidModel;
import com.wf.wfballistics.client.model.GlyphidPoses;
import com.wf.wfballistics.client.model.GlyphidRig;
import com.wf.wfballistics.drone.flight.FlightAttitude;
import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import com.wf.wfballistics.entity.glyphid.GlyphidCaste;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.task.Plan;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.task.SimplePlan;
import dev.engine_room.flywheel.lib.visual.AbstractEntityVisual;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * Draws a glyphid: one shared mesh, the caste's skin and size, six legs walking, jaws that snap when it
 * bites, and armour plates that vanish as they are shot off.
 *
 * <p>Instanced rather than drawn part by part because the point of the subsystem is three hundred of them at
 * once. Every joint pose comes out of {@link GlyphidPoses}' tables, so posing one bug is twenty-seven matrix
 * multiplies against its body transform and no trigonometry at all — the difference between a swarm and a
 * slideshow.
 *
 * <p>The body transform is built the long way round — flip, drop, flip back — rather than as the single
 * rotation those three collapse to. The mesh is authored in the frame a vanilla mob model lives in, which is
 * upside down and offset by the {@code 1.5078} a living renderer subtracts; writing each of those out means
 * the numbers here are the ones the mesh was built against, and anything hung off the animal later has an
 * obvious place to go in the chain.
 */
public class GlyphidVisual extends AbstractEntityVisual<EntityGlyphid> implements DynamicVisual {

    /**
     * How far a corpse has rolled over by, at most. Vanilla's, so a dead glyphid ends up on its side like
     * everything else does.
     */
    private static final float DEATH_ROLL = 90.0f;
    /**
     * Ticks the nuclear caste takes to swell to its full size. Deliberately a little longer than its
     * hundred-tick fuse, so it is still visibly growing at the moment it goes off rather than sitting at
     * full size waiting.
     */
    private static final float SWELL_TICKS = 95.0f;

    private final GlyphidCaste caste;
    private final float scale;
    private final TransformedInstance[] parts;
    /**
     * The infestation overlay, or null until this glyphid is seen to be infected. Built on demand rather than
     * up front: it is a second full set of instances, and most bugs never need it.
     */
    private TransformedInstance[] infested;

    /**
     * Per-frame scratch, owned rather than shared: flywheel runs visuals across several threads at once, so a
     * static scratch matrix would be two glyphids writing the same transform.
     */
    private final Matrix4f root = new Matrix4f();
    private final Quaternionf lean = new Quaternionf();

    /**
     * State resampled once a tick rather than once a frame. All of it is synced entity data or derived from
     * the block the bug is standing in, so it cannot change any faster than this, and reading it per frame
     * cost a lock on the data table five times over plus a chunk lookup to arrive at the same answers.
     */
    private int sampledTick = Integer.MIN_VALUE;
    private int packedLight;
    private byte armor = EntityGlyphid.FULL_ARMOR;
    private boolean flying;
    private float roll;
    private float pitch;
    /**
     * The tick this glyphid was first seen dying on, or -1. Read off {@code tickCount} rather than counted,
     * so the distance limiter skipping updates cannot make the animation run slow.
     */
    private int deathStartTick = -1;

    public GlyphidVisual(VisualizationContext context, EntityGlyphid entity, GlyphidCaste caste) {
        super(context, entity, 0.0f);
        this.caste = caste;
        this.scale = (float) entity.getGlyphidScale();

        Model[] models = GlyphidModel.parts(caste);
        this.parts = new TransformedInstance[models.length];
        for (int i = 0; i < models.length; i++) {
            parts[i] = context.instancerProvider()
                    .instancer(InstanceTypes.TRANSFORMED, models[i])
                    .createInstance();
        }

        sampleTick();
        updatePose(0.0f);
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

    private void updatePose(float partialTick) {
        if (parts.length == 0) {
            return;
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
            // The lean is a world-frame tilt of the lift axis, exactly as a drone's is, so it goes on
            // outside the heading: see FlightAttitude. Roll and pitch were projected onto the body's own
            // axes to quantise them for the wire, and this projects them back.
            float heading = (float) ((entity.getYRot() + 90.0f) * (Math.PI / 180.0));
            double tiltX = roll * Math.cos(heading) + pitch * Math.sin(heading);
            double tiltZ = pitch * Math.cos(heading) - roll * Math.sin(heading);
            matrix.rotate(FlightAttitude.leanRotation(tiltX, tiltZ, lean));
        }

        matrix.rotateY((float) Math.toRadians(180.0f - yaw));

        // Vanilla's corpse roll. The nuclear caste never gets here: it replaces tickDeath outright to run its
        // fuse, so its deathTime stays at zero and it stands upright until it goes off.
        if (entity.deathTime > 0) {
            float fall = Math.min(Mth.sqrt((entity.deathTime + partialTick - 1.0f) / 20.0f * 1.6f), 1.0f);
            matrix.rotateZ((float) Math.toRadians(fall * DEATH_ROLL));
        }

        matrix.scale(-1.0f, -1.0f, 1.0f);
        if (caste == GlyphidCaste.NUCLEAR && deathStartTick >= 0) {
            swell(matrix, entity.tickCount - deathStartTick + partialTick);
        }
        GlyphidRig.mount(matrix, scale);

        int light = packedLight;
        GlyphidRig.place(parts, matrix,
                GlyphidPoses.bite(entity.getAttackAnim(partialTick)),
                GlyphidPoses.walk(entity.walkAnimation.position(partialTick)),
                armor, light);
        overlay(light);
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

    /**
     * The infestation growing through an infected glyphid, drawn over whatever skin its caste already wears.
     * Copies the poses rather than recomputing them, so it costs a matrix copy per part and picks up shot-off
     * armour for free.
     */
    private void overlay(int light) {
        boolean infectedNow = entity.subtype() == EntityGlyphid.TYPE_INFECTED;
        if (infested == null) {
            if (!infectedNow) {
                return;
            }
            Model[] models = GlyphidModel.infested();
            if (models.length != parts.length) {
                return;
            }
            infested = new TransformedInstance[models.length];
            for (int i = 0; i < models.length; i++) {
                // Biased after the body so the decal lands on a depth the body has already written.
                infested[i] = instancerProvider()
                        .instancer(InstanceTypes.TRANSFORMED, models[i], 1)
                        .createInstance();
            }
        }
        for (int i = 0; i < infested.length; i++) {
            if (infectedNow) {
                infested[i].pose.set(parts[i].pose);
            } else {
                infested[i].setZeroTransform();
            }
            infested[i].light(light);
            infested[i].setChanged();
        }
    }

    @Override
    protected void _delete() {
        for (TransformedInstance part : parts) {
            part.delete();
        }
        if (infested != null) {
            for (TransformedInstance part : infested) {
                part.delete();
            }
        }
    }

    @Override
    public Plan<Context> planFrame() {
        return SimplePlan.of(context -> {
            // Off-screen bugs keep the pose they were last drawn in. Flywheel runs this plan for every
            // visual in range whether or not it is in front of the camera, and a swarm behind you is still
            // three hundred rigs' worth of work.
            if (!isVisible(context.frustum())) {
                return;
            }
            Vec3 camera = context.camera().getPosition();
            if (!context.limiter().shouldUpdate(distanceSquared(camera.x, camera.y, camera.z))) {
                return;
            }
            updatePose(context.partialTick());
        });
    }
}
