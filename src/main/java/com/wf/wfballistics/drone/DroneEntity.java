package com.wf.wfballistics.drone;

import com.mojang.logging.LogUtils;
import com.wf.wfballistics.ModEntities;
import com.wf.wfballistics.api.WFBallisticsAPI;
import com.wf.wfballistics.api.WFEventType;
import com.wf.wfballistics.api.WFTelemetry;
import com.wf.wfballistics.api.WFTelemetryService;
import com.wf.wfballistics.attitude.MissileAttitude;
import com.wf.wfballistics.attitude.MissileAttitudeRegistry;
import com.wf.wfballistics.chunk.MissileChunkLoader;
import com.wf.wfballistics.drone.ai.DroneAction;
import com.wf.wfballistics.drone.cam.CameraSpec;
import com.wf.wfballistics.drone.ai.DroneCarrier;
import com.wf.wfballistics.drone.ai.DroneBrain;
import com.wf.wfballistics.drone.ai.DronePlan;
import com.wf.wfballistics.drone.ai.DroneNav;
import com.wf.wfballistics.drone.ai.DroneSnapshot;
import com.wf.wfballistics.drone.ai.TerrainSampler;
import com.wf.wfballistics.anim.Rotor;
import com.wf.wfballistics.anim.Rotors;
import com.wf.wfballistics.drone.flight.Airframe;
import com.wf.wfballistics.drone.flight.Contacts;
import com.wf.wfballistics.drone.flight.FlightAttitude;
import com.wf.wfballistics.drone.nav.DroneNavigation;
import com.wf.wfballistics.drone.nav.DronePath;
import com.wf.wfballistics.drone.nav.TerrainField;
import com.wf.wfballistics.drone.ai.coord.CoordinationModels;
import com.wf.wfballistics.drone.squad.Formation;
import com.wf.wfballistics.drone.squad.Formations;
import com.wf.wfballistics.entity.BombletEntity;
import com.wf.wfballistics.exchange.ExchangeManager;
import com.wf.wfballistics.item.MinePreset;
import com.wf.wfballistics.item.MinePresetRegistry;
import com.wf.wfballistics.mine.MineEntity;
import com.wf.wfballistics.warhead.WarheadRegistry;
import com.wf.wfballistics.work.WorkAssignment;
import com.wf.wfballistics.block.entity.DronePadBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Containers;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import com.wf.wfballistics.entity.InterceptTarget;
import com.wf.wfballistics.entity.OBBEntity;
import com.wf.wfballistics.recon.ContactClass;
import com.wf.wfballistics.util.OBB;
import net.minecraft.core.NonNullList;
import net.minecraft.core.particles.ParticleTypes;
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
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
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
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** An autonomous rotor drone. */
public class DroneEntity extends Entity implements OBBEntity, InterceptTarget, DroneCarrier {

    public static final double DEFAULT_CRUISE_SPEED = 0.75;
    public static final double DEFAULT_CRUISE_ALTITUDE = 40.0;
    public static final double DEFAULT_CLIMB_RATE = 0.35;
    /**
     * Speed an attack run is flown at. Faster than cruise: the payload's throw comes from the airframe.
     */
    public static final double DEFAULT_RELEASE_SPEED = 1.2;
    public static final float DEFAULT_HEALTH = 20.0f;
    /** A hit worth at least this much takes a rotor disc off. */
    public static final float ROTOR_LOSS_DAMAGE = 4.0f;
    /** How far past dead a hit has to carry to leave no airframe at all. */
    public static final float OVERKILL_HEALTH = 15.0f;
    /** Blast size when a wreck hits the ground. */
    public static final float CRASH_BLAST = 1.4f;
    /** How far off level a wreck can come to rest, radians. */
    private static final double CRASH_TILT = Math.toRadians(35.0);
    /** Height above the ground at which a falling wreck counts as having arrived. */
    private static final double CRASH_CONTACT = 0.4;
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
    /** How far around the point it was sent to watch a surveillance drone reports contacts from. */
    public static final double WATCH_RADIUS = 48.0;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final EntityDataAccessor<String> MODEL_ID =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Byte> STATE =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.BYTE);
    /** What is slung underneath, as flags: {@link #LOAD_CRATE}, {@link #LOAD_PAYLOAD}, {@link #LOAD_MINES}. */
    private static final EntityDataAccessor<Byte> LOAD =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.BYTE);
    private static final byte LOAD_CRATE = 1;
    private static final byte LOAD_PAYLOAD = 2;
    private static final byte LOAD_MINES = 4;
    /** Which mine is on the rack, as its <b>model</b> id, and how many are left on it. */
    private static final EntityDataAccessor<String> LOAD_ID =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Byte> LOAD_COUNT =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.BYTE);
    /**
     * The airframe's lean and throttle, sent to the client so the model is drawn in the attitude it is actually
     * flying in rather than one the renderer guessed at from the velocity.
     */
    /** Which rotor discs have been shot off, as a bitmask over the airframe's rotor list. */
    private static final EntityDataAccessor<Byte> ROTORS_OUT =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> ROLL =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> PITCH =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> THROTTLE =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.BYTE);
    /**
     * Byte units per radian of lean, and per unit of throttle.
     */
    public static final float TILT_QUANTUM = 90.0f;
    public static final float THROTTLE_QUANTUM = 50.0f;
    /**
     * Downward speed applied to a grounded drone until it is resting, so one that finishes a landing a fraction of
     * a block high settles instead of hovering.
     */
    private static final double SETTLE_SPEED = 0.08;
    /** How far apart a squad's drones are set down when it launches. */
    public static final double SPAWN_SPACING = 4.0;
    /** How far above the surface a drone under power is held. */
    private static final double SKIM_CLEARANCE = 0.5;
    /**
     * "No route has ever been asked for", distinct from any real game time.
     */
    private static final long NEVER = Long.MIN_VALUE;

    /** Scratch for {@link #refreshObb}, which runs every tick for every one of these that moved. */
    private final Vector3f obbHeading = new Vector3f();
    private final Quaternionf obbOrientation = new Quaternionf();
    private final Quaterniond obbRotation = new Quaterniond();
    private final Vector3d obbScratch = new Vector3d();
    private final OBB obb = new OBB(new Vector3d(), new Vector3d(), new Quaterniond(), OBB.Part.BODY);
    private final List<OBB> obbList = List.of(this.obb);
    private final MissileChunkLoader chunkLoader = new MissileChunkLoader();
    /**
     * What this drone is doing for a construction or salvage job, or null if it is not on one.
     */
    @Nullable
    private WorkAssignment assignment;

    private DroneState state = DroneState.IDLE;
    private int stateTicks;
    private DroneBattery battery = new DroneBattery(DroneBattery.DEFAULT_CAPACITY);
    /** Which camera head is bolted on, or null for none. */
    private CameraSpec camera = CameraSpec.RECON;

    /** The fits, in the order the save byte encodes them. */
    private static final CameraSpec[] CAMERA_FITS = {CameraSpec.RECON, CameraSpec.STANDARD, null};
    private PowerProfile power = PowerProfile.DEFAULT;
    /** How the airframe is leaning and how hard the rotors are working. */
    private FlightAttitude attitude = FlightAttitude.STOPPED;
    /** The route it is following over the terrain, planned off-thread. */
    @Nullable
    private DronePath path;
    /** Staging waypoints still to be flown before the current destination. */
    private final ArrayDeque<Vec3> legs = new ArrayDeque<>();
    /** The dogleg to fly <em>after</em> the drop, installed into {@link #legs} the moment the cargo is released. */
    private final List<Vec3> egressPlan = new ArrayList<>();
    /**
     * True on a mission that is trying to hide: no telemetry is kept, and the listing redacts it.
     */
    private boolean classified;
    /**
     * True if this drone is going to its destination to pick a crate up rather than put one down.
     */
    private boolean collecting;
    /**
     * The exchange this drone is flying for, if any, and the station that sent it.
     */
    @Nullable
    private UUID exchangeId;
    // Whose drone this is, stamped at launch from the claim on the ground it left. Null = nobody's.
    private UUID teamId;
    @Nullable
    private String stationCode;
    /**
     * When the last route search was ordered, so one already running is not ordered again every tick.
     */
    private long pathRequestedAt = NEVER;

    @Nullable
    private Vec3 destination;
    private Vec3 exfil = Vec3.ZERO;
    /** The queued mission steps. */
    private DroneProgram program = DroneProgram.EMPTY;
    /**
     * Who a watching drone has already called in, so the same player standing in the same field is reported once
     * rather than every tick.
     */
    private final Set<UUID> seenContacts = new HashSet<>();
    /** Whether a crate is gripped in the claws, and what is in it. */
    private boolean hasCrate;
    private final NonNullList<ItemStack> cargo =
            NonNullList.withSize(CrateEntity.SLOTS, ItemStack.EMPTY);
    /** Warhead slung under a strike drone, by registered id. */
    @Nullable
    private ResourceLocation payloadId;
    /** The rack of mines slung under a minelayer, or null on a drone that carries none. */
    @Nullable
    private MineLoad mines;
    private double releaseSpeed = DEFAULT_RELEASE_SPEED;
    private float health = DEFAULT_HEALTH;
    // True once a wreck has hit the ground and been laid out where it stopped: see crash().
    private boolean crashed;

    private long squadId;
    private boolean leader;
    private ResourceLocation formationId = Formations.DEFAULT;
    private double formationSpacing = Formation.DEFAULT_SPACING;
    private ResourceLocation coordinationId = CoordinationModels.DEFAULT;
    /** How many drones the mission ordered. */
    private int squadSize = 1;

    private double cruiseSpeed = DEFAULT_CRUISE_SPEED;
    private double cruiseAltitude = DEFAULT_CRUISE_ALTITUDE;
    private double climbRate = DEFAULT_CLIMB_RATE;

    private WFTelemetry telemetry;
    private boolean telemetryInit;
    private double obbX = Double.NaN, obbY, obbZ, obbYaw, obbTiltX, obbTiltZ;
    /** Surface height under the drone as of the last snapshot. */
    private double lastGroundY = Double.NaN;

    public DroneEntity(EntityType<? extends DroneEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.blocksBuilding = false;
    }

    public DroneEntity(Level level, Vec3 pos) {
        this(ModEntities.DRONE.get(), level);
        this.setPos(pos.x, pos.y, pos.z);
        this.exfil = pos;
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
        this.stateTicks++;

        if (this.state.airborne()) {
            this.chunkLoader.update(this, serverLevel, this.position(), this.getDeltaMovement(), true);
        }

        if (this.state == DroneState.DOWNED && !this.crashed) {
            double ground = TerrainSampler.measure(serverLevel, this.getX(), this.getZ());
            if (!Double.isNaN(ground) && this.getY() - ground <= CRASH_CONTACT) {
                this.crash(serverLevel);
            }
        }

        if (this.assignment != null) {
            com.wf.wfballistics.build.BuildPilot.advance(serverLevel, this);
        }

        this.battery.drain(this.power.drain(this.state, this.attitude.throttle(), this.massFactor(),
                this.getDeltaMovement().horizontalDistance()));
    }

    @Override
    public UUID droneId() {
        return this.getUUID();
    }

    @Override
    public long squadId() {
        return this.squadId;
    }

    @Override
    public boolean leader() {
        return this.leader;
    }

    @Override
    public void promote() {
        this.leader = true;
        this.recordEvent(WFEventType.PROMOTED, "took over the squad");
    }

    @Override
    public ResourceLocation formationId() {
        return this.formationId;
    }

    @Override
    public double formationSpacing() {
        return this.formationSpacing;
    }

    /** How far apart this drone's squad flies. */
    public void setFormationSpacing(double spacing) {
        this.formationSpacing = Formation.clampSpacing(spacing);
    }

    @Override
    public ResourceLocation coordinationId() {
        return this.coordinationId;
    }

    /** Which architecture this drone's squad holds its formation with. */
    public void setCoordinationId(@Nullable ResourceLocation id) {
        this.coordinationId = id != null ? CoordinationModels.parse(id.toString()) : CoordinationModels.DEFAULT;
    }

    public int squadSize() {
        return this.squadSize;
    }

    /** How many drones this drone should expect to be flying with. */
    public void setSquadSize(int size) {
        this.squadSize = Math.max(1, size);
    }

    @Override
    public boolean carrierAlive() {
        return this.isAlive() && !this.isRemoved();
    }

    @Override
    public DroneSnapshot snapshot(ServerLevel level, long gameTime) {
        Vec3 pos = this.position();
        double groundY = TerrainSampler.groundY(level, pos.x, pos.z, pos.y - this.cruiseAltitude);
        double destGroundY = this.destination == null ? groundY
                : TerrainSampler.groundY(level, this.destination.x, this.destination.z, this.destination.y);
        this.lastGroundY = groundY;
        return new DroneSnapshot(this.getUUID(), false, pos, this.getDeltaMovement(), this.headingRadians(),
                this.attitude, this.airframe(), this.sampleNav(level, pos, groundY, gameTime),
                this.state, this.stateTicks, this.destination, this.legs.peek(), this.program, this.exfil,
                this.hasCargo(), this.hasPayload(), this.mines, this.collecting, this.dropZoneClear(level),
                this.sweepForContacts(level),
                this.battery.charge(), this.battery.capacity(), this.power,
                this.cruiseSpeed, this.cruiseAltitude, this.climbRate, this.releaseSpeed,
                groundY, destGroundY, this.squadId, this.leader, this.squadSize, this.assignment,
                this.rotorDamage(), gameTime, this.getUUID().getLeastSignificantBits());
    }

    /** Everything the planner needs to know about the ground, read here because a worker cannot read it itself. */
    private DroneNav sampleNav(ServerLevel level, Vec3 pos, double groundY, long gameTime) {
        Vec3 goal = DroneNavigation.goal(this.state, this.legs.peek(), this.destination, this.exfil);
        boolean overdue = this.pathRequestedAt == NEVER
                || gameTime - this.pathRequestedAt > DroneNavigation.PLAN_RETRY_TICKS;
        boolean due = overdue && DroneNavigation.needsPlan(this.path, pos, goal, gameTime);
        TerrainField field = due ? DroneNavigation.field(level, pos, goal, groundY) : null;
        if (field != null) {
            this.pathRequestedAt = gameTime;
        }
        double planningSpeed = Math.max(this.cruiseSpeed, this.releaseSpeed);
        double required = this.state.followsTerrain()
                ? DroneNavigation.requiredAltitude(level, pos, this.getDeltaMovement(), planningSpeed,
                this.climbRate, groundY)
                : Double.NEGATIVE_INFINITY;
        return new DroneNav(this.path, field, required);
    }

    @Override
    public void apply(ServerLevel level, DronePlan plan) {
        for (DroneAction action : plan.actions()) {
            this.perform(level, action);
        }
        if (plan.nextState() != null) {
            this.setState(plan.nextState());
        }
        if (!this.crashed) {
            this.setAttitude(plan.attitude());
            this.setHeadingRadians(plan.yaw());
        }
        this.moveWith(plan.velocity());
    }

    @Override
    public void coast(ServerLevel level) {
        this.moveWith(DroneBrain.coast(this.getDeltaMovement()));
    }

    @Override
    public void adoptPath(DronePath path) {
        this.path = path;
    }

    private void moveWith(Vec3 velocity) {
        if (!this.state.airborne()) {
            this.setDeltaMovement(Vec3.ZERO);
            double rest = this.lastGroundY;
            if (!Double.isNaN(rest) && this.getY() > rest) {
                this.setPos(this.getX(), Math.max(rest, this.getY() - SETTLE_SPEED), this.getZ());
            }
            this.setBoundingBox(this.makeBoundingBox());
            this.resolveContacts();
            return;
        }
        this.setDeltaMovement(velocity);
        this.hasImpulse = true;
        this.move(MoverType.SELF, velocity);
        this.resolveContacts();
        this.clampAboveGround();
        this.setBoundingBox(this.makeBoundingBox());
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
        this.setBoundingBox(this.makeBoundingBox());
    }

    /** Drones are solid bodies. */
    @Override
    public boolean canBeCollidedWith() {
        return true;
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
        this.lastGroundY = ground;
        double floor = ground + (this.state.followsTerrain() ? SKIM_CLEARANCE : 0.0);
        if (this.getY() >= floor) {
            return;
        }
        this.setPos(this.getX(), floor, this.getZ());
        Vec3 motion = this.getDeltaMovement();
        if (motion.y < 0.0) {
            this.setDeltaMovement(motion.x, 0.0, motion.z);
        }
    }

    /**
     * @return false if anyone is standing near the drop point.
     */
    private boolean dropZoneClear(ServerLevel level) {
        if (!this.classified || this.destination == null) {
            return true;
        }
        double radiusSqr = ExchangeManager.WATCH_RADIUS * ExchangeManager.WATCH_RADIUS;
        for (Player player : level.players()) {
            if (player.isSpectator() || player.isDeadOrDying()) {
                continue;
            }
            if (player.distanceToSqr(this.destination) <= radiusSqr) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return everyone who has come into view of a watching drone since the last time it looked.
     */
    private List<String> sweepForContacts(ServerLevel level) {
        if (this.state != DroneState.SURVEIL || this.destination == null) {
            if (!this.seenContacts.isEmpty()) {
                this.seenContacts.clear();
            }
            return List.of();
        }
        double radiusSqr = WATCH_RADIUS * WATCH_RADIUS;
        List<String> fresh = null;
        for (Player player : level.players()) {
            if (player.isSpectator() || player.isDeadOrDying()) {
                continue;
            }
            if (player.distanceToSqr(this.destination) > radiusSqr) {
                continue;
            }
            if (this.seenContacts.add(player.getUUID())) {
                if (fresh == null) {
                    fresh = new ArrayList<>(2);
                }
                fresh.add(player.getGameProfile().getName());
            }
        }
        return fresh == null ? List.of() : fresh;
    }

    private void perform(ServerLevel level, DroneAction action) {
        switch (action) {
            case DroneAction.DropCargo drop -> this.dropCargo(level, drop.at());
            case DroneAction.PickUpCargo pick -> this.pickUpCargo(level, pick.at());
            case DroneAction.AdvanceLeg ignored -> this.legs.poll();
            case DroneAction.AdvanceTask ignored -> this.advanceTask();
            case DroneAction.AbortProgram abort -> {
                this.program = this.program.abandoned();
                this.recordEvent(WFEventType.MISSION_COMPLETE, "program abandoned: " + abort.reason());
            }
            case DroneAction.DropPayload drop -> this.dropPayload(level);
            case DroneAction.LayMine lay -> this.layMine(level, lay.aim());
            case DroneAction.CompleteMission complete -> {
                this.destination = null;
                this.path = null;
                this.recordEvent(WFEventType.MISSION_COMPLETE, complete.reason());
            }
            case DroneAction.Retarget retarget -> {
                this.destination = retarget.destination();
                this.path = null;
            }
            case DroneAction.FinishWork work ->
                    com.wf.wfballistics.build.BuildPilot.finish(level, this, work.at());
            case DroneAction.ExchangeSupplies ignored ->
                    com.wf.wfballistics.build.BuildPilot.exchange(level, this);
            case DroneAction.Log log -> this.recordEvent(log.type(), log.detail());
        }
    }

    /**
     * Deliver the crate: put it down on purpose, at the aim point, and count it as a delivery.
     */
    private void dropCargo(ServerLevel level, Vec3 at) {
        CrateEntity crate = releaseCargo(level, at);
        if (crate == null) {
            return;
        }
        ExchangeManager.onCargoDropped(level, this, crate);
        this.beginEgress();
    }

    /**
     * Turn the held cargo into a real crate entity and let go of it.
     *
     * @param at where the crate is aimed. It is born where it was being drawn, in the grippers, and only
     *      then moved over {@code at}, keeping whichever height is greater, so it falls from the drone
     *      rather than materialising on the ground underneath it
     * @return the crate, or null if there was nothing aboard
     */
    @Nullable
    private CrateEntity releaseCargo(ServerLevel level, Vec3 at) {
        if (!this.hasCrate) {
            return null;
        }
        CrateEntity crate = new CrateEntity(level, this.gripPos());
        for (int i = 0; i < this.cargo.size(); i++) {
            crate.items().set(i, this.cargo.get(i));
        }
        level.addFreshEntity(crate);
        crate.release(at);
        this.setCrate(false);
        return crate;
    }

    /**
     * @return where a crate held in the grippers sits in the world, as a crate entity's position (its feet).
     *      The airframe's mount says where the load's <em>top</em> is held, so the body of it hangs below that.
     */
    private Vec3 gripPos() {
        Vec3 mount = DroneModels.mount(this.getModelId());
        return this.position().add(mount.x, mount.y - CrateEntity.SIZE, mount.z);
    }

    /**
     * Swap the remaining route for the egress dogleg. Does nothing on a direct mission, which has none.
     */
    private void beginEgress() {
        this.legs.clear();
        this.legs.addAll(this.egressPlan);
        this.egressPlan.clear();
    }

    /**
     * Collect a crate somebody else left here. The other half of a handshake.
     */
    private void pickUpCargo(ServerLevel level, Vec3 at) {
        if (this.hasCrate) {
            return;
        }
        Vec3 from = this.position();
        AABB reach = new AABB(from, from).inflate(PICKUP_RADIUS, 0.0, PICKUP_RADIUS)
                .expandTowards(0.0, -PICKUP_REACH_DOWN, 0.0);
        for (CrateEntity crate : level.getEntitiesOfClass(CrateEntity.class, reach)) {
            if (crate.isAlive() && !crate.isRemoved()) {
                for (int i = 0; i < this.cargo.size(); i++) {
                    this.cargo.set(i, crate.items().get(i));
                }
                this.setCrate(true);
                ExchangeManager.onCargoCollected(level, this, crate);
                crate.discard();
                this.beginEgress();
                return;
            }
        }
    }

    /** Pickle the warhead. */
    private void dropPayload(ServerLevel level) {
        if (this.payloadId == null) {
            return;
        }
        BombletEntity payload = new BombletEntity(level, this.position().subtract(0.0, 1.0, 0.0),
                this.getDeltaMovement(), WarheadRegistry.get(this.payloadId), this.payloadId, PAYLOAD_FUSE);
        payload.setOwner(this);
        level.addFreshEntity(payload);
        this.setPayload(null);
    }

    /** Put one mine off the rack down. */
    private void layMine(ServerLevel level, Vec3 aim) {
        if (this.mines == null || this.mines.empty()) {
            return;
        }
        MinePreset preset = MinePresetRegistry.get(this.mines.preset());
        if (preset == null) {
            this.recordEvent(WFEventType.CARGO_DROP, "rack names an unknown mine: " + this.mines.preset());
            this.setMines(null);
            return;
        }
        MineEntity mine = preset.build(level, this.random.nextFloat() * 360.0f);
        Vec3 from = this.position()
                .subtract(0.0, 1.0, 0.0);
        mine.moveTo(from.x, from.y, from.z, mine.getYRot(), 0.0f);
        mine.setTeamId(this.teamId);
        mine.scatter(this.getDeltaMovement(), this.random);
        level.addFreshEntity(mine);

        this.setMines(this.mines.afterLaying());
        this.recordEvent(WFEventType.CARGO_DROP, String.format("mine away at %d %d %d, %d left",
                (int) aim.x, (int) aim.y, (int) aim.z, this.mines == null ? 0 : this.mines.remaining()));
    }

    /** Shot down: cut the power and let it spin in. */
    public void shootDown() {
        if (this.state == DroneState.DOWNED) {
            return;
        }
        if (!this.rotorDamage().damaged()) {
            this.knockOutRotor(null);
        }
        this.setState(DroneState.DOWNED);
        this.spillCargo();
        this.recordEvent(WFEventType.DESTROYED, "shot down");
    }

    /**
     * Take one rotor disc off the airframe.
     *
     * @param from where the hit came from, or null for anywhere. A shot that arrives from one side takes
     *      the disc on that side: the drone then falls away from the shooter, which is both what
     *      would happen and the readable outcome.
     */
    public void knockOutRotor(@Nullable Vec3 from) {
        List<Rotor> rotors = Rotors.of(this.getModelId());
        if (rotors.isEmpty()) {
            return;
        }
        int count = Math.min(rotors.size(), RotorDamage.MAX_ROTORS);
        byte out = this.entityData.get(ROTORS_OUT);
        int best = -1;
        double bestScore = Double.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            if ((out & (1 << i)) != 0) {
                continue;
            }
            double score;
            if (from == null) {
                score = this.random.nextDouble();
            } else {
                Vector3f pivot = Rotors.pivot(rotors.get(i));
                score = from.distanceToSqr(this.armPosition(pivot));
            }
            if (score < bestScore) {
                bestScore = score;
                best = i;
            }
        }
        if (best < 0) {
            return;     // every disc already gone
        }
        this.entityData.set(ROTORS_OUT, (byte) (out | (1 << best)));
    }

    /**
     * @return where one rotor arm is in the world, for deciding which disc a shot took off.
     */
    private Vec3 armPosition(Vector3f pivot) {
        float yaw = this.headingRadians();
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        return new Vec3(this.getX() + pivot.x * cos + pivot.z * sin,
                this.getY() + pivot.y,
                this.getZ() + pivot.z * cos - pivot.x * sin);
    }

    /**
     * @return which discs are gone and what that does to the way this drone falls.
     */
    public RotorDamage rotorDamage() {
        return RotorDamage.of(this.getModelId(), this.entityData.get(ROTORS_OUT));
    }

    /**
     * Blown apart in the air rather than shot down: the airframe stops existing as an airframe and comes down as
     * its own bones.
     */
    public void breakUp() {
        if (!(this.level() instanceof ServerLevel serverLevel) || this.isRemoved()) {
            return;
        }
        this.spillCargo();
        this.recordEvent(WFEventType.DESTROYED, "broke up");
        DroneDebrisEntity.shatter(serverLevel, this.getModelId(), this.position(),
                this.headingRadians(), this.getDeltaMovement());
        this.discard();
    }

    /** The wreck reaching the ground: a small blast where it hit, and then it lies there. */
    private void crash(ServerLevel level) {
        this.crashed = true;
        Vec3 at = this.position().add(0.0, DroneModels.center(this.getModelId()).y, 0.0);
        level.explode(this, at.x, at.y, at.z, CRASH_BLAST, Level.ExplosionInteraction.NONE);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y, at.z, 18, 0.5, 0.25, 0.5, 0.02);
        this.setHeadingRadians(this.random.nextFloat() * (float) (Math.PI * 2.0));
        this.setAttitude(new FlightAttitude(
                (this.random.nextDouble() * 2.0 - 1.0) * CRASH_TILT,
                (this.random.nextDouble() * 2.0 - 1.0) * CRASH_TILT,
                0.0));
        this.recordEvent(WFEventType.DESTROYED, "crashed");
    }

    public boolean isDowned() {
        return this.state == DroneState.DOWNED;
    }

    // --- InterceptTarget: a drone is something air defence may shoot at ---

    /**
     * A wreck on its way down has already been dealt with; shooting it again only wastes the round.
     */
    @Override
    public boolean interceptEngageable() {
        return this.isAlive() && !this.isRemoved() && !this.isDowned();
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
        this.shootDown();
    }

    /**
     * @return the faction this drone flies for, or null if it belongs to nobody (a hand-summoned one).
     */
    @Nullable
    public UUID getTeamId() {
        return this.teamId;
    }

    /**
     * Stamped once at launch from whoever claims the ground it took off from, so a battery can tell its own side's
     * traffic from somebody else's without asking the drone where it is now.
     */
    public void setTeamId(@Nullable UUID teamId) {
        this.teamId = teamId;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.level().isClientSide || this.isRemoved()) {
            return false;
        }
        Vec3 from = source == null ? null : source.getSourcePosition();
        if (this.state == DroneState.DOWNED) {
            // Hit again on the way down: there is nothing left to shoot down, so it comes apart instead.
            this.breakUp();
            return true;
        }
        this.health -= amount;
        this.recordEvent(WFEventType.DAMAGED, String.format("%.1f damage, %.1f hp left", amount, this.health));
        if (amount >= ROTOR_LOSS_DAMAGE) {
            this.knockOutRotor(from);
        }
        if (this.health <= 0.0f) {
            // Overkill: a warhead rather than a burst of cannon fire. There is no airframe left to fall.
            if (this.health <= -OVERKILL_HEALTH) {
                this.breakUp();
            } else {
                this.shootDown();
            }
        }
        return true;
    }

    /** Drop whatever is aboard where the drone is: used when it is shot down and when a wreck is broken up. */
    private void spillCargo() {
        if (this.level() instanceof ServerLevel serverLevel && this.hasCrate) {
            this.releaseCargo(serverLevel, this.position());
        }
    }

    /** Right-click recovery: take the delivery off a drone. */
    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (this.level().isClientSide) {
            return InteractionResult.sidedSuccess(true);
        }
        if (this.hasCrate) {
            this.openCargo(player);
            return InteractionResult.CONSUME;
        }
        if (this.payloadId != null) {
            player.displayClientMessage(Component.literal("Recovered payload: " + this.payloadId.getPath()), true);
            this.setPayload(null);
            this.recordEvent(WFEventType.CARGO_PICKUP, "payload recovered by hand");
            return InteractionResult.CONSUME;
        }
        if (this.hasMines()) {
            player.displayClientMessage(Component.literal("Recovered mines: " + this.mines.label()), true);
            this.recordEvent(WFEventType.CARGO_PICKUP, "rack recovered by hand: " + this.mines.label());
            this.setMines(null);
            return InteractionResult.CONSUME;
        }
        player.displayClientMessage(Component.literal(String.format("%s - %.0f%% battery, nothing aboard",
                this.state, this.battery.percent())), true);
        return InteractionResult.CONSUME;
    }

    public DroneState getDroneState() {
        return this.state;
    }

    public void setState(DroneState state) {
        if (this.state == state) {
            return;
        }
        this.state = state;
        this.stateTicks = 0;
        this.entityData.set(STATE, (byte) state.ordinal());
        if (state.airborne() && this.level() instanceof ServerLevel serverLevel) {
            this.chunkLoader.update(this, serverLevel, this.position(), this.getDeltaMovement(), true);
        }
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
        return this.power.massFactor(this.hasCrate || this.payloadId != null || this.hasMines());
    }

    public FlightAttitude getAttitude() {
        return this.attitude;
    }

    /**
     * Store the attitude the flight model settled on and publish it to whoever is watching.
     */
    public void setAttitude(FlightAttitude attitude) {
        this.attitude = attitude;
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

    @Nullable
    public DronePath getPath() {
        return this.path;
    }

    @Nullable
    public Vec3 getDestination() {
        return this.destination;
    }

    public void setDestination(@Nullable Vec3 destination) {
        this.destination = destination;
        this.path = null;
    }

    public DroneProgram getProgram() {
        return this.program;
    }

    /** Give this drone a queue to fly, and send it to the first step. */
    public void setProgram(DroneProgram program) {
        this.program = program == null ? DroneProgram.EMPTY : program;
        Vec3 first = this.program.destination(this.exfil);
        if (first != null) {
            this.setDestination(first);
        }
    }

    /** Step the queue on and take the next destination with it. */
    private void advanceTask() {
        this.program = this.program.advanced();
        this.seenContacts.clear();
        this.setDestination(this.program.destination(this.exfil));
    }

    public boolean isClassified() {
        return this.classified;
    }

    public boolean isCollecting() {
        return this.collecting;
    }

    @Nullable
    public UUID getExchangeId() {
        return this.exchangeId;
    }

    @Nullable
    public String getStationCode() {
        return this.stationCode;
    }

    /**
     * Fly this as part of an arranged exchange: no telemetry, a dogleg out and back, and either dropping or
     * collecting at the far end.
     *
     * @param approach staging waypoints to fly before the destination
     * @param egress staging waypoints to fly after the drop, installed when the cargo changes hands
     */
    public void setExchange(@Nullable UUID exchangeId, @Nullable String stationCode, boolean classified,
                            boolean collecting, List<Vec3> approach, List<Vec3> egress) {
        this.exchangeId = exchangeId;
        this.stationCode = stationCode;
        this.classified = classified;
        this.collecting = collecting;
        this.legs.clear();
        this.legs.addAll(approach);
        this.egressPlan.clear();
        this.egressPlan.addAll(egress);
    }

    /**
     * @return the staging waypoints still to fly, for diagnostics.
     */
    public int remainingLegs() {
        return this.legs.size();
    }

    /**
     * Abandon the exchange and go home with whatever is aboard.
     */
    public void abortExchange() {
        this.exchangeId = null;
        this.destination = null;
        this.path = null;
        this.beginEgress();
    }

    public Vec3 getExfil() {
        return this.exfil;
    }

    public void setExfil(Vec3 exfil) {
        this.exfil = exfil;
    }

    public boolean hasCargo() {
        return this.hasCrate;
    }

    /**
     * @return true if anything is slung underneath, crate or warhead. The synced half of {@link #hasCargo},
     *      so the client can work out how hard the rotors are having to work.
     */
    public boolean isLoaded() {
        return this.entityData.get(LOAD) != 0;
    }

    /**
     * @return true if what is slung underneath is a crate, as the client sees it. Distinct from
     *      {@link #isLoaded} because a warhead weighs on the rotors the same way but is not drawn as a crate.
     */
    public boolean hasCrateAboard() {
        return (this.entityData.get(LOAD) & LOAD_CRATE) != 0;
    }

    /** Grip or release a crate. */
    public void setCrate(boolean carrying) {
        this.hasCrate = carrying;
        if (!carrying) {
            this.cargo.clear();
        }
        this.syncLoad();
    }

    /**
     * Take on cargo directly, without a crate entity ever existing: how a pad loads a drone and how one comes back
     * from the off-world sim.
     */
    public void loadCargo(CompoundTag tag) {
        this.cargo.clear();
        ContainerHelper.loadAllItems(tag, this.cargo, this.registryAccess());
        this.hasCrate = true;
        this.syncLoad();
    }

    @Nullable
    public WorkAssignment assignment() {
        return this.assignment;
    }

    public void setAssignment(@Nullable WorkAssignment assignment) {
        this.assignment = assignment;
    }

    /**
     * @return true if there is not a free slot nor a matching stack with room in it. What tells a demolition
     *      drone to go and empty out
     */
    public boolean cargoFull() {
        for (ItemStack stack : this.cargo) {
            if (stack.isEmpty() || stack.getCount() < stack.getMaxStackSize()) {
                return false;
            }
        }
        return true;
    }

    public boolean hasItem(Item item) {
        for (ItemStack stack : this.cargo) {
            if (stack.is(item) && !stack.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Put a block down, paying for it out of the hold.
     *
     * @return false if the space was not free, the drone had nothing to place it with, or the world refused
     *      the block. All three are the order's problem rather than the drone's, and the caller counts them
     *      against it: the space being occupied is not going to fix itself
     */
    public boolean placeFromCargo(ServerLevel level, BlockPos at, BlockState state) {
        BlockState existing = level.getBlockState(at);
        if (existing.equals(state)) {
            return true;
        }
        if (!existing.canBeReplaced()) {
            return false;
        }
        Item item = state.getBlock().asItem();
        if (item == Items.AIR || !this.takeItem(item)) {
            return false;
        }
        if (!level.setBlock(at, state, Block.UPDATE_ALL)) {
            this.giveItem(new ItemStack(item));
            return false;
        }
        level.gameEvent(net.minecraft.world.level.gameevent.GameEvent.BLOCK_PLACE, at,
                net.minecraft.world.level.gameevent.GameEvent.Context.of(this, state));
        return true;
    }

    /**
     * Take a block down and put what it drops in the hold.
     *
     * @return false only if the block cannot be removed at all. An empty space counts as done: the plan was
     *      drawn against the world as it was, and somebody mining a block by hand should not leave an order that
     *      fails three times and blocks
     */
    public boolean breakIntoCargo(ServerLevel level, BlockPos at) {
        BlockState state = level.getBlockState(at);
        if (state.isAir()) {
            return true;
        }
        if (state.getDestroySpeed(level, at) < 0.0F) {
            return false;
        }
        for (ItemStack drop : Block.getDrops(state, level, at, level.getBlockEntity(at), this,
                ItemStack.EMPTY)) {
            this.giveItem(drop);
        }
        return level.destroyBlock(at, false, this);
    }

    /**
     * Load up from a station's own store, taking only what the job is about to need.
     *
     * @param wanted the items worth carrying, from {@code BuildPilot}. An empty set means take nothing,
     *      which is the right answer for a demolition, not a bug
     * @return how many items were taken
     */
    public int loadFromStation(ServerLevel level, Vec3 station, java.util.Set<Item> wanted) {
        DronePadBlockEntity pad = padAt(level, station);
        if (pad == null || wanted.isEmpty()) {
            return 0;
        }
        int taken = 0;
        NonNullList<ItemStack> store = pad.cargoItems();
        for (int i = 0; i < store.size() && !this.cargoFull(); i++) {
            ItemStack stack = store.get(i);
            if (stack.isEmpty() || !wanted.contains(stack.getItem())) {
                continue;
            }
            ItemStack moving = stack.copy();
            int left = this.insert(moving);
            taken += stack.getCount() - left;
            stack.setCount(left);
            if (left == 0) {
                store.set(i, ItemStack.EMPTY);
            }
        }
        if (taken > 0) {
            pad.setChanged();
            this.setCrate(true);
        }
        return taken;
    }

    /**
     * Hand everything in the hold over to a station.
     *
     * @return how many items were handed over. Anything that does not fit stays aboard, so a full depot
     *      means the drone comes back still loaded rather than the material vanishing
     */
    public int unloadToStation(ServerLevel level, Vec3 station) {
        DronePadBlockEntity pad = padAt(level, station);
        if (pad == null) {
            return 0;
        }
        NonNullList<ItemStack> store = pad.cargoItems();
        int given = 0;
        for (int i = 0; i < this.cargo.size(); i++) {
            ItemStack stack = this.cargo.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            int before = stack.getCount();
            int left = insertInto(store, stack.copy());
            given += before - left;
            if (left == 0) {
                this.cargo.set(i, ItemStack.EMPTY);
            } else {
                stack.setCount(left);
            }
        }
        if (given > 0) {
            pad.setChanged();
        }
        if (this.cargoEmpty()) {
            this.setCrate(false);
        }
        return given;
    }

    private boolean cargoEmpty() {
        for (ItemStack stack : this.cargo) {
            if (!stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Spend one of {@code item} from the hold.
     */
    private boolean takeItem(Item item) {
        for (int i = 0; i < this.cargo.size(); i++) {
            ItemStack stack = this.cargo.get(i);
            if (stack.is(item) && !stack.isEmpty()) {
                stack.shrink(1);
                if (stack.isEmpty()) {
                    this.cargo.set(i, ItemStack.EMPTY);
                }
                if (this.cargoEmpty()) {
                    this.setCrate(false);
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Put items in the hold, spilling on the ground whatever will not fit.
     */
    private void giveItem(ItemStack stack) {
        int left = this.insert(stack);
        if (left > 0) {
            ItemStack overflow = stack.copy();
            overflow.setCount(left);
            Containers.dropItemStack(this.level(), this.getX(), this.getY(), this.getZ(), overflow);
        } else {
            this.setCrate(true);
        }
    }

    /**
     * @return how much of {@code stack} would not fit.
     */
    private int insert(ItemStack stack) {
        return insertInto(this.cargo, stack);
    }

    private static int insertInto(NonNullList<ItemStack> into, ItemStack stack) {
        int left = stack.getCount();
        for (int i = 0; i < into.size() && left > 0; i++) {
            ItemStack slot = into.get(i);
            if (slot.isEmpty() || !ItemStack.isSameItemSameComponents(slot, stack)) {
                continue;
            }
            int moved = Math.min(slot.getMaxStackSize() - slot.getCount(), left);
            slot.grow(moved);
            left -= moved;
        }
        for (int i = 0; i < into.size() && left > 0; i++) {
            if (!into.get(i).isEmpty()) {
                continue;
            }
            ItemStack fresh = stack.copy();
            int moved = Math.min(fresh.getMaxStackSize(), left);
            fresh.setCount(moved);
            into.set(i, fresh);
            left -= moved;
        }
        return left;
    }

    /**
     * @return the pad under this station point, or null if there is not one: the pad was broken, or the
     *      chunk is not loaded and nothing can be moved into it anyway
     */
    @Nullable
    private static DronePadBlockEntity padAt(ServerLevel level, Vec3 station) {
        BlockPos below = BlockPos.containing(station).below();
        if (!level.isLoaded(below)) {
            return null;
        }
        return level.getBlockEntity(below) instanceof DronePadBlockEntity pad ? pad : null;
    }

    /**
     * @return the cargo as a tag, for handing across an offload to the drone sim.
     */
    public CompoundTag saveCargo() {
        CompoundTag tag = new CompoundTag();
        ContainerHelper.saveAllItems(tag, this.cargo, this.registryAccess());
        return tag;
    }

    /** Open the crate's contents for a player. */
    public void openCargo(Player player) {
        Container container = new SimpleContainer(this.cargo.toArray(new ItemStack[0])) {
            @Override
            public void setChanged() {
                for (int i = 0; i < DroneEntity.this.cargo.size(); i++) {
                    DroneEntity.this.cargo.set(i, this.getItem(i));
                }
            }
        };
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, p) -> ChestMenu.threeRows(id, inventory, container),
                Component.translatable("entity.wfballistics.crate")));
    }

    private void syncLoad() {
        byte flags = 0;
        if (this.hasCrate) {
            flags |= LOAD_CRATE;
        }
        if (this.payloadId != null) {
            flags |= LOAD_PAYLOAD;
        }
        if (this.hasMines()) {
            flags |= LOAD_MINES;
        }
        this.entityData.set(LOAD, flags);

        MinePreset preset = this.mines == null ? null : MinePresetRegistry.get(this.mines.preset());
        this.entityData.set(LOAD_ID, preset == null ? "" : preset.modelId()
                .toString());
        this.entityData.set(LOAD_COUNT, (byte) (this.mines == null
                ? 0 : Math.min(Byte.MAX_VALUE, this.mines.remaining())));
    }

    /**
     * @return the model id of the mine on the rack, or null when there is no rack: as the client sees it.
     *      The counterpart of {@link #hasCrateAboard} for a minelayer.
     */
    @Nullable
    public ResourceLocation slungMineModel() {
        String raw = this.entityData.get(LOAD_ID);
        return raw.isEmpty() ? null : ResourceLocation.tryParse(raw);
    }

    /** @return how many mines are still on the rack, as the client sees it. */
    public int slungMineCount() {
        return Math.max(0, this.entityData.get(LOAD_COUNT));
    }

    /** @return true if a warhead is slung underneath, as the client sees it. */
    public boolean hasPayloadAboard() {
        return (this.entityData.get(LOAD) & LOAD_PAYLOAD) != 0;
    }

    public boolean hasPayload() {
        return this.payloadId != null;
    }

    @Nullable
    public ResourceLocation getPayloadId() {
        return this.payloadId;
    }

    public void setPayload(@Nullable ResourceLocation payloadId) {
        this.payloadId = payloadId;
        this.syncLoad();
    }

    /** @return true while there is a mine left on the rack. An empty rack is the same as no rack. */
    public boolean hasMines() {
        return this.mines != null && !this.mines.empty();
    }

    @Nullable
    public MineLoad getMines() {
        return this.mines;
    }

    /** Loads, replaces or clears the mine rack. An emptied rack is dropped outright so nothing carries 0/8. */
    public void setMines(@Nullable MineLoad mines) {
        this.mines = mines == null || mines.empty() ? null : mines;
        this.syncLoad();
    }

    public double getReleaseSpeed() {
        return this.releaseSpeed;
    }

    public void setReleaseSpeed(double releaseSpeed) {
        this.releaseSpeed = releaseSpeed;
    }

    public float getHealth() {
        return this.health;
    }

    public void setSquad(long squadId, boolean leader, ResourceLocation formationId) {
        this.squadId = squadId;
        this.leader = leader;
        this.formationId = formationId != null ? formationId : Formations.DEFAULT;
    }

    public double getCruiseSpeed() {
        return this.cruiseSpeed;
    }

    public void setCruiseSpeed(double cruiseSpeed) {
        this.cruiseSpeed = cruiseSpeed;
    }

    public double getCruiseAltitude() {
        return this.cruiseAltitude;
    }

    public void setCruiseAltitude(double cruiseAltitude) {
        this.cruiseAltitude = cruiseAltitude;
    }

    public double getClimbRate() {
        return this.climbRate;
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
        this.recordEvent(WFEventType.SPAWN, "drone " + this.getModelId().getPath());
    }

    public void openTelemetry() {
        this.telemetry = WFBallisticsAPI.openTelemetry(this.getUUID(), this.level().getGameTime());
    }

    public void attachTelemetry(WFTelemetry telemetry) {
        this.telemetry = telemetry;
    }

    public void recordEvent(WFEventType type, String detail) {
        if (this.level().isClientSide) {
            return;
        }
        if (this.classified) {
            return;
        }
        WFTelemetryService.record(this.telemetry, this.getUUID(), type, this.level().getGameTime(),
                this.position(), false, detail);
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
        double leanX = this.attitude.tiltX();
        double leanZ = this.attitude.tiltZ();
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
        worldCenter.add(x, y, z);
        this.obb.setCenter(worldCenter);

        this.obb.setExtents(obbScratch.set(dims.x * 0.5, dims.y * 0.5, dims.z * 0.5));
        this.obb.updateRotation(rot);
    }

    @Override
    protected AABB makeBoundingBox() {
        if (this.obb == null) {
            return super.makeBoundingBox();
        }
        this.refreshObb();
        Vector3d ext = this.obb.extents();
        if (ext.x < 1.0E-3 && ext.y < 1.0E-3 && ext.z < 1.0E-3) {
            return super.makeBoundingBox();
        }
        Vector3d c = this.obb.center();
        Vector3d[] ax = this.obb.getAxes();
        double hx = ext.x * Math.abs(ax[0].x) + ext.y * Math.abs(ax[1].x) + ext.z * Math.abs(ax[2].x);
        double hy = ext.x * Math.abs(ax[0].y) + ext.y * Math.abs(ax[1].y) + ext.z * Math.abs(ax[2].y);
        double hz = ext.x * Math.abs(ax[0].z) + ext.y * Math.abs(ax[1].z) + ext.z * Math.abs(ax[2].z);
        return new AABB(c.x - hx, c.y - hy, c.z - hz, c.x + hx, c.y + hy, c.z + hz);
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
    }

    /**
     * Mirror the synced state back onto the field, because {@link #getDroneState()} reads the field and on the
     * client nothing else ever writes it: {@link #setState} only runs on the server and {@link
     * #readAdditionalSaveData} never runs at all.
     */
    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (this.level().isClientSide && STATE.equals(key)) {
            DroneState[] states = DroneState.values();
            int ordinal = this.entityData.get(STATE);
            this.state = ordinal >= 0 && ordinal < states.length ? states[ordinal] : DroneState.IDLE;
        }
    }

    @Override
    public void remove(RemovalReason reason) {
        if (this.level() instanceof ServerLevel serverLevel) {
            this.chunkLoader.releaseAll(this, serverLevel);
            if (this.assignment != null) {
                com.wf.wfballistics.work.WorkRegistry.get(serverLevel).abandonAll(this.getUUID());
            }
        }
        super.remove(reason);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.setModelId(DroneModels.parse(tag.getString("ModelId")));
        try {
            this.state = DroneState.valueOf(tag.getString("State"));
        } catch (IllegalArgumentException ignored) {
            this.state = DroneState.IDLE;
        }
        this.entityData.set(STATE, (byte) this.state.ordinal());
        this.stateTicks = tag.getInt("StateTicks");
        this.battery = DroneBattery.load(tag.getCompound("Battery"));
        if (tag.contains("DestX")) {
            this.destination = new Vec3(tag.getDouble("DestX"), tag.getDouble("DestY"), tag.getDouble("DestZ"));
        }
        this.exfil = new Vec3(tag.getDouble("ExfilX"), tag.getDouble("ExfilY"), tag.getDouble("ExfilZ"));
        if (tag.getBoolean("HasCrate")) {
            this.loadCargo(tag.getCompound("Cargo"));
        }
        this.camera = cameraFitOf(tag.getByte("Camera"));
        this.squadId = tag.getLong("SquadId");
        this.leader = tag.getBoolean("Leader");
        this.formationId = Formations.parse(tag.getString("Formation"));
        this.formationSpacing = tag.contains("FormationSpacing")
                ? Formation.clampSpacing(tag.getDouble("FormationSpacing")) : Formation.DEFAULT_SPACING;
        this.coordinationId = tag.contains("Coordination")
                ? CoordinationModels.parse(tag.getString("Coordination")) : CoordinationModels.LEGACY;
        this.squadSize = Math.max(1, tag.getInt("SquadSize"));
        this.cruiseSpeed = tag.contains("CruiseSpeed") ? tag.getDouble("CruiseSpeed") : DEFAULT_CRUISE_SPEED;
        this.cruiseAltitude = tag.contains("CruiseAltitude") ? tag.getDouble("CruiseAltitude") : DEFAULT_CRUISE_ALTITUDE;
        this.climbRate = tag.contains("ClimbRate") ? tag.getDouble("ClimbRate") : DEFAULT_CLIMB_RATE;
        this.setPayload(tag.contains("Payload") ? WarheadRegistry.parse(tag.getString("Payload")) : null);
        this.setMines(tag.contains("Mines") ? MineLoad.load(tag.getCompound("Mines")) : null);
        this.releaseSpeed = tag.contains("ReleaseSpeed") ? tag.getDouble("ReleaseSpeed") : DEFAULT_RELEASE_SPEED;
        this.health = tag.contains("Health") ? tag.getFloat("Health") : DEFAULT_HEALTH;
        this.classified = tag.getBoolean("Classified");
        this.collecting = tag.getBoolean("Collecting");
        this.assignment = tag.contains("Work")
                ? WorkAssignment.load(tag.getCompound("Work")) : null;
        this.exchangeId = tag.hasUUID("Exchange") ? tag.getUUID("Exchange") : null;
        this.teamId = tag.hasUUID("TeamId") ? tag.getUUID("TeamId") : null;
        this.entityData.set(ROTORS_OUT, tag.getByte("RotorsOut"));
        this.crashed = tag.getBoolean("Crashed");
        this.stationCode = tag.contains("Station") ? tag.getString("Station") : null;
        this.legs.clear();
        this.legs.addAll(readLegs(tag, "Legs"));
        this.egressPlan.clear();
        this.egressPlan.addAll(readLegs(tag, "Egress"));
        this.program = tag.contains("Program") ? DroneProgram.load(tag.getCompound("Program")) : DroneProgram.EMPTY;
    }

    private static List<Vec3> readLegs(CompoundTag tag, String key) {
        List<Vec3> out = new ArrayList<>();
        net.minecraft.nbt.ListTag list = tag.getList(key, net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag leg = list.getCompound(i);
            out.add(new Vec3(leg.getDouble("X"), leg.getDouble("Y"), leg.getDouble("Z")));
        }
        return out;
    }

    private static void writeLegs(CompoundTag tag, String key, Iterable<Vec3> legs) {
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        for (Vec3 leg : legs) {
            CompoundTag entry = new CompoundTag();
            entry.putDouble("X", leg.x);
            entry.putDouble("Y", leg.y);
            entry.putDouble("Z", leg.z);
            list.add(entry);
        }
        tag.put(key, list);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putString("ModelId", this.getModelId().toString());
        tag.putString("State", this.state.name());
        tag.putInt("StateTicks", this.stateTicks);
        CompoundTag batteryTag = new CompoundTag();
        this.battery.save(batteryTag);
        tag.put("Battery", batteryTag);
        if (this.destination != null) {
            tag.putDouble("DestX", this.destination.x);
            tag.putDouble("DestY", this.destination.y);
            tag.putDouble("DestZ", this.destination.z);
        }
        tag.putDouble("ExfilX", this.exfil.x);
        tag.putDouble("ExfilY", this.exfil.y);
        tag.putDouble("ExfilZ", this.exfil.z);
        if (this.hasCrate) {
            tag.putBoolean("HasCrate", true);
            tag.put("Cargo", this.saveCargo());
        }
        tag.putByte("Camera", cameraFitByte(this.camera));
        tag.putLong("SquadId", this.squadId);
        tag.putBoolean("Leader", this.leader);
        tag.putString("Formation", this.formationId.toString());
        tag.putDouble("FormationSpacing", this.formationSpacing);
        tag.putString("Coordination", this.coordinationId.toString());
        tag.putInt("SquadSize", this.squadSize);
        tag.putDouble("CruiseSpeed", this.cruiseSpeed);
        tag.putDouble("CruiseAltitude", this.cruiseAltitude);
        tag.putDouble("ClimbRate", this.climbRate);
        if (this.payloadId != null) {
            tag.putString("Payload", this.payloadId.toString());
        }
        if (this.mines != null) {
            tag.put("Mines", this.mines.save());
        }
        tag.putDouble("ReleaseSpeed", this.releaseSpeed);
        tag.putFloat("Health", this.health);
        tag.putBoolean("Classified", this.classified);
        tag.putBoolean("Collecting", this.collecting);
        if (this.exchangeId != null) {
            tag.putUUID("Exchange", this.exchangeId);
        }
        tag.putByte("RotorsOut", this.entityData.get(ROTORS_OUT));
        tag.putBoolean("Crashed", this.crashed);
        if (this.teamId != null) {
            tag.putUUID("TeamId", this.teamId);
        }
        if (this.stationCode != null) {
            tag.putString("Station", this.stationCode);
        }
        if (this.assignment != null) {
            tag.put("Work", this.assignment.save());
        }
        writeLegs(tag, "Legs", this.legs);
        writeLegs(tag, "Egress", this.egressPlan);
        if (!this.program.isEmpty()) {
            tag.put("Program", this.program.save());
        }
    }
}
