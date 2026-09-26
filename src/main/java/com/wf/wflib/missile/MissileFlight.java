package com.wf.wflib.missile;

import com.wf.wflib.MissileEntity;
import com.wf.wflib.MissileEntity.CruiseMode;
import com.wf.wflib.MissileEntity.DownedAction;
import com.wf.wflib.MissileEntity.Medium;
import com.wf.wflib.MissileEntity.Phase;
import com.wf.wflib.MissileModels;
import com.wf.wflib.api.WFEventType;
import com.wf.wflib.flight.ApproachStage;
import com.wf.wflib.flight.ArrivalEstimator;
import com.wf.wflib.flight.AttackProfile;
import com.wf.wflib.flight.FlightContext;
import com.wf.wflib.flight.FlightProfile;
import com.wf.wflib.flight.FlightStage;
import com.wf.wflib.flight.FlightStageRegistry;
import com.wf.wflib.flight.LoiterStage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Where the missile goes and how: phase, stages, cruise altitude, terrain/seabed clearance, dive. */
public final class MissileFlight {

    private static final double DIVE_RAYCAST_RANGE = 64.0;
    private static final int DIVE_ANGLE_SAMPLES = 6;
    private static final double SURFACE_MARGIN = 2.0;
    private static final int MAX_SUBMERGENCE = 96;
    /** Dry ticks before a submerged missile is written off: one dry tick is a porpoise, not a broach. */
    private static final int BROACH_GRACE_TICKS = 20;
    private static final double BROACH_RECOVERY = 0.04;
    /** radians * model length per tick. */
    static final double TURN_AGILITY = 1.0;

    private final MissileEntity missile;

    Vec3 target = Vec3.ZERO;
    Phase phase = Phase.ASCEND;
    ResourceLocation ascentStageId = FlightStageRegistry.defaultId(Phase.ASCEND);
    ResourceLocation cruiseStageId = FlightStageRegistry.defaultId(Phase.CRUISE);
    ResourceLocation attackStageId = FlightStageRegistry.defaultId(Phase.ATTACK);
    private FlightProfile profile = FlightProfile.fromIds(ascentStageId, cruiseStageId, attackStageId);
    private int loiterTicks;
    private boolean diveCommitted;
    CruiseMode cruiseMode = CruiseMode.TERRAIN_FOLLOW;
    Medium medium = Medium.AIR;
    private int dryTicks;
    double cruiseAltitude = 200.0;
    double terrainClearance = 24.0;
    private double cruiseTargetY = Double.NaN;
    double cruiseSpeed = MissileEntity.CRUISE_SPEED;
    double ascentSpeed = Double.NaN;
    double attackAngle = Double.NaN;
    double minDiveAngle = MissileEntity.DEFAULT_MIN_DIVE_ANGLE;
    double maxDiveAngle = MissileEntity.DEFAULT_MAX_DIVE_ANGLE;
    @Nullable
    private Vec3 attackApproachDir;
    AttackProfile attackProfile = AttackProfile.SPEED;
    double approachJoinCap = MissileEntity.DEFAULT_APPROACH_JOIN_CAP;
    private int cruiseTicks;
    double maxTurnRate = TURN_AGILITY / MissileModels.length(MissileModels.DEFAULT);
    /** Lateral acceleration limit, blocks/tick^2; > 0 replaces {@link #maxTurnRate} with omega = a/v. */
    double gLimit;
    /** Below this speed the lateral limit falls off with dynamic pressure (v^2 / ref^2). */
    double gRefSpeed = 1.0;
    @Nullable
    private Vec3 launchPos;

    public MissileFlight(MissileEntity missile) {
        this.missile = missile;
    }

    public static double ascentSpeedFor(double cruiseSpeed) {
        return Math.max(MissileEntity.MIN_ASCENT_SPEED, cruiseSpeed * MissileEntity.ASCENT_SPEED_FACTOR);
    }

    /** Turn-rate-limited rotation of {@code current} toward {@code desired}, keeping {@code desired}'s speed. */
    public static Vec3 constrainTurn(Vec3 current, Vec3 desired, double maxTurnRate) {
        double desiredSpeed = desired.length();
        if (desiredSpeed < 1.0E-6 || current.lengthSqr() < 1.0E-6 || maxTurnRate >= Math.PI) {
            return desired;
        }
        Vec3 curDir = current.normalize();
        Vec3 desDir = desired.scale(1.0 / desiredSpeed);
        double angle = Math.acos(Mth.clamp(curDir.dot(desDir), -1.0, 1.0));
        if (angle <= maxTurnRate) {
            return desDir.scale(desiredSpeed);
        }
        Vec3 axis = curDir.cross(desDir);
        if (axis.lengthSqr() < 1.0E-12) {
            Vec3 reference = Math.abs(curDir.y) < 0.99 ? new Vec3(0.0, 1.0, 0.0) : new Vec3(1.0, 0.0, 0.0);
            axis = curDir.cross(reference);
        }
        axis = axis.normalize();
        Vec3 newDir = curDir.scale(Math.cos(maxTurnRate)).add(axis.cross(curDir).scale(Math.sin(maxTurnRate)));
        return newDir.normalize().scale(desiredSpeed);
    }

    void noteLaunch() {
        if (this.launchPos == null) {
            this.launchPos = this.missile.position();
        }
    }

    @Nullable
    Vec3 launchPos() {
        return this.launchPos;
    }

    /** This tick's view of the world: aim direction, terrain floor, surface ceiling. */
    FlightContext context(Vec3 pos) {
        double dx = this.target.x - pos.x;
        double dz = this.target.z - pos.z;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        double nx = 0.0;
        double nz = 0.0;
        if (horizontalDist > 1.0E-3) {
            nx = dx / horizontalDist;
            nz = dz / horizontalDist;
        }
        return new FlightContext(pos, this.target, horizontalDist, nx, nz,
                this.computeSafeAltitude(pos, dx, dz, horizontalDist), this.computeSafeCeiling(pos));
    }

    /** Advance the phase (each stage decides when it is done), then fly the resulting phase's stage. */
    Vec3 stageVelocity(FlightContext ctx) {
        Phase next = this.profile.stage(this.phase).next(this.missile, ctx);
        if (next != null) {
            this.setPhase(next);
        }
        this.cruiseTicks = this.phase == Phase.CRUISE ? this.cruiseTicks + 1 : 0;
        return this.profile.stage(this.phase).guide(this.missile, ctx);
    }

    void setPhase(Phase phase) {
        this.phase = phase;
        this.missile.recordEvent(switch (phase) {
            case ASCEND -> WFEventType.ASCEND;
            case CRUISE -> WFEventType.CRUISE;
            case ATTACK -> WFEventType.ATTACK;
        }, "");
    }

    int cruiseTicks() {
        return this.cruiseTicks;
    }

    private double computeSafeAltitude(Vec3 pos, double dx, double dz, double horizontalDist) {
        double scanCenterX = pos.x;
        double scanCenterZ = pos.z;
        if (this.phase != Phase.ASCEND && horizontalDist > 1.0E-3) {
            scanCenterX += (dx / horizontalDist) * this.medium.lookAhead();
            scanCenterZ += (dz / horizontalDist) * this.medium.lookAhead();
        }
        double waterline = this.medium.hasCeiling() ? this.fluidSurfaceAbove(pos) : Double.NaN;
        double terrainSafe = this.scanTerrainTop(this.medium.heightmap(), scanCenterX, scanCenterZ,
                this.medium.scanRadius(), Double.isNaN(waterline) ? Double.POSITIVE_INFINITY : waterline)
                + this.terrainClearance;
        if (this.cruiseMode != CruiseMode.HIGH_ALTITUDE) {
            return terrainSafe;
        }
        if (this.medium == Medium.WATER) {
            double surface = this.fluidSurfaceAbove(pos);
            return Double.isNaN(surface) ? terrainSafe : Math.max(terrainSafe, surface - this.cruiseAltitude);
        }
        return Math.max(this.cruiseAltitude, terrainSafe);
    }

    private double computeSafeCeiling(Vec3 pos) {
        if (!this.medium.hasCeiling()) {
            return Double.POSITIVE_INFINITY;
        }
        double surface = this.fluidSurfaceAbove(pos);
        return Double.isNaN(surface) ? Double.POSITIVE_INFINITY : surface - SURFACE_MARGIN;
    }

    /**
     * @return y of the fluid surface over {@code pos}; NaN when not in fluid, or no surface within
     *      {@link #MAX_SUBMERGENCE} (overhang / sealed aquifer: nothing to broach through).
     */
    private double fluidSurfaceAbove(Vec3 pos) {
        BlockPos cursor = BlockPos.containing(pos);
        if (this.missile.level().getFluidState(cursor).isEmpty()) {
            return Double.NaN;
        }
        for (int step = 0; step < MAX_SUBMERGENCE; step++) {
            BlockPos above = cursor.above();
            if (this.missile.level().getFluidState(above).isEmpty()) {
                return above.getY();
            }
            cursor = above;
        }
        return Double.NaN;
    }

    /** @return true if written off this tick (broached for good); caller stops ticking. */
    boolean tickBroach() {
        if (!this.medium.hasCeiling() || this.phase == Phase.ASCEND || this.missile.isSubmerged()) {
            this.dryTicks = 0;
            return false;
        }
        this.dryTicks++;
        if (this.dryTicks < BROACH_GRACE_TICKS) {
            Vec3 v = this.missile.getDeltaMovement();
            this.missile.setDeltaMovement(v.x, Math.min(v.y, 0.0) - BROACH_RECOVERY, v.z);
            return false;
        }
        this.missile.recordEvent(WFEventType.DESTROYED, "broached");
        this.missile.damage().shootDown(DownedAction.FIZZLE);
        return true;
    }

    /**
     * @param cutoff tops at/above this are dropped from the fan: +inf in air (every ridge counts); waterline
     *      submerged (a column reaching it is a bank, not a seabed).
     */
    private double scanTerrainTop(Heightmap.Types heightmap, double centerX, double centerZ, double radius,
                                  double cutoff) {
        int r = (int) Math.ceil(radius);
        int step = Math.max(2, r / 4);
        int cx = Mth.floor(centerX);
        int cz = Mth.floor(centerZ);
        int maxTop = this.missile.level().getMinBuildHeight();
        boolean sampled = false;
        ServerLevel server = this.missile.level() instanceof ServerLevel sl ? sl : null;
        for (int ox = -r; ox <= r; ox += step) {
            for (int oz = -r; oz <= r; oz += step) {
                int wx = cx + ox;
                int wz = cz + oz;
                if (server == null) {
                    int clientTop = this.missile.level().getHeight(heightmap, wx, wz);
                    if (clientTop < cutoff) {
                        maxTop = Math.max(maxTop, clientTop);
                        sampled = true;
                    }
                    continue;
                }
                LevelChunk chunk = server.getChunkSource().getChunkNow(
                        SectionPos.blockToSectionCoord(wx), SectionPos.blockToSectionCoord(wz));
                if (chunk == null) {
                    continue;
                }
                int top = chunk.getHeight(heightmap, wx, wz) + 1;
                if (top >= cutoff) {
                    continue;
                }
                sampled = true;
                if (top > maxTop) {
                    maxTop = top;
                }
            }
        }
        return sampled ? maxTop : this.missile.getY() - this.terrainClearance;
    }

    /** Dive angle (degrees below horizontal) the terminal stages fly this tick. */
    public double resolveDiveAngle(FlightContext ctx) {
        if (!Double.isNaN(this.attackAngle)) {
            return this.attackAngle;
        }
        double lo = Math.min(this.minDiveAngle, this.maxDiveAngle);
        double hi = Math.max(this.minDiveAngle, this.maxDiveAngle);
        double dy = this.missile.getY() - ctx.target().y;
        double direct = Math.toDegrees(Math.atan2(dy, Math.max(ctx.horizontalDist(), 1.0e-4)));
        double ideal = Mth.clamp(direct, lo, hi);
        if (hi - lo < 1.0e-4 || ctx.horizontalDist() > DIVE_RAYCAST_RANGE) {
            return ideal;
        }
        double chosen = ideal;
        double bestDelta = Double.POSITIVE_INFINITY;
        boolean anyClear = false;
        for (int i = 0; i <= DIVE_ANGLE_SAMPLES; i++) {
            double a = lo + (hi - lo) * i / DIVE_ANGLE_SAMPLES;
            if (this.diveApproachClear(ctx, a)) {
                double delta = Math.abs(a - ideal);
                if (delta < bestDelta) {
                    bestDelta = delta;
                    chosen = a;
                    anyClear = true;
                }
            }
        }
        return anyClear ? chosen : ideal;
    }

    private boolean diveApproachClear(FlightContext ctx, double angleDeg) {
        double theta = Math.toRadians(angleDeg);
        double cos = Math.cos(theta);
        Vec3 travel = new Vec3(ctx.nx() * cos, -Math.sin(theta), ctx.nz() * cos);
        double dy = this.missile.getY() - ctx.target().y;
        double length = Mth.clamp(Math.sqrt(ctx.horizontalDist() * ctx.horizontalDist() + dy * dy), 8.0,
                DIVE_RAYCAST_RANGE);
        Vec3 aim = ctx.target().add(0.0, 0.5, 0.0);
        Vec3 entry = aim.subtract(travel.scale(length));
        BlockHitResult hit = this.missile.level().clip(new ClipContext(entry, aim,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this.missile));
        return hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(aim) < 4.0;
    }

    /** Rough ticks to impact: climb, transit, terminal descent, remaining loiter. */
    public int estimateArrivalTicks() {
        double cruiseAltitudeY = this.cruiseMode == CruiseMode.HIGH_ALTITUDE
                ? this.cruiseAltitude : this.missile.getY() + this.terrainClearance;
        int loiterRemaining = Math.max(0, LoiterStage.loiterTicksOf(this.cruiseStageId) - this.loiterTicks);
        return ArrivalEstimator.estimateTicks(this.missile.position(), this.target, this.cruiseSpeed,
                this.getAscentSpeed(), cruiseAltitudeY, loiterRemaining, this.attackApproachDir, this.approachJoinCap);
    }

    void rebuildProfile() {
        this.profile = FlightProfile.fromIds(this.ascentStageId, this.cruiseStageId, this.attackStageId);
    }

    public FlightStage attackStage() {
        return this.profile.stage(Phase.ATTACK);
    }

    public Vec3 getTarget() {
        return this.target;
    }

    public void setTarget(Vec3 target) {
        this.target = target;
    }

    public Phase getPhase() {
        return this.phase;
    }

    public CruiseMode getCruiseMode() {
        return this.cruiseMode;
    }

    public Medium getMedium() {
        return this.medium;
    }

    void setMedium(Medium medium) {
        this.medium = medium;
        this.missile.syncSubmergedMedium(medium.hasCeiling());
    }

    public double getCruiseAltitude() {
        return this.cruiseAltitude;
    }

    public double getTerrainClearance() {
        return this.terrainClearance;
    }

    public double getMaxTurnRate() {
        return this.maxTurnRate;
    }

    /** Turn limit at {@code speed}: fixed rate, or the G limit over speed with dynamic-pressure falloff. */
    public double turnRateAt(double speed) {
        if (this.gLimit <= 0.0) {
            return this.maxTurnRate;
        }
        double v = Math.max(speed, 0.05);
        double q = Math.min(1.0, v * v / (this.gRefSpeed * this.gRefSpeed));
        return this.gLimit * q / v;
    }

    public double getCruiseSpeed() {
        return this.cruiseSpeed;
    }

    public double getAscentSpeed() {
        return Double.isNaN(this.ascentSpeed) ? ascentSpeedFor(this.cruiseSpeed) : this.ascentSpeed;
    }

    public double getLaunchY() {
        return this.launchPos != null ? this.launchPos.y : this.missile.getY();
    }

    /** Explicit dive angle (degrees below horizontal), or NaN = auto-pick in [min, max]. */
    public double getAttackAngle() {
        return this.attackAngle;
    }

    public double getMinDiveAngle() {
        return this.minDiveAngle;
    }

    public double getMaxDiveAngle() {
        return this.maxDiveAngle;
    }

    /** Unit horizontal direction target -> approach side; null = unconstrained. */
    @Nullable
    public Vec3 getAttackApproachDir() {
        return this.attackApproachDir;
    }

    /** Negated {@link #getAttackApproachDir}: the terminal run's travel direction. */
    @Nullable
    public Vec3 getAttackTravelDir() {
        return this.attackApproachDir == null ? null
                : new Vec3(-this.attackApproachDir.x, 0.0, -this.attackApproachDir.z);
    }

    /** Normalised; null/near-zero clears. A constraint switches the cruise stage to {@link ApproachStage}. */
    public void setAttackApproachDir(@Nullable Vec3 dir) {
        if (dir == null) {
            this.attackApproachDir = null;
            return;
        }
        double h = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        if (h < 1.0E-4) {
            this.attackApproachDir = null;
            return;
        }
        this.attackApproachDir = new Vec3(dir.x / h, 0.0, dir.z / h);
        ResourceLocation approach = FlightStageRegistry.rl(ApproachStage.INSTANCE.id());
        if (!approach.equals(this.cruiseStageId) && FlightStageRegistry.exists(Phase.CRUISE, approach)) {
            this.cruiseStageId = approach;
            this.rebuildProfile();
        }
    }

    public AttackProfile getAttackProfile() {
        return this.attackProfile;
    }

    public double getApproachJoinCap() {
        return this.approachJoinCap;
    }

    /** Cruise-altitude memory for the cruise stage's smoothing; NaN before the first cruise tick. */
    public double getCruiseTargetY() {
        return this.cruiseTargetY;
    }

    public void setCruiseTargetY(double cruiseTargetY) {
        this.cruiseTargetY = cruiseTargetY;
    }

    public ResourceLocation getAscentStageId() {
        return this.ascentStageId;
    }

    public ResourceLocation getCruiseStageId() {
        return this.cruiseStageId;
    }

    public ResourceLocation getAttackStageId() {
        return this.attackStageId;
    }

    public int getLoiterTicks() {
        return this.loiterTicks;
    }

    public void setLoiterTicks(int loiterTicks) {
        this.loiterTicks = loiterTicks;
    }

    public boolean isDiveCommitted() {
        return this.diveCommitted;
    }

    public void setDiveCommitted(boolean diveCommitted) {
        this.diveCommitted = diveCommitted;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("TargetX", this.target.x);
        tag.putDouble("TargetY", this.target.y);
        tag.putDouble("TargetZ", this.target.z);
        tag.putString("Phase", this.phase.name());
        tag.putString("CruiseMode", this.cruiseMode.name());
        tag.putString("Medium", this.medium.name());
        tag.putDouble("CruiseAltitude", this.cruiseAltitude);
        tag.putDouble("TerrainClearance", this.terrainClearance);
        tag.putDouble("MaxTurnRate", this.maxTurnRate);
        tag.putDouble("GLimit", this.gLimit);
        tag.putDouble("GRefSpeed", this.gRefSpeed);
        tag.putDouble("CruiseSpeed", this.cruiseSpeed);
        tag.putDouble("AscentSpeed", this.ascentSpeed);
        tag.putDouble("AttackAngle", this.attackAngle);
        tag.putDouble("MinDiveAngle", this.minDiveAngle);
        tag.putDouble("MaxDiveAngle", this.maxDiveAngle);
        if (this.attackApproachDir != null) {
            tag.putDouble("AttackApproachX", this.attackApproachDir.x);
            tag.putDouble("AttackApproachZ", this.attackApproachDir.z);
        }
        tag.putString("AttackProfile", this.attackProfile.name());
        tag.putDouble("ApproachJoinCap", this.approachJoinCap);
        tag.putInt("CruiseTicks", this.cruiseTicks);
        tag.putString("AscentStage", this.ascentStageId.toString());
        tag.putString("CruiseStage", this.cruiseStageId.toString());
        tag.putString("AttackStage", this.attackStageId.toString());
        tag.putInt("LoiterTicks", this.loiterTicks);
        tag.putBoolean("DiveCommitted", this.diveCommitted);
        tag.putDouble("CruiseTargetY", this.cruiseTargetY);
        if (this.launchPos != null) {
            tag.putDouble("LaunchX", this.launchPos.x);
            tag.putDouble("LaunchY", this.launchPos.y);
            tag.putDouble("LaunchZ", this.launchPos.z);
        }
        return tag;
    }

    public void load(CompoundTag tag) {
        this.target = new Vec3(tag.getDouble("TargetX"), tag.getDouble("TargetY"), tag.getDouble("TargetZ"));
        this.phase = Phase.valueOf(tag.getString("Phase"));
        this.cruiseMode = CruiseMode.valueOf(tag.getString("CruiseMode"));
        this.setMedium(Medium.valueOf(tag.getString("Medium")));
        this.cruiseAltitude = tag.getDouble("CruiseAltitude");
        this.terrainClearance = tag.getDouble("TerrainClearance");
        this.maxTurnRate = tag.getDouble("MaxTurnRate");
        this.gLimit = tag.getDouble("GLimit");
        this.gRefSpeed = tag.getDouble("GRefSpeed");
        this.cruiseSpeed = tag.getDouble("CruiseSpeed");
        this.ascentSpeed = tag.getDouble("AscentSpeed");
        this.attackAngle = tag.getDouble("AttackAngle");
        this.minDiveAngle = tag.getDouble("MinDiveAngle");
        this.maxDiveAngle = tag.getDouble("MaxDiveAngle");
        this.attackProfile = AttackProfile.byName(tag.getString("AttackProfile"));
        this.approachJoinCap = tag.getDouble("ApproachJoinCap");
        this.cruiseTicks = tag.getInt("CruiseTicks");
        this.ascentStageId = FlightStageRegistry.parse(Phase.ASCEND, tag.getString("AscentStage"));
        this.cruiseStageId = FlightStageRegistry.parse(Phase.CRUISE, tag.getString("CruiseStage"));
        this.attackStageId = FlightStageRegistry.parse(Phase.ATTACK, tag.getString("AttackStage"));
        this.rebuildProfile();
        this.attackApproachDir = null;
        if (tag.contains("AttackApproachX")) {
            this.setAttackApproachDir(new Vec3(tag.getDouble("AttackApproachX"), 0.0, tag.getDouble("AttackApproachZ")));
        }
        this.loiterTicks = tag.getInt("LoiterTicks");
        this.diveCommitted = tag.getBoolean("DiveCommitted");
        this.cruiseTargetY = tag.getDouble("CruiseTargetY");
        this.launchPos = tag.contains("LaunchX")
                ? new Vec3(tag.getDouble("LaunchX"), tag.getDouble("LaunchY"), tag.getDouble("LaunchZ")) : null;
    }
}
