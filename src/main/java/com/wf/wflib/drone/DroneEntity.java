package com.wf.wflib.drone;

import com.wf.wflib.block.DroneNetBlock;
import com.wf.wflib.ModEntities;
import com.wf.wflib.api.DetachedAnchors;
import com.wf.wflib.api.DetachedBodyHost;
import com.wf.wflib.api.WFLibAPI;
import com.wf.wflib.api.WFEventType;
import com.wf.wflib.api.WFTelemetry;
import com.wf.wflib.api.WFTelemetryService;
import com.wf.wflib.attitude.MissileAttitude;
import com.wf.wflib.attitude.MissileAttitudeRegistry;
import com.wf.wflib.chunk.MissileChunkLoader;
import com.wf.wflib.drone.ai.DroneAction;
import com.wf.wflib.drone.cam.CameraSpec;
import com.wf.wflib.drone.cam.Datalink;
import com.wf.wflib.drone.ai.DroneBrain;
import com.wf.wflib.drone.ai.DronePlan;
import com.wf.wflib.drone.ai.DroneSnapshot;
import com.wf.wflib.drone.ai.TerrainSampler;
import com.wf.wflib.drone.flight.Airframe;
import com.wf.wflib.drone.flight.Contacts;
import com.wf.wflib.drone.flight.FlightAttitude;
import com.wf.wflib.drone.flight.Multirotor;
import com.wf.wflib.entity.InterceptTarget;
import com.wf.wflib.entity.OBBEntity;
import com.wf.wflib.recon.ContactClass;
import com.wf.wflib.util.OBB;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** An autonomous rotor drone. */
public class DroneEntity extends Entity implements OBBEntity, InterceptTarget, ControllableDrone, DetachedBodyHost {

    /**
     * Ticks a released payload flies before self-detonating, so one dropped over a void still goes off.
     */
    public static final int PAYLOAD_FUSE = 200;
    /**
     * How close a crate must be for a collecting drone to get hold of it.
     */
    public static final double PICKUP_RADIUS = 4.0;
    /** How far below itself a hovering drone will reach for a crate. */
    public static final double PICKUP_REACH_DOWN = 8.0;
    /** How far apart a squad's drones are set down when it launches. */
    public static final double SPAWN_SPACING = 4.0;
    /** Byte units per radian of lean, and per unit of throttle. */
    public static final float TILT_QUANTUM = 90.0f;
    public static final float THROTTLE_QUANTUM = 50.0f;
    /**
     * Downward speed applied to a grounded drone until it is resting, so one that finishes a landing a fraction of
     * a block high settles instead of hovering.
     */
    private static final double SETTLE_SPEED = 0.08;
    /** How far above the surface a drone under power is held. */
    private static final double SKIM_CLEARANCE = 0.5;

    private static final EntityDataAccessor<String> MODEL_ID =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.STRING);
    static final EntityDataAccessor<Byte> STATE =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.BYTE);
    /** What is slung underneath, as flags: {@link #LOAD_CRATE}, {@link #LOAD_PAYLOAD}, {@link #LOAD_MINES}. */
    static final EntityDataAccessor<Byte> LOAD =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.BYTE);
    static final byte LOAD_CRATE = 1;
    static final byte LOAD_PAYLOAD = 2;
    static final byte LOAD_MINES = 4;
    /** Which mine is on the rack, as its <b>model</b> id, and how many are left on it. */
    static final EntityDataAccessor<String> LOAD_ID =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.STRING);
    static final EntityDataAccessor<Byte> LOAD_COUNT =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.BYTE);
    /** Which rotor discs have been shot off, as a bitmask over the airframe's rotor list. */
    static final EntityDataAccessor<Byte> ROTORS_OUT =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.BYTE);
    /** Lean and throttle as flown ({@link #onAttitude}), not guessed by the renderer from velocity. */
    static final EntityDataAccessor<Byte> ROLL =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.BYTE);
    static final EntityDataAccessor<Byte> PITCH =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.BYTE);
    static final EntityDataAccessor<Byte> THROTTLE =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.BYTE);
    /** Remote pilots' parked bodies ({@link DetachedAnchors}). */
    private static final EntityDataAccessor<CompoundTag> ANCHORS =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.COMPOUND_TAG);
    /** Pilot's {@link Datalink} quality, quantised. */
    private static final EntityDataAccessor<Float> LINK =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.FLOAT);
    private static final float LINK_QUANTUM = 1.0f / 64.0f;
    /** Scratch for {@link #refreshObb}, which runs every tick for every one of these that moved. */
    private final Vector3f obbHeading = new Vector3f();
    private final Quaternionf obbOrientation = new Quaternionf();
    private final Quaterniond obbRotation = new Quaterniond();
    private final Vector3d obbScratch = new Vector3d();
    private boolean leavingWorld;
    private final OBB obb = new OBB(new Vector3f(), new Vector3f(), new Quaternionf());
    private final List<OBB> obbList = List.of(this.obb);
    private final MissileChunkLoader chunkLoader = new MissileChunkLoader();
    private final DroneFlight flight = new DroneFlight(this);
    private final DroneRoute route = new DroneRoute(this);
    private final DroneOrders orders = new DroneOrders(this);
    private final DroneHold hold = new DroneHold(this);
    private final DroneDamage damage = new DroneDamage(this);
    private final DroneSquadRole squad = new DroneSquadRole(this);
    private final DetachedAnchors anchors = new DetachedAnchors(this, ANCHORS);

    private DroneBattery battery = new DroneBattery(DroneBattery.DEFAULT_CAPACITY);
    /** Which camera head is bolted on, or null for none. */
    private CameraSpec camera = CameraSpec.RECON;

    /** The fits, in the order the save byte encodes them. */
    private static final CameraSpec[] CAMERA_FITS = {CameraSpec.RECON, CameraSpec.STANDARD, null};
    private PowerProfile power = PowerProfile.DEFAULT;
    // Whose drone this is, stamped at launch from the claim on the ground it left. Null = nobody's.
    private UUID teamId;
    /** Player who ordered the flight (PDA dispatch); hears its datalink whatever the side. */
    @Nullable
    private UUID operatorId;
    /** PDA terminal {@code rename}; null = model path. */
    @Nullable
    private String callsign;

    private WFTelemetry telemetry;
    private boolean telemetryInit;
    private double obbX = Double.NaN, obbY, obbZ, obbYaw, obbTiltX, obbTiltZ;

    public DroneEntity(EntityType<? extends DroneEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.blocksBuilding = false;
    }

    public DroneEntity(Level level, Vec3 pos) {
        this(ModEntities.DRONE.get(), level);
        this.setPos(pos.x, pos.y, pos.z);
        this.route.setExfil(pos);
    }

    @Override
    public void tick() {
        super.tick();

        if (this.level().isClientSide) {
            this.setBoundingBox(this.makeBoundingBox());
            return;
        }

        ServerLevel serverLevel = (ServerLevel) this.level();
        this.initTelemetry();
        this.flight.tick();

        if (this.flight.getDroneState().airborne()) {
            this.chunkLoader.update(this, serverLevel, this.position(), this.getDeltaMovement(), true);
        }

        this.damage.tick(serverLevel);

        if (this.orders.assignment() != null) {
            com.wf.wflib.build.BuildPilot.advance(serverLevel, this);
        }

        LivingEntity pilot = this.pilot();
        this.tickLink(serverLevel, pilot);
        if (pilot != null) {
            this.flyManual(pilot);
        }

        this.battery.drain(this.power.drain(this.flight.getDroneState(), this.flight.getAttitude().throttle(),
                this.massFactor(), this.getDeltaMovement().horizontalDistance()));
    }

    @Override
    public Entity asEntity() {
        return this;
    }

    @Override
    public DroneFlight flight() {
        return this.flight;
    }

    @Override
    public DroneRoute route() {
        return this.route;
    }

    @Override
    public DroneOrders orders() {
        return this.orders;
    }

    public DroneHold hold() {
        return this.hold;
    }

    public DroneDamage damage() {
        return this.damage;
    }

    @Override
    public DroneSquadRole squad() {
        return this.squad;
    }

    /** Entering an airborne state takes the chunk tickets now, not next tick. */
    @Override
    public void onStateChanged(DroneState state) {
        this.entityData.set(STATE, (byte) state.ordinal());
        if (state.airborne() && this.level() instanceof ServerLevel serverLevel) {
            this.chunkLoader.update(this, serverLevel, this.position(), this.getDeltaMovement(), true);
        }
    }

    @Override
    public void onAttitude(FlightAttitude attitude) {
        float yaw = this.headingRadians();
        this.entityData.set(ROLL, quantise(attitude.roll(yaw), TILT_QUANTUM));
        this.entityData.set(PITCH, quantise(attitude.pitch(yaw), TILT_QUANTUM));
        this.entityData.set(THROTTLE, quantise(attitude.throttle(), THROTTLE_QUANTUM));
    }

    private static byte quantise(double value, float quantum) {
        return (byte) Math.max(-127, Math.min(127, Math.round(value * quantum)));
    }

    /**
     * @return lean about the nose axis, radians, as the client sees it.
     */
    public float getRoll() {
        return this.entityData.get(ROLL) / TILT_QUANTUM;
    }

    /**
     * @return lean about the wing axis, radians: positive is nose down.
     */
    public float getPitch() {
        return this.entityData.get(PITCH) / TILT_QUANTUM;
    }

    /**
     * @return thrust as a multiple of an unladen hover, so 1.0 is holding station and 0 is a dead rotor.
     */
    public float getThrottle() {
        return this.entityData.get(THROTTLE) / THROTTLE_QUANTUM;
    }

    @Override
    public double chargeFraction() {
        return this.battery.percent() / 100.0;
    }

    @Override
    public boolean supports(DroneTask.Kind kind) {
        return true;
    }

    void refitBounds() {
        this.setBoundingBox(this.makeBoundingBox());
    }

    @Override
    public DroneSnapshot snapshot(ServerLevel level, long gameTime) {
        Vec3 pos = this.position();
        DroneFlight f = this.flight;
        DroneRoute r = this.route;
        Vec3 destination = r.getDestination();
        double groundY = TerrainSampler.groundY(level, pos.x, pos.z, pos.y - f.getCruiseAltitude());
        double destGroundY = destination == null ? groundY
                : TerrainSampler.groundY(level, destination.x, destination.z, destination.y);
        f.noteGround(groundY);
        return new DroneSnapshot(this.getUUID(), false, pos, this.getDeltaMovement(), this.headingRadians(),
                f.getAttitude(), this.airframe(), r.sampleNav(level, pos, groundY, gameTime),
                f.getDroneState(), f.stateTicks(), destination, r.nextLeg(), this.orders.getProgram(), r.getExfil(),
                this.hold.hasCargo(), this.hold.hasPayload(), this.hold.getMines(), this.orders.isCollecting(),
                this.orders.dropZoneClear(level), this.orders.sweepForContacts(level),
                this.battery.charge(), this.battery.capacity(), this.power,
                f.getCruiseSpeed(), f.getCruiseAltitude(), f.getClimbRate(), f.getReleaseSpeed(),
                groundY, destGroundY, this.squad.squadId(), this.squad.leader(), this.squad.squadSize(),
                this.orders.assignment(), this.damage.rotorDamage(), gameTime,
                this.getUUID().getLeastSignificantBits());
    }

    @Override
    public void apply(ServerLevel level, DronePlan plan) {
        if (this.pilot() != null) {
            return;
        }
        for (DroneAction action : plan.actions()) {
            this.perform(level, action);
        }
        if (plan.nextState() != null) {
            this.flight.setState(plan.nextState());
        }
        if (!this.damage.crashed()) {
            this.flight.setAttitude(plan.attitude());
            this.setHeadingRadians(plan.yaw());
        }
        this.moveWith(plan.velocity());
    }

    @Override
    public void coast(ServerLevel level) {
        if (this.pilot() != null) {
            return;
        }
        this.moveWith(DroneBrain.coast(this.getDeltaMovement()));
    }

    /** Remote pilot (anchored passenger), else null: flies by hand, the brain's plans are dropped meanwhile. */
    @Nullable
    public LivingEntity pilot() {
        for (Entity passenger : this.getPassengers()) {
            if (passenger instanceof LivingEntity living && this.anchors.get(living) != null) {
                return living;
            }
        }
        return null;
    }

    public float link() {
        return this.entityData.get(LINK);
    }

    private void tickLink(ServerLevel level, @Nullable LivingEntity pilot) {
        float link = pilot == null || this.camera == null ? 1.0f
                : Datalink.quality(level, this.camera, Datalink.operatorAt(pilot), this, this.getEyePosition());
        float shown = this.entityData.get(LINK);
        if (Math.abs(link - shown) >= LINK_QUANTUM || link == 1.0f && shown != 1.0f) {
            this.entityData.set(LINK, link);
        }
    }

    /**
     * Stabilised stick: W/S along the look (pitch included), A/D strafe, jump climbs; heading = look yaw.
     * Lost link => hover. Grounded + no climb => stays parked.
     */
    private void flyManual(LivingEntity pilot) {
        Vec3 desired = Vec3.ZERO;
        if (this.battery.isEmpty()) {
            desired = new Vec3(0.0, -this.flight.getClimbRate(), 0.0);
        } else if (Datalink.usable(this.link())) {
            double speed = this.flight.getCruiseSpeed();
            float yaw = pilot.getYRot();
            Vec3 look = Vec3.directionFromRotation(pilot.getXRot(), yaw);
            Vec3 left = Vec3.directionFromRotation(0.0f, yaw - 90.0f);
            desired = look.scale(pilot.zza * speed).add(left.scale(pilot.xxa * speed))
                    .add(0.0, pilot.jumping ? this.flight.getClimbRate() : 0.0, 0.0);
            this.setHeadingRadians((float) -Math.toRadians(yaw));
        }
        DroneState state = this.flight.getDroneState();
        if (!state.airborne()) {
            if (desired.y <= 0.0) {
                this.moveWith(Vec3.ZERO);
                return;
            }
            this.flight.setState(DroneState.SURVEIL);
        } else if (desired.y < 0.0 && this.getY() - this.flight.lastGroundY() < SKIM_CLEARANCE + 0.1) {
            this.flight.setState(DroneState.IDLE);
        }
        Multirotor.Step step = Multirotor.step(this.getDeltaMovement(), this.flight.getAttitude(), desired, Vec3.ZERO,
                this.airframe(), this.massFactor());
        if (!this.damage.crashed()) {
            this.flight.setAttitude(step.attitude());
        }
        this.moveWith(step.velocity());
    }

    @Override
    public boolean isDetachedBodyActive() {
        return !this.anchors.isEmpty();
    }

    @Nullable
    @Override
    public Vec3 getDetachedBodyAnchor(Entity operator) {
        return this.anchors.get(operator);
    }

    @Override
    public void setDetachedBodyAnchor(Entity operator, @Nullable Vec3 anchor) {
        this.anchors.set(operator, anchor);
    }

    @Override
    public void clearDetachedBodyAnchors() {
        this.anchors.clear();
    }

    @Override
    public Collection<Entity> getDetachedOperators() {
        return this.anchors.operators();
    }

    /** Remote pilot's body stays parked at its anchor. */
    @Override
    protected void positionRider(Entity passenger, Entity.MoveFunction callback) {
        Vec3 anchor = this.anchors.get(passenger);
        if (anchor != null) {
            callback.accept(passenger, anchor.x, anchor.y, anchor.z);
            return;
        }
        super.positionRider(passenger, callback);
    }

    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        Vec3 anchor = this.anchors.release(passenger);
        return anchor != null ? anchor : super.getDismountLocationForPassenger(passenger);
    }

    private void moveWith(Vec3 velocity) {
        if (!this.flight.getDroneState().airborne()) {
            this.setDeltaMovement(Vec3.ZERO);
            double rest = this.flight.lastGroundY();
            if (!Double.isNaN(rest) && this.getY() > rest) {
                this.setPos(this.getX(), Math.max(rest, this.getY() - SETTLE_SPEED), this.getZ());
            }
            this.refitBounds();
            this.resolveContacts();
            return;
        }
        if (DroneNetBlock.caught(this.level(), this)) {
            this.setDeltaMovement(Vec3.ZERO);
            return;
        }
        this.setDeltaMovement(velocity);
        this.hasImpulse = true;
        this.move(MoverType.SELF, velocity);
        this.resolveContacts();
        this.clampAboveGround();
        this.refitBounds();
    }

    /** Push out of any drone this one ended the tick inside of. */
    private void resolveContacts() {
        AABB box = this.getBoundingBox();
        List<DroneEntity> touching = this.level().getEntitiesOfClass(DroneEntity.class, box,
                other -> other != this && other.isAlive());
        if (touching.isEmpty()) {
            return;
        }
        Vec3 escape = Vec3.ZERO;
        for (DroneEntity other : touching) {
            escape = escape.add(Contacts.escape(box, other.getBoundingBox(), this.getId() < other.getId()));
        }
        Vec3 step = Contacts.step(escape);
        if (step.lengthSqr() < 1.0E-12) {
            return;
        }
        this.setPos(this.getX() + step.x, this.getY() + step.y, this.getZ() + step.z);
        this.refitBounds();
    }

    /** Refuse to end a tick inside the ground. */
    private void clampAboveGround() {
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        double ground = TerrainSampler.measure(serverLevel, this.getX(), this.getZ());
        if (Double.isNaN(ground)) {
            return;
        }
        this.flight.noteGround(ground);
        double floor = ground + (this.flight.getDroneState().followsTerrain() ? SKIM_CLEARANCE : 0.0);
        if (this.getY() >= floor) {
            return;
        }
        this.setPos(this.getX(), floor, this.getZ());
        Vec3 motion = this.getDeltaMovement();
        if (motion.y < 0.0) {
            this.setDeltaMovement(motion.x, 0.0, motion.z);
        }
    }

    /** Drones are solid bodies. */
    @Override
    public boolean canBeCollidedWith() {
        return true;
    }

    private void perform(ServerLevel level, DroneAction action) {
        switch (action) {
            case DroneAction.DropCargo drop -> this.hold.dropCargo(level, drop.at());
            case DroneAction.PickUpCargo pick -> this.hold.pickUpCargo(level, pick.at());
            case DroneAction.AdvanceLeg ignored -> this.route.advanceLeg();
            case DroneAction.AdvanceTask ignored -> this.orders.advanceTask();
            case DroneAction.AbortProgram abort -> this.orders.abandonProgram(abort.reason());
            case DroneAction.DropPayload drop -> this.hold.dropPayload(level);
            case DroneAction.LayMine lay -> this.hold.layMine(level, lay.aim());
            case DroneAction.CompleteMission complete -> {
                this.route.setDestination(null);
                this.recordEvent(WFEventType.MISSION_COMPLETE, complete.reason());
            }
            case DroneAction.Retarget retarget -> this.route.setDestination(retarget.destination());
            case DroneAction.FinishWork work ->
                    com.wf.wflib.build.BuildPilot.finish(level, this, work.at());
            case DroneAction.ExchangeSupplies ignored ->
                    com.wf.wflib.build.BuildPilot.exchange(level, this);
            case DroneAction.Log log -> this.recordEvent(log.type(), log.detail());
        }
    }

    // --- InterceptTarget: a drone is something air defence may shoot at ---

    /**
     * A wreck on its way down has already been dealt with; shooting it again only wastes the round.
     */
    @Override
    public boolean interceptEngageable() {
        return this.isAlive() && !this.isRemoved() && !this.damage.isDowned();
    }

    @Override
    public ContactClass interceptClass() {
        return ContactClass.DRONE;
    }

    /** A drone answers to no launcher: it is flown by a program or by a pilot, and neither is a control id. */
    @Override
    public UUID interceptControlId() {
        return null;
    }

    @Override
    public UUID interceptTeamId() {
        return this.teamId;
    }

    @Override
    public void interceptDamage(float amount) {
        this.hurt(this.damageSources().generic(), amount);
    }

    @Override
    public void interceptKill() {
        this.damage.shootDown();
    }

    /**
     * @return the faction this drone flies for, or null if it belongs to nobody (a hand-summoned one).
     */
    @Override
    @Nullable
    public UUID getTeamId() {
        return this.teamId;
    }

    /**
     * Stamped once at launch from whoever claims the ground it took off from, so a battery can tell its own side's
     * traffic from somebody else's without asking the drone where it is now.
     */
    @Override
    @Nullable
    public com.wf.wflib.telemetry.Telemetry datalink() {
        return this.orders.isClassified() ? null : new com.wf.wflib.telemetry.Telemetry(
                com.wf.wflib.telemetry.TelemetryKind.DRONE, this.callsign(), this.teamId, this.operatorId);
    }

    @Override
    public String callsign() {
        return this.callsign != null ? this.callsign : this.getModelId().getPath();
    }

    @Override
    public void setCallsign(@Nullable String callsign) {
        this.callsign = callsign;
    }

    @Override
    @Nullable
    public UUID getOperatorId() {
        return this.operatorId;
    }

    @Override
    public void setOperatorId(@Nullable UUID operatorId) {
        this.operatorId = operatorId;
    }

    @Override
    public void setTeamId(@Nullable UUID teamId) {
        this.teamId = teamId;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.level().isClientSide || this.isRemoved()) {
            return false;
        }
        this.damage.hurt(source, amount);
        return true;
    }

    /** Right-click recovery: take the delivery off a drone. */
    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (this.level().isClientSide) {
            return InteractionResult.sidedSuccess(true);
        }
        if (!this.hold.recover(player)) {
            player.displayClientMessage(Component.literal(String.format("%s - %.0f%% battery, nothing aboard",
                    this.flight.getDroneState(), this.battery.percent())), true);
        }
        return InteractionResult.CONSUME;
    }

    /**
     * @return the camera fitted to this drone, or null if it carries none.
     */
    @Nullable
    public CameraSpec cameraSpec() {
        return this.camera;
    }

    public void setCameraSpec(@Nullable CameraSpec spec) {
        this.camera = spec;
    }

    /** Identity-compared on purpose: the fits are the shared constants, not values that happen to match. */
    private static int cameraFitIndex(@Nullable CameraSpec spec) {
        for (int i = 0; i < CAMERA_FITS.length; i++) {
            if (CAMERA_FITS[i] == spec) {
                return i;
            }
        }
        return CAMERA_FITS.length - 1;
    }

    /** The fit as the byte the save format uses. */
    public static byte cameraFitByte(@Nullable CameraSpec spec) {
        return (byte) cameraFitIndex(spec);
    }

    /** @see #cameraFitByte */
    @Nullable
    public static CameraSpec cameraFitOf(byte encoded) {
        return CAMERA_FITS[Math.floorMod(encoded, CAMERA_FITS.length)];
    }

    public DroneBattery battery() {
        return this.battery;
    }

    public PowerProfile power() {
        return this.power;
    }

    /**
     * @return how this drone flies, which is a fact about which airframe it is. Resolved with the model id
     *      rather than held beside it, so a drone cannot end up drawn as one aircraft and flown as another.
     */
    public Airframe airframe() {
        return this.modelRef().airframe();
    }

    /**
     * @return total mass as a multiple of the unladen airframe. Anything slung underneath counts.
     */
    public double massFactor() {
        return this.power.massFactor(this.hold.laden());
    }

    /** Memoised {@link #getModelId}. */
    private record ModelRef(String raw, ResourceLocation id, Airframe airframe) {
    }

    private volatile ModelRef modelRef;

    public ResourceLocation getModelId() {
        return this.modelRef()
                .id();
    }

    private ModelRef modelRef() {
        String raw = this.entityData.get(MODEL_ID);
        ModelRef ref = this.modelRef;
        if (ref == null || !ref.raw().equals(raw)) {
            ResourceLocation id = DroneModels.parse(raw);
            ref = new ModelRef(raw, id, DroneModels.airframe(id));
            this.modelRef = ref;
        }
        return ref;
    }

    public void setModelId(ResourceLocation id) {
        this.entityData.set(MODEL_ID, (id != null ? id : DroneModels.DEFAULT).toString());
    }

    /**
     * @return the airframe's facing as a rotation about {@code +Y}, the form the model and OBB both want.
     *      Vanilla yaw runs the other way, which is why this isn't just {@code getYRot()}.
     */
    /** Camera lens, world: {@link DroneModels#lens} turned to heading; lean ignored. */
    public Vec3 lensPosition(float partialTick) {
        Vec3 lens = DroneModels.lens(this.getModelId());
        double yaw = -Math.toRadians(this.getViewYRot(partialTick));
        double cos = Math.cos(yaw);
        double sin = Math.sin(yaw);
        return this.getPosition(partialTick).add(lens.x * cos + lens.z * sin, lens.y, lens.z * cos - lens.x * sin);
    }

    public float headingRadians() {
        return (float) -Math.toRadians(this.getYRot());
    }

    public void setHeadingRadians(float heading) {
        float degrees = (float) -Math.toDegrees(heading);
        this.setYRot(degrees);
        this.yRotO = degrees;
    }

    private void initTelemetry() {
        if (this.telemetryInit) {
            return;
        }
        this.telemetryInit = true;
        if (WFTelemetryService.autoOpen()) {
            this.telemetry = WFTelemetryService.open(this.getUUID(), this.level().getGameTime());
        }
        if (this.tickCount <= 1) {
            this.recordEvent(WFEventType.SPAWN, "drone " + this.getModelId().getPath());
        }
    }

    public void openTelemetry() {
        this.telemetry = WFLibAPI.openTelemetry(this.getUUID(), this.level().getGameTime());
    }

    public void attachTelemetry(WFTelemetry telemetry) {
        this.telemetry = telemetry;
    }

    @Override
    public void recordEvent(WFEventType type, String detail) {
        if (this.level().isClientSide) {
            return;
        }
        if (this.orders.isClassified()) {
            return;
        }
        WFTelemetryService.record(this.telemetry, this.getUUID(), type, this.level().getGameTime(),
                this.position(), false, detail);
        com.wf.wflib.telemetry.TelemetryLog.post(this, type, detail);
    }

    @Override
    public List<OBB> getOBBs() {
        this.refreshObb();
        return this.obbList;
    }

    /**
     * Rebuilds the body OBB from the airframe mesh and the current pose: yaw from the model's attitude, then the
     * lean the flight model has it holding, so the box a shot has to hit is the shape the drone is actually
     * presenting rather than a level one.
     */
    private void refreshObb() {
        double x = this.getX(), y = this.getY(), z = this.getZ();
        float yaw = this.headingRadians();
        double leanX = this.flight.getAttitude().tiltX();
        double leanZ = this.flight.getAttitude().tiltZ();
        if (x == obbX && y == obbY && z == obbZ && yaw == obbYaw && leanX == obbTiltX && leanZ == obbTiltZ) {
            return;
        }
        obbX = x;
        obbY = y;
        obbZ = z;
        obbYaw = yaw;
        obbTiltX = leanX;
        obbTiltZ = leanZ;

        ResourceLocation modelId = this.getModelId();
        Vec3 dims = DroneModels.hullSize(modelId);
        Vec3 localCenter = DroneModels.hullCenter(modelId);

        MissileAttitude attitude = MissileAttitudeRegistry.get(DroneModels.attitudeId(modelId));
        obbHeading.set((float) Math.sin(yaw), 0.0f, (float) Math.cos(yaw));
        attitude.orientation(obbHeading, obbOrientation);
        Quaterniond rot = FlightAttitude.leanRotationPrecise(leanX, leanZ, obbRotation)
                .mul(obbOrientation.x, obbOrientation.y, obbOrientation.z, obbOrientation.w);

        Vector3d worldCenter = obbScratch.set(localCenter.x, localCenter.y, localCenter.z);
        rot.transform(worldCenter);
        this.obb.setCenter(x + worldCenter.x, y + worldCenter.y, z + worldCenter.z);
        this.obb.setExtents((float) (dims.x * 0.5), (float) (dims.y * 0.5), (float) (dims.z * 0.5));
        this.obb.rotation().set((float) rot.x, (float) rot.y, (float) rot.z, (float) rot.w);
    }

    @Override
    protected AABB makeBoundingBox() {
        if (this.obb == null) {
            return super.makeBoundingBox();
        }
        this.refreshObb();
        Vector3f ext = this.obb.extents();
        if (ext.x < 1.0E-3 && ext.y < 1.0E-3 && ext.z < 1.0E-3) {
            return super.makeBoundingBox();
        }
        return this.obb.bounds();
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public boolean shouldRenderAtSqrDistance(double distance) {
        return true;
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    /**
     * A non-zero pick radius, for the same reason a missile has one: {@code MixinProjectileUtil} inflates the
     * target OBB by it, and without that a missile crossing at seven blocks a tick has to thread a hull under a
     * block tall on its centreline exactly.
     */
    @Override
    public float getPickRadius() {
        return 0.35f;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(MODEL_ID, DroneModels.DEFAULT.toString());
        builder.define(STATE, (byte) DroneState.IDLE.ordinal());
        builder.define(LOAD, (byte) 0);
        builder.define(LOAD_ID, "");
        builder.define(LOAD_COUNT, (byte) 0);
        builder.define(ROTORS_OUT, (byte) 0);
        builder.define(ROLL, (byte) 0);
        builder.define(PITCH, (byte) 0);
        builder.define(THROTTLE, (byte) 0);
        builder.define(ANCHORS, new CompoundTag());
        builder.define(LINK, 1.0f);
    }

    /**
     * Mirror the synced state into {@link DroneFlight}: on the client nothing else writes it ({@code setState} is
     * server-only, {@link #readAdditionalSaveData} never runs).
     */
    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (this.level().isClientSide && STATE.equals(key)) {
            this.flight.syncState(this.entityData.get(STATE));
        }
    }

    /** Handed to the sim, which carries the assignment: its claim stays held. */
    public void leaveWorld() {
        this.leavingWorld = true;
        this.discard();
    }

    @Override
    public void remove(RemovalReason reason) {
        if (this.level() instanceof ServerLevel serverLevel) {
            this.chunkLoader.releaseAll(this, serverLevel);
            if (this.orders.assignment() != null && !this.leavingWorld) {
                com.wf.wflib.work.WorkRegistry.get(serverLevel).abandonAll(this.getUUID());
            }
        }
        super.remove(reason);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.setModelId(DroneModels.parse(tag.getString("ModelId")));
        // /summon reads an empty tag: keep the full pack.
        if (tag.contains("Battery")) {
            this.battery = DroneBattery.load(tag.getCompound("Battery"));
        }
        this.camera = cameraFitOf(tag.getByte("Camera"));
        this.teamId = tag.hasUUID("TeamId") ? tag.getUUID("TeamId") : null;
        this.operatorId = tag.hasUUID("Operator") ? tag.getUUID("Operator") : null;
        this.callsign = tag.contains("Callsign") ? tag.getString("Callsign") : null;
        this.flight.load(tag.getCompound("Flight"));
        this.entityData.set(STATE, (byte) this.flight.getDroneState().ordinal());
        this.route.load(tag.getCompound("Route"));
        this.orders.load(tag.getCompound("Orders"));
        this.hold.load(tag.getCompound("Hold"));
        this.damage.load(tag.getCompound("Damage"));
        this.squad.load(tag.getCompound("Squad"));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putString("ModelId", this.getModelId().toString());
        CompoundTag batteryTag = new CompoundTag();
        this.battery.save(batteryTag);
        tag.put("Battery", batteryTag);
        tag.putByte("Camera", cameraFitByte(this.camera));
        if (this.teamId != null) {
            tag.putUUID("TeamId", this.teamId);
        }
        if (this.operatorId != null) {
            tag.putUUID("Operator", this.operatorId);
        }
        if (this.callsign != null) {
            tag.putString("Callsign", this.callsign);
        }
        tag.put("Flight", this.flight.save());
        tag.put("Route", this.route.save());
        tag.put("Orders", this.orders.save());
        tag.put("Hold", this.hold.save());
        tag.put("Damage", this.damage.save());
        tag.put("Squad", this.squad.save());
    }
}
