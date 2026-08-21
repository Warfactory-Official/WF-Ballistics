package com.wf.wfballistics.client.render;

import com.wf.wfballistics.ModModels;
import com.wf.wfballistics.attitude.MissileAttitude;
import com.wf.wfballistics.attitude.MissileAttitudeRegistry;
import com.wf.wfballistics.client.anim.JawInstances;
import com.wf.wfballistics.client.anim.RotorInstances;
import com.wf.wfballistics.drone.CrateEntity;
import com.wf.wfballistics.drone.DroneEntity;
import com.wf.wfballistics.drone.DroneModels;
import com.wf.wfballistics.drone.PowerProfile;
import com.wf.wfballistics.drone.flight.Airframe;
import com.wf.wfballistics.drone.flight.FlightAttitude;
import dev.engine_room.flywheel.api.task.Plan;
import dev.engine_room.flywheel.api.task.TaskExecutor;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.visual.AbstractEntityVisual;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Draws a drone: the airframe in the attitude it is actually flying in, its rotor discs turning at the speed
 * its throttle implies, its grippers shut around whatever it is holding, and the crate itself.
 *
 * <p>None of that is invented here. The lean and the throttle are both real flight state computed by the
 * server's flight model and sent over, so a drone leaning hard into a turn is leaning because that lean is
 * what is accelerating it: the picture and the physics cannot drift apart, which they did when this class
 * estimated a tilt from the velocity on its own.
 *
 * <p>The crate is drawn from the drone's own transform rather than by a second entity following it about.
 * A slung {@code CrateEntity} moved on the server every tick and reached the client as ordinary movement
 * packets on its own schedule, several ticks out of step with the drone's, so it visibly lagged and
 * juddered behind the thing carrying it. Held here it cannot: there is one transform, and the crate is part
 * of it. A real crate is only spawned when the drone lets go of it or is shot down.
 */
public class DroneVisual extends AbstractEntityVisual<DroneEntity> implements DynamicVisual {

    /**
     * How quickly the drawn attitude chases the synced one. The lean is quantised into a byte and only
     * arrives when it changes, so a little smoothing hides the steps without adding any lag worth noticing.
     */
    private static final float ATTITUDE_SMOOTHING = 0.35f;
    /**
     * How fast the rotors spool toward the speed the throttle calls for. Rotors have inertia; they also stop
     * dead the moment a drone is shot down, and this is what makes the difference legible.
     */
    private static final float SPOOL_RATE = 0.12f;
    /**
     * How fast the grippers swing, in fractions of their travel per tick: about a third of a second end to
     * end, which is what a claw that size would take.
     */
    private static final float JAW_RATE = 0.18f;
    /**
     * Half the crate's height. The mount names where a load is <em>held</em>, which is its top face, so the
     * crate mesh hangs this far below it. Matches the CrateEntity hitbox, so nothing changes size when the
     * drone finally lets go and a real crate appears.
     */
    private static final float CRATE_HALF = CrateEntity.SIZE / 2.0f;

    private final TransformedInstance body;
    private final RotorInstances rotors;
    private final JawInstances jaws;
    /**
     * The carried crate. Always instanced, and zeroed out on the frames the drone is empty: cheaper and
     * steadier than creating and destroying an instance every time a drone picks something up.
     */
    private final TransformedInstance crate;
    private final Vector3f mount;
    private final MissileAttitude attitude;
    private final Airframe airframe = Airframe.QUADCOPTER;
    /**
     * Per-frame scratch. Owned by this visual rather than shared, because flywheel runs visuals across
     * several threads at once: a static scratch pool would be two drones writing the same quaternion.
     */
    private final Vector3f heading = new Vector3f();
    private final Quaternionf orientation = new Quaternionf();
    private final Quaternionf lean = new Quaternionf();
    private final BlockPos.MutableBlockPos lightPos = new BlockPos.MutableBlockPos();

    private double prevX, prevY, prevZ;
    private double curX, curY, curZ;
    private int lastPosTick = -1;
    private float prevYaw;
    private float curYaw;
    private float roll;
    private float pitch;
    /**
     * Continuous rotor phase, in ticks of turning. Integrated here rather than read off {@code tickCount}
     * because the discs change speed with the throttle, so the angle is the integral of a varying rate and
     * not a product of a fixed one. {@link com.wf.wfballistics.anim.Rotor} turns it into an angle, keeping
     * the counter-rotating pairs' opposite signs.
     */
    private float rotorPhase;
    private float rotorScale;
    /**
     * How far the grippers are open, 0 shut to 1 wide. Driven straight off whether the drone is carrying
     * anything, so the release animation is not a scripted one-shot that could get out of step with the
     * cargo: the claws are open because the crate is gone.
     */
    private float jawOpen;
    private float lastPartialTick;
    /**
     * State resampled once a tick rather than once a frame, in {@link #sampleTick}.
     *
     * <p>None of it can change more often than a tick: it is either synced entity data, which only moves
     * when a packet lands, or derived from the drone's block position. Reading it per frame cost a
     * read-lock on the entity's data table six times over, a cube root, and a chunk lookup for the light,
     * all to arrive at the same answers as the frame before.
     */
    private int sampledTick = Integer.MIN_VALUE;
    private int packedLight;
    private float targetRoll;
    private float targetPitch;
    private float targetRotorScale;
    private float targetJawOpen;
    private boolean carryingCrate;

    public DroneVisual(VisualizationContext context, DroneEntity entity) {
        super(context, entity, 0.0f);

        ResourceLocation modelId = entity.getModelId();
        this.attitude = MissileAttitudeRegistry.get(DroneModels.attitudeId(modelId));
        this.body = context.instancerProvider()
                .instancer(InstanceTypes.TRANSFORMED, Models.partial(ModModels.drone(modelId)))
                .createInstance();
        this.rotors = RotorInstances.create(context, modelId);
        this.jaws = JawInstances.create(context, modelId);
        this.crate = context.instancerProvider()
                .instancer(InstanceTypes.TRANSFORMED, Models.partial(ModModels.CRATE))
                .createInstance();
        Vec3 m = DroneModels.mount(modelId);
        this.mount = new Vector3f((float) m.x, (float) m.y, (float) m.z);

        this.prevX = this.curX = entity.getX();
        this.prevY = this.curY = entity.getY();
        this.prevZ = this.curZ = entity.getZ();
        this.prevYaw = this.curYaw = entity.headingRadians();
        this.lastPosTick = entity.tickCount;
        sampleTick();
        this.roll = this.targetRoll;
        this.pitch = this.targetPitch;
        this.rotorScale = this.targetRotorScale;
        this.jawOpen = this.targetJawOpen;

        updatePose(0.0f);
        this.body.setChanged();
    }

    /**
     * Re-read everything that only moves on a tick boundary. Cheap enough to do unconditionally once a tick
     * and worth not doing at all on the frames in between.
     */
    private void sampleTick() {
        sampledTick = entity.tickCount;
        targetRoll = entity.getRoll();
        targetPitch = entity.getPitch();
        boolean loaded = entity.isLoaded();
        carryingCrate = entity.hasCrateAboard();
        // Grippers shut around a load, open when there is nothing to hold.
        targetJawOpen = loaded ? 0.0f : 1.0f;
        // How fast the discs should be turning, as a multiple of their hover speed: straight from the
        // throttle the server is flying on, through the airframe's own thrust-to-rpm relation.
        targetRotorScale = entity.getDroneState().powered()
                ? (float) airframe.rotorScale(entity.getThrottle(), PowerProfile.DEFAULT.massFactor(loaded))
                : 0.0f;
        lightPos.set(Mth.floor(curX), Mth.floor(curY), Mth.floor(curZ));
        packedLight = LevelRenderer.getLightColor(entity.level(), lightPos);
    }

    private void updatePose(float partialTick) {
        // Frames, not ticks, are what this is called on, so the rotor phase has to advance by however much
        // time has actually passed. Advancing it once per call would tie the rotor speed to the frame rate.
        float elapsed = Math.max(0.0f, entity.tickCount > lastPosTick
                ? (entity.tickCount - lastPosTick) + partialTick - lastPartialTick
                : partialTick - lastPartialTick);
        lastPartialTick = partialTick;

        if (entity.tickCount > lastPosTick) {
            prevX = curX;
            prevY = curY;
            prevZ = curZ;
            prevYaw = curYaw;
            curX = entity.getX();
            curY = entity.getY();
            curZ = entity.getZ();
            curYaw = entity.headingRadians();
            lastPosTick = entity.tickCount;
        }

        if (entity.tickCount != sampledTick) {
            sampleTick();
        }

        roll += (targetRoll - roll) * ATTITUDE_SMOOTHING;
        pitch += (targetPitch - pitch) * ATTITUDE_SMOOTHING;

        // These are rates per tick, so they step by however long the frame was. Easing once per call would
        // have rotors wind up and claws swing faster on a faster machine.
        rotorScale += Mth.clamp(targetRotorScale - rotorScale, -SPOOL_RATE * elapsed, SPOOL_RATE * elapsed);
        rotorPhase += rotorScale * elapsed;
        jawOpen += Mth.clamp(targetJawOpen - jawOpen, -JAW_RATE * elapsed, JAW_RATE * elapsed);

        Vec3i origin = renderOrigin();
        float x = (float) (Mth.lerp(partialTick, prevX, curX) - origin.getX());
        float y = (float) (Mth.lerp(partialTick, prevY, curY) - origin.getY());
        float z = (float) (Mth.lerp(partialTick, prevZ, curZ) - origin.getZ());
        float yaw = prevYaw + wrap(curYaw - prevYaw) * partialTick;

        // Roll and pitch arrive as leans about the airframe's own axes; the flight model works in world-frame
        // lean, so project them back before building the same rotation the hitbox uses.
        double tiltX = roll * Math.cos(yaw) + pitch * Math.sin(yaw);
        double tiltZ = pitch * Math.cos(yaw) - roll * Math.sin(yaw);

        heading.set((float) Math.sin(yaw), 0.0f, (float) Math.cos(yaw));
        attitude.orientation(heading, orientation);
        FlightAttitude.leanRotation(tiltX, tiltZ, lean);

        // Straight into the body instance's matrix, which the parts then read as their parent transform.
        Matrix4f matrix = this.body.pose;
        matrix.translation(x, y, z)
                .rotate(lean)
                .rotate(orientation);

        this.body.light(packedLight);
        this.body.setChanged();
        this.rotors.update(matrix, rotorPhase, packedLight);
        this.jaws.update(matrix, jawOpen, packedLight);
        updateCrate(matrix, packedLight);
    }

    /**
     * Hang the crate off the airframe's payload mount, or zero the instance out when there is nothing to
     * draw. Only an actual crate is drawn: a drone flying a warhead is loaded too, and it has no crate.
     */
    private void updateCrate(Matrix4f body, int packedLight) {
        if (!carryingCrate) {
            this.crate.setZeroTransform();
            this.crate.setChanged();
            return;
        }
        this.crate.pose.set(body).translate(mount.x, mount.y - CRATE_HALF, mount.z);
        this.crate.light(packedLight);
        this.crate.setChanged();
    }

    private static float wrap(float angle) {
        float twoPi = (float) (Math.PI * 2.0);
        angle %= twoPi;
        if (angle >= (float) Math.PI) {
            angle -= twoPi;
        } else if (angle < (float) -Math.PI) {
            angle += twoPi;
        }
        return angle;
    }

    @Override
    protected void _delete() {
        this.body.delete();
        this.rotors.delete();
        this.jaws.delete();
        this.crate.delete();
    }

    @Override
    public Plan<Context> planFrame() {
        return new Plan<>() {
            @Override
            public void execute(TaskExecutor taskExecutor, Context context, Runnable onCompletion) {
                updatePose(context.partialTick());
                onCompletion.run();
            }

            @Override
            public Plan<Context> then(Plan<Context> plan) {
                return null;
            }

            @Override
            public Plan<Context> and(Plan<Context> plan) {
                return null;
            }
        };
    }
}
