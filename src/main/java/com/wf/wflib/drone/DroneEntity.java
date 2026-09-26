package com.wf.wflib.drone;

import com.wf.wflib.ModEntities;
import com.wf.wflib.api.WFLibAPI;
import com.wf.wflib.api.WFEventType;
import com.wf.wflib.api.WFTelemetry;
import com.wf.wflib.api.WFTelemetryService;
import com.wf.wflib.attitude.MissileAttitude;
import com.wf.wflib.attitude.MissileAttitudeRegistry;
import com.wf.wflib.chunk.MissileChunkLoader;
import com.wf.wflib.drone.ai.DroneAction;
import com.wf.wflib.drone.cam.CameraSpec;
import com.wf.wflib.drone.ai.DroneCarrier;
import com.wf.wflib.drone.ai.DroneBrain;
import com.wf.wflib.drone.ai.DronePlan;
import com.wf.wflib.drone.ai.DroneSnapshot;
import com.wf.wflib.drone.ai.TerrainSampler;
import com.wf.wflib.drone.flight.Airframe;
import com.wf.wflib.drone.flight.FlightAttitude;
import com.wf.wflib.drone.nav.DronePath;
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

import java.util.List;
import java.util.UUID;

/** An autonomous rotor drone. */
public class DroneEntity extends Entity implements OBBEntity, InterceptTarget, DroneCarrier {

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
    /** Lean and throttle as flown ({@link DroneFlight#setAttitude}), not guessed by the renderer from velocity. */
    static final EntityDataAccessor<Byte> ROLL =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.BYTE);
    static final EntityDataAccessor<Byte> PITCH =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.BYTE);
    static final EntityDataAccessor<Byte> THROTTLE =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.BYTE);
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

    private DroneBattery battery = new DroneBattery(DroneBattery.DEFAULT_CAPACITY);
    /** Which camera head is bolted on, or null for none. */
    private CameraSpec camera = CameraSpec.RECON;

    /** The fits, in the order the save byte encodes them. */
    private static final CameraSpec[] CAMERA_FITS = {CameraSpec.RECON, CameraSpec.STANDARD, null};
    private PowerProfile power = PowerProfile.DEFAULT;
    // Whose drone this is, stamped at launch from the claim on the ground it left. Null = nobody's.
    private UUID teamId;

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

        this.battery.drain(this.power.drain(this.flight.getDroneState(), this.flight.getAttitude().throttle(),
                this.massFactor(), this.getDeltaMovement().horizontalDistance()));
    }

    public DroneFlight flight() {
        return this.flight;
    }

    public DroneRoute route() {
        return this.route;
    }

    public DroneOrders orders() {
        return this.orders;
    }

    public DroneHold hold() {
        return this.hold;
    }

    public DroneDamage damage() {
        return this.damage;
    }

    public DroneSquadRole squad() {
        return this.squad;
    }

    /** Entering an airborne state: take the chunk tickets now, not next tick. */
    void onAirborne() {
        if (this.level() instanceof ServerLevel serverLevel) {
            this.chunkLoader.update(this, serverLevel, this.position(), this.getDeltaMovement(), true);
        }
    }

    void refitBounds() {
        this.setBoundingBox(this.makeBoundingBox());
    }

    @Override
    public UUID droneId() {
        return this.getUUID();
    }

    @Override
    public long squadId() {
        return this.squad.squadId();
    }

    @Override
    public boolean leader() {
        return this.squad.leader();
    }

    @Override
    public void promote() {
        this.squad.promote();
    }

    @Override
    public ResourceLocation formationId() {
        return this.squad.formationId();
    }

    @Override
    public double formationSpacing() {
        return this.squad.formationSpacing();
    }

    @Override
    public ResourceLocation coordinationId() {
        return this.squad.coordinationId();
    }

    @Override
    public boolean carrierAlive() {
        return this.isAlive() && !this.isRemoved();
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
        this.flight.moveWith(plan.velocity());
    }

    @Override
    public void coast(ServerLevel level) {
        this.flight.moveWith(DroneBrain.coast(this.getDeltaMovement()));
    }

    @Override
    public void adoptPath(DronePath path) {
        this.route.adoptPath(path);
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
        this.telemetry = WFLibAPI.openTelemetry(this.getUUID(), this.level().getGameTime());
    }

    public void attachTelemetry(WFTelemetry telemetry) {
        this.telemetry = telemetry;
    }

    public void recordEvent(WFEventType type, String detail) {
        if (this.level().isClientSide) {
            return;
        }
        if (this.orders.isClassified()) {
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
        this.battery = DroneBattery.load(tag.getCompound("Battery"));
        this.camera = cameraFitOf(tag.getByte("Camera"));
        this.teamId = tag.hasUUID("TeamId") ? tag.getUUID("TeamId") : null;
        this.flight.load(tag.getCompound("Flight"));
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
        tag.put("Flight", this.flight.save());
        tag.put("Route", this.route.save());
        tag.put("Orders", this.orders.save());
        tag.put("Hold", this.hold.save());
        tag.put("Damage", this.damage.save());
        tag.put("Squad", this.squad.save());
    }
}
