package com.wf.wfballistics;

import com.wf.wfballistics.chunk.MissileChunkLoader;
import com.wf.wfballistics.compat.WarforgeCompat;
import com.wf.wfballistics.entity.OBBEntity;
import com.wf.wfballistics.flight.*;
import com.wf.wfballistics.fx.ExplosionCreator;
import com.wf.wfballistics.sim.IMissileListener;
import com.wf.wfballistics.sim.MissileListenerRegistry;
import com.wf.wfballistics.sim.MissileSimConfig;
import com.wf.wfballistics.sim.SimMissileManager;
import com.wf.wfballistics.swarm.SwarmManager;
import com.wf.wfballistics.util.OBB;
import com.wf.wfballistics.util.SweptCollision;
import com.wf.wfballistics.warhead.RecursiveFrag;
import com.wf.wfballistics.warhead.WarheadRegistry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class MissileEntity extends Projectile implements OBBEntity, IMissileListener {

    public static final int DEFAULT_FRAGMENT_COUNT = 24;

    // Default exhaust/trail tint: the hot RGB (0xRRGGBB) the client-side plume fades from as it cools. An
    // orange rocket flame, matching the legacy hard-coded trail colour. Per-missile via the Builder.
    public static final int DEFAULT_EXHAUST_COLOR = 0xFFB20D;

    public static final double CRUISE_SPEED = 1.0;
    public static final double ASCENT_SPEED_FACTOR = 1.5;
    public static final double MIN_ASCENT_SPEED = 1.5;
    public static final double MIN_ATTACK_ANGLE = 5.0;
    public static final double MAX_ATTACK_ANGLE = 90.0;
    // Safety/arming: the warhead is inert until the missile has flown this far from its launch point, so it
    // can't fuze, impact-detonate, or be blown up by damage while still on/near the launcher.
    public static final double ARMING_DISTANCE = 6.0;
    // Interception damage pool: CIWS fire / interceptors chip this down; at <= 0 the missile is destroyed.
    public static final float DEFAULT_HEALTH = 50.0f;
    // Change in actual speed per tick (blocks/tick^2) while spooling up toward / braking down to the speed the
    // guidance asks for. cruiseSpeed is the target/max speed; these govern how fast it is reached and shed.
    public static final double DEFAULT_ACCELERATION = 0.15;
    public static final double DEFAULT_DECELERATION = 0.25;
    private static final double DIVE_ACCELERATION = 1.5;
    // Ballistic fall (out of fuel): downward accel, terminal speed, and a light horizontal drag so momentum
    // (inertia) carries the missile forward as it arcs down instead of being zeroed.
    private static final double FUEL_OUT_GRAVITY = 0.05;
    private static final double TERMINAL_FALL_SPEED = -3.9;
    private static final double FALL_HORIZONTAL_DRAG = 0.99;
    // Evasive boost: a short high-speed burst (extra speed, so an interceptor whiffs) bought with a chunk of
    // fuel — deliberately inefficient, so repeated dodging drains the tank and a dry missile can't dodge.
    private static final double BOOST_SPEED_MULT = 2.0;
    private static final int BOOST_DURATION = 8;
    private static final int BOOST_FUEL_COST = 150;
    // Jink (opt-in evasive maneuvering): per-tick lateral deflection of the boosted velocity toward a break
    // direction, so the dodge is a hard sidestep off the interceptor's committed lead rather than a predictable
    // straight-line sprint. tan of the deflection angle (~31 degrees/tick), self-limiting as the heading turns.
    private static final double JINK_DEFLECT = 0.6;
    // Formation flight: how hard a subordinate steers toward its slot, and how much it may overspeed its
    // cruise to close a gap (so it throttles up when behind and eases back to the commander's pace on station).
    private static final double FORMATION_GAIN = 0.25;
    private static final double FORMATION_MAX_OVERSPEED = 1.6;
    // Radius (blocks) of the saturation spread each subordinate disperses to when the swarm breaks for the
    // terminal attack, so they fan out across the target area instead of stacking on one point.
    private static final double SATURATION_SPREAD = 10.0;
    // Ticks between sonic-boom shock rings while travelling supersonic.
    private static final int SONIC_BOOM_INTERVAL = 6;
    private static final EntityDataAccessor<String> MODEL_ID =
            SynchedEntityData.defineId(MissileEntity.class, EntityDataSerializers.STRING);
    // Synced so the client-side exhaust trail can tint itself per missile (see InstancedTrailEffect).
    private static final EntityDataAccessor<Integer> EXHAUST_COLOR =
            SynchedEntityData.defineId(MissileEntity.class, EntityDataSerializers.INT);
    private static final double TURN_AGILITY = 1.0; // radians * (model units) per tick
    // Predictive friendly deconfliction ("don't ram your own"): a missile looks AVOID_HORIZON ticks ahead
    // for friendly missiles (same swarm or launcher) whose closest approach would fall within AVOID_MIN_SEP
    // blocks, and if so applies a small course offset (perpendicular to the closing path, complementary
    // between the pair, bounded by AVOID_STRENGTH blocks/tick) so they slip past instead of colliding.
    // AVOID_RADIUS bounds the neighbour search. The offset only exists while a collision is predicted.
    private static final double AVOID_RADIUS = 24.0;
    private static final int AVOID_HORIZON = 20;
    private static final double AVOID_MIN_SEP = 4.0;
    private static final double AVOID_STRENGTH = 1.5;
    private static final double ARMING_DISTANCE_SQ = ARMING_DISTANCE * ARMING_DISTANCE;
    // Failsafe: arm anyway after this many ticks so a missile that somehow can't travel never stays a live dud.
    private static final int ARMING_FAILSAFE_TICKS = 100;
    // Fuel is measured in ticks of powered flight (one burned per tick). When the tank is dry the missile
    // stops thrusting and falls ballistically. Default is generous so ordinary strikes complete; a redirected
    // or long-loitering missile/drone can still run out. Per-missile via the Builder / preset.
    public static int DEFAULT_FUEL_TICKS = 1200;
    // Oriented bounding box that wraps the (elongated) missile model, giving projectiles an accurate,
    // rotation-aware hitbox instead of the coarse vanilla AABB. Kept live-updated in refreshObb().
    private final OBB obb = new OBB(new Vector3d(), new Vector3d(), new Quaterniond(), OBB.Part.BODY);
    private final List<OBB> obbList = List.of(this.obb);
    // Forces the chunks the missile needs while flying (own chunk ticking + non-ticking look-ahead fan).
    private final MissileChunkLoader chunkLoader = new MissileChunkLoader();
    // Transform (position + heading) the OBB was last built for; refreshObb() skips recomputation while
    // these are unchanged, so the repeated getOBBs() calls during a hit-check don't rebuild it each time.
    private double obbX = Double.NaN, obbY, obbZ, obbDx, obbDy, obbDz;
    private Vec3 target = Vec3.ZERO;
    private Phase phase = Phase.ASCEND;
    // How this missile flies: one swappable stage per phase (ascent curve, cruise/loiter, attack run),
    // each resolved from FlightStageRegistry by id and composed into the runtime profile. Selecting stages
    // independently is what lets a missile become e.g. a loitering drone; the choice survives save/load.
    // Declared before the profile so the field initialisers run in order.
    private ResourceLocation ascentStageId = FlightStageRegistry.defaultId(Phase.ASCEND);
    private ResourceLocation cruiseStageId = FlightStageRegistry.defaultId(Phase.CRUISE);
    private ResourceLocation attackStageId = FlightStageRegistry.defaultId(Phase.ATTACK);
    private FlightProfile flightProfile = FlightProfile.fromIds(this.ascentStageId, this.cruiseStageId, this.attackStageId);
    // Loitering-munition timer: ticks spent orbiting on-station (see LoiterStage). Persisted.
    private int loiterTicks = 0;
    private CruiseMode cruiseMode = CruiseMode.TERRAIN_FOLLOW;
    private double cruiseAltitude = 200.0;
    private double terrainClearance = 24.0;
    // Smoothed terrain-follow altitude the missile actually flies toward (eased from the raw scan);
    // NaN until the first cruise tick, then re-seeded from the current scan.
    private double cruiseTargetY = Double.NaN;
    // Horizontal cruise speed (blocks/tick). The off-world simulation advances at this same rate so
    // simulated travel time matches an in-world flight.
    private double cruiseSpeed = CRUISE_SPEED;
    private double ascentSpeed = Double.NaN;
    private double attackAngle = Double.NaN;
    // Airburst fuze: while diving, detonate in the air once the missile is within this many
    // blocks (Y difference) above the target. 0 disables it, giving a contact/ground detonation.
    private float explosionOffset = 0.0f;
    private ResourceLocation detonationId = WarheadRegistry.defaultId();
    // Number of bomblets the FRAGMENTATION warhead scatters; per-missile, set via the Builder.
    private int fragmentCount = DEFAULT_FRAGMENT_COUNT;
    private WarheadRegistry.Detonation detonation = WarheadRegistry.STANDARD;
    // Max change in velocity direction per tick, in radians. Default scales from the model's length
    // (longer airframe = less nimble); overridable via the Builder.
    private double maxTurnRate = TURN_AGILITY / MissileModels.length(MissileModels.DEFAULT);
    // Consecutive ticks spent in CRUISE; gates the offload-to-simulation transition.
    private int cruiseTicks = 0;
    private float health = DEFAULT_HEALTH;
    // Guards against re-entrant detonation: the warhead's own blast can hurt() this still-present missile,
    // which would otherwise re-enter detonate() and recurse until the stack overflows.
    private boolean detonated = false;
    // Arming state (see ARMING_DISTANCE): the launch point, captured on the first server tick, and whether
    // the warhead has gone live. While unarmed all detonation triggers are suppressed.
    private Vec3 launchPos = null;
    private boolean armed = false;
    // Recursive-fragmentation payload: how many more times this missile splits into child missiles before
    // the leaf generation does a real blast (see RecursiveFrag). 0 = never splits (a normal missile).
    private int splitDepth = 0;
    // Groups a fragmentation family so its members never collide with one another (see canHitEntity).
    // 0 = no family; the missile collides with every other missile normally.
    private long swarmId = 0L;
    // Formation leader flag: in a swarm with a commander, the commander flies the mission and the others hold a
    // wedge formation on it (see SwarmManager). If the commander is lost, a successor is promoted and the rest
    // obey the new commander. Swarms with no commander (e.g. a RecursiveFrag family) just fly independently.
    private boolean commander = false;
    // A subordinate latches this once the commander begins its terminal dive: it breaks formation and runs its
    // own attack on a dispersed aim point (saturation spread) instead of re-forming.
    private boolean brokeFormation = false;
    // Launcher/control identity (see MissileDispenserBlockEntity): missiles sharing a non-null control id
    // (fired from the same launcher, or one recursive family) are friendly and never collide with each other.
    private UUID controlId = null;
    // WarForge faction id (see WarforgeCompat): the "team" a missile belongs to — a player's faction, or the
    // faction claiming a launcher/battery's land. Missiles of the same / allied / truced faction are friendly,
    // which is what stops a faction's defenses engaging its own missiles across different launchers.
    private UUID teamId = null;

    // Interceptor mode
    // When true this missile is an interceptor: it homes on another (moving) missile, re-writing its aim point
    // to a per-tick lead point, and resolves a kill by a random roll (see tryIntercept) rather than a warhead.
    private boolean interceptor = false;
    // NEAREST re-acquires the closest hostile missile each tick; LOCK homes on one specific target UUID.
    private InterceptMode interceptMode = InterceptMode.NEAREST;
    // The specific target's UUID in LOCK mode (null in NEAREST mode).
    private UUID lockTargetId = null;
    // Per-interceptor kill probability rolled on closest approach (default 90%).
    private float interceptChance = MissileSimConfig.DEFAULT_INTERCEPT_CHANCE;
    // Transient: the UUID being homed on this tick (set by updateInterceptTarget, read by tryIntercept), and
    // how many consecutive ticks we've had no resolvable target (drives the give-up/fizzle timeout).
    private UUID currentTargetId = null;
    private int noTargetTicks = 0;
    // Transient: set each tick by leadPoint — true when the interceptor cannot win a timed intercept (the
    // target outruns it) and is instead attempting to cross the target's flight path, which tryIntercept
    // rolls at a reduced chance.
    private boolean crossingShot = false;

    // Designated strike target (non-interceptor missiles/drones): a specific entity to hit. While set and
    // alive, the missile re-aims onto it each tick; a loitering drone orbits it and pounces when on-station,
    // and keeps loitering (until fuel runs out) if the target isn't currently present.
    private UUID designatedTargetId = null;

    // Low-observable: a stealth missile is invisible to automatic detection (interceptor NEAREST acquisition,
    // interceptor batteries, CIWS). It can still be engaged only by manually locking its exact UUID.
    private boolean stealth = false;
    // Evasion (0..1): chance to boost clear of an interception attempt — the "tier" that lets a better missile
    // escape interceptors more often. On a successful dodge the missile burns fuel for a speed burst that
    // turns the hit into a miss (see evadeBoost); the roll is amplified during the terminal dive.
    private float evasion = 0.0f;
    // Evasive maneuvering toggle: when set, an evasion boost also jinks the missile off its course (a lateral
    // break away from the interceptor) instead of only sprinting straight, so the dodge is far less predictable
    // and genuinely displaces it from the interceptor's lead (see evadeBoost / the guidance boost step).
    private boolean evasiveManeuver = false;
    // Remaining ticks of an active evasive speed burst (see evadeBoost / the guidance boost step).
    private int boostTicks = 0;
    // Break direction captured when a maneuvering boost triggers: a world-space unit vector (roughly
    // perpendicular to the heading, away from the interceptor, randomised) the boost deflects toward. Transient,
    // like boostTicks; a reload mid-burst just finishes the burst straight.
    private Vec3 boostManeuver = null;

    //Propulsion and fuel
    // Whether the tank holds solid (preloaded charge) or liquid (kerosene) propellant. Burn is identical for
    // both (ticks of thrust); the type is carried for flavour and future tank/bucket refuelling.
    private FuelType fuelType = FuelType.SOLID;
    private int fuelCapacity = DEFAULT_FUEL_TICKS; // initial tank size (telemetry / refuel reference)
    private int fuel = DEFAULT_FUEL_TICKS;         // remaining ticks of powered flight
    private double acceleration = DEFAULT_ACCELERATION;
    private double deceleration = DEFAULT_DECELERATION;

    public MissileEntity(EntityType<? extends Projectile> type, Level level) {
        super(type, level);

        // The missile integrates its own flight each tick, so vanilla physics and gravity are disabled to
        // keep them from fighting the guidance; ballistic fall is applied manually once fuel runs out.
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    public static Builder builder(EntityType<? extends Projectile> type, Level level) {
        return new Builder(type, level);
    }

    private static Vec3 constrainTurn(Vec3 current, Vec3 desired, double maxTurnRate) {
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

        Vec3 newDir = curDir.scale(Math.cos(maxTurnRate))
                .add(axis.cross(curDir).scale(Math.sin(maxTurnRate)));
        return newDir.normalize().scale(desiredSpeed);
    }

    private static double approach(double cur, double target, double maxDelta) {
        double diff = target - cur;
        if (Math.abs(diff) <= maxDelta) {
            return target;
        }
        return cur + Math.copySign(maxDelta, diff);
    }

    /**
     * @return the target UUIDs already "claimed" by interceptors in {@code missiles}, from the perspective of
     * a claimant with entity id {@code selfId} (pass {@link Integer#MAX_VALUE} for a battery, which yields to
     * every interceptor). A LOCK interceptor always claims its lock target; a NEAREST interceptor claims its
     * current target only against higher-id peers — so exactly one interceptor keeps a shared target and the
     * rest divert to other threats (no dogpiling, and no two interceptors swapping off each other forever).
     */
    public static Set<UUID> claimedTargets(List<MissileEntity> missiles, int selfId) {
        Set<UUID> claimed = new HashSet<>();
        for (MissileEntity o : missiles) {
            if (!o.interceptor || o.getId() == selfId) {
                continue;
            }
            if (o.interceptMode == InterceptMode.LOCK && o.lockTargetId != null) {
                claimed.add(o.lockTargetId);
            } else if (o.currentTargetId != null && o.getId() < selfId) {
                claimed.add(o.currentTargetId);
            }
        }
        return claimed;
    }

    private static double smallestPositive(double a, double b) {
        if (a > 0.0 && b > 0.0) {
            return Math.min(a, b);
        }
        if (a > 0.0) {
            return a;
        }
        return b > 0.0 ? b : -1.0;
    }

    /**
     * A visible/audible flak burst at the intercept point so a kill and a miss are easy to tell apart at
     * range: a bright burst + explosion boom on a kill, a small puff + lighter pop on a miss.
     */
    private static void spawnInterceptBurst(ServerLevel level, Vec3 p, boolean kill) {
        level.sendParticles(ParticleTypes.EXPLOSION, p.x, p.y, p.z, kill ? 3 : 1, 1.0, 1.0, 1.0, 0.0);
        level.sendParticles(ParticleTypes.FLAME, p.x, p.y, p.z, kill ? 24 : 6, 1.4, 1.4, 1.4, 0.06);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, p.x, p.y, p.z, kill ? 14 : 5, 1.6, 1.6, 1.6, 0.02);
        level.playSound(null, p.x, p.y, p.z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE,
                kill ? 3.0f : 1.3f, kill ? 1.0f : 1.5f);
    }

    @Override
    public void tick() {
        super.tick();

        if (this.level().isClientSide) {
            // Keep the AABB wrapping the oriented model each frame so culling / F3+B stay correct;
            // the client doesn't run the flight logic below and never calls move().
            this.setBoundingBox(this.makeBoundingBox());
            return;
        }

        ServerLevel serverLevel = (ServerLevel) this.level();

        if (this.launchPos == null) {
            this.launchPos = this.position();
        }

        Vec3 currentPos = this.position();


        if (this.fuel <= 0) {
            this.ballisticFall(serverLevel, currentPos);
            return;
        }
        this.fuel--;


        if (this.interceptor && !this.updateInterceptTarget(serverLevel, currentPos)) {
            return;
        }

        if (!this.interceptor && this.designatedTargetId != null) {
            Entity designated = serverLevel.getEntity(this.designatedTargetId);
            if (designated != null && designated.isAlive() && designated != this) {
                this.setTarget(designated.position());
            }
        }

        Vec3 targetPos = this.getTarget();

        double dx = targetPos.x - currentPos.x;
        double dz = targetPos.z - currentPos.z;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);


        boolean loadFan = this.cruiseMode == CruiseMode.TERRAIN_FOLLOW
                || this.phase == Phase.ATTACK
                || horizontalDist < MissileSimConfig.FAN_TERMINAL_RANGE;
        this.chunkLoader.update(this, serverLevel, currentPos, this.getDeltaMovement(), loadFan);

        // Guidance: delegate "how it flies" to the active flight stage for this phase.
        double nx = 0.0;
        double nz = 0.0;
        if (horizontalDist > 1.0E-3) {
            nx = dx / horizontalDist;
            nz = dz / horizontalDist;
        }
        double safeAltitude = this.computeSafeAltitude(currentPos, dx, dz, horizontalDist);
        FlightContext ctx = new FlightContext(currentPos, targetPos, horizontalDist, nx, nz, safeAltitude);

        // Formation flight: a swarm subordinate with a live commander holds its wedge slot (throttling speed to
        // maintain station) instead of flying the mission stages — the commander leads the strike. Everything
        // else (the commander, or a subordinate whose commander is gone) flies its own phased mission.
        MissileEntity commander = (this.swarmId != 0L && !this.commander && !this.brokeFormation)
                ? SwarmManager.commander(serverLevel, this.swarmId) : null;
        if (commander != null && commander.isAlive() && commander.getPhase() == Phase.ATTACK) {
            // The commander has begun its terminal dive: break formation, disperse onto our own aim point
            // around the target (a saturation spread), and run our own attack from here on.
            this.brokeFormation = true;
            this.setTarget(this.saturationAim(commander.getTarget()));
            this.phase = Phase.ATTACK;
            commander = null;
        }
        boolean inFormation = commander != null && commander != this && commander.isAlive();

        Vec3 velocity;
        if (inFormation) {
            velocity = this.formationGuide(commander);
        } else {
            // Advance the phase (each stage decides when it is done), then fly the resulting phase's stage.
            Phase next = this.flightProfile.stage(this.phase).next(this, ctx);
            if (next != null) {
                this.phase = next;
            }
            this.cruiseTicks = (this.phase == Phase.CRUISE) ? this.cruiseTicks + 1 : 0;

            velocity = this.flightProfile.stage(this.phase).guide(this, ctx);

            // Coordinate with nearby friendly missiles: if their paths would cross, veer just enough to slip
            // past instead of ramming. Folded into the desired velocity so the turn-rate limit smooths it in.
            if (this.controlId != null || this.swarmId != 0L) {
                velocity = velocity.add(this.avoidFriendlies());
            }
        }

        // Limit how far the heading can swing this tick so the missile arcs instead of snapping direction,
        // then ramp the actual speed toward the guidance speed under the accel/decel limits (real spool-up
        // from launch and smooth braking) rather than instantly matching it.
        velocity = constrainTurn(this.getDeltaMovement(), velocity, this.maxTurnRate);
        if (this.boostTicks > 0) {
            this.boostTicks--;
            velocity = velocity.scale(BOOST_SPEED_MULT);
            if (this.boostManeuver != null) {
                double sp = velocity.length();
                if (sp > 1.0E-6) {
                    Vec3 deflected = velocity.scale(1.0 / sp).add(this.boostManeuver.scale(JINK_DEFLECT));
                    double dlen = deflected.length();
                    if (dlen > 1.0E-6) {
                        velocity = deflected.scale(sp / dlen);
                    }
                }
            }
            serverLevel.sendParticles(ParticleTypes.FLAME, this.getX(), this.getY(), this.getZ(),
                    6, 0.3, 0.3, 0.3, 0.02);
        } else {
            this.boostManeuver = null; // burst over: drop the stale break so the next dodge picks a fresh one
        }
        velocity = this.applyThrust(velocity);

        this.setDeltaMovement(velocity);

        this.hasImpulse = true;
        this.move(net.minecraft.world.entity.MoverType.SELF, this.getDeltaMovement());

        // Position and heading just changed: re-fit the OBB and the vanilla AABB around the model.
        this.setBoundingBox(this.makeBoundingBox());

        // Sonic boom
        double machSpeed = this.getDeltaMovement().length();
        if (machSpeed >= MissileSimConfig.SUPERSONIC_SPEED
                && (this.tickCount + this.getId()) % SONIC_BOOM_INTERVAL == 0) {
            ExplosionCreator.sonicBoom(this.level(), this.getX(), this.getY(), this.getZ(),
                    (float) Mth.clamp(machSpeed * 1.5, 6.0, 24.0));
        }

        // Interceptor kill check
        if (this.interceptor && this.tryIntercept(currentPos)) {
            return;
        }

        // Interceptors never offload to the off-world sim
        if (!this.interceptor
                && this.phase == Phase.CRUISE
                && this.cruiseTicks > MissileSimConfig.CRUISE_SIM_DELAY_TICKS
                && horizontalDist > MissileSimConfig.DESTINATION_RANGE
                && !SimMissileManager.nearAnyListener(serverLevel, this.position())) {
            if (this.swarmId == 0L) {
                SimMissileManager.startSim(this);
                return;
            }
            if (this.commander) {
                List<MissileEntity> subs = this.formationSubordinates(serverLevel);
                if (subs != null) {
                    SimMissileManager.startSimSwarm(this, subs);
                    return;
                }
            }
        }

        // Detonation triggers are inert until the missile has armed clear of the launcher.
        if (this.isArmed()) {
            // Airburst fuze
            if (this.explosionOffset > 0.0f && this.phase == Phase.ATTACK
                    && this.getY() - targetPos.y <= this.explosionOffset) {
                this.detonate(this.position());
                return;
            }

            // Swept collision over the segment actually traversed this tick (currentPos is the pre-move
            // position), extended forward by the body length. Replaces the vanilla post-move look-ahead
            // raycast, which let fast missiles tunnel through walls.
            HitResult hitResult = this.sweepForImpact(currentPos, this.getDeltaMovement());

            if (hitResult.getType() != HitResult.Type.MISS) {
                this.onMissileImpact(hitResult);
            }
        }
    }

    /**
     * Whether the warhead is live. Latches true once the missile has flown {@link #ARMING_DISTANCE} from its
     * launch point (or after {@link #ARMING_FAILSAFE_TICKS} as a stuck-missile failsafe), preventing a
     * detonation on or right next to the launcher.
     */
    private boolean isArmed() {
        if (this.armed) {
            return true;
        }
        boolean clearedLauncher = this.launchPos != null
                && this.position().distanceToSqr(this.launchPos) >= ARMING_DISTANCE_SQ;
        if (clearedLauncher || this.tickCount >= ARMING_FAILSAFE_TICKS) {
            this.armed = true;
        }
        return this.armed;
    }

    /**
     * The altitude the missile should hold this tick: the scanned terrain top (looked ahead toward the target
     * once past ascent) plus the terrain clearance. In HIGH_ALTITUDE mode it holds the fixed cruise height but
     * is floored to that terrain-safe height so it still climbs over rising ground instead of into it.
     * Shared by the flight stages via the {@link FlightContext}.
     */
    private double computeSafeAltitude(Vec3 pos, double dx, double dz, double horizontalDist) {
        double terrainScanRadius = 24.0; // how far around the missile we look for terrain
        double lookAhead = 32.0;         // how far ahead (toward target) to scan while cruising
        double scanCenterX = pos.x;
        double scanCenterZ = pos.z;
        if (this.phase != Phase.ASCEND && horizontalDist > 1.0E-3) {
            scanCenterX += (dx / horizontalDist) * lookAhead;
            scanCenterZ += (dz / horizontalDist) * lookAhead;
        }
        double terrainSafe = scanTerrainTop(scanCenterX, scanCenterZ, terrainScanRadius) + this.terrainClearance;
        if (this.cruiseMode == CruiseMode.HIGH_ALTITUDE) {
            return Math.max(this.cruiseAltitude, terrainSafe);
        }
        return terrainSafe;
    }

    /**
     * Scans safe height based on mc heightmap
     */
    private double scanTerrainTop(double centerX, double centerZ, double radius) {
        int r = (int) Math.ceil(radius);
        int step = Math.max(2, r / 4);
        int cx = Mth.floor(centerX);
        int cz = Mth.floor(centerZ);

        int maxTop = this.level().getMinBuildHeight();
        for (int ox = -r; ox <= r; ox += step) {
            for (int oz = -r; oz <= r; oz += step) {
                int top = this.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx + ox, cz + oz);
                if (top > maxTop) {
                    maxTop = top;
                }
            }
        }
        return maxTop;
    }

    protected boolean canHitEntity(Entity target) {
        if (target == this || target.isSpectator() || !target.isAlive()) {
            return false;
        }
        // Missiles aren't pickable (Entity#isPickable is false), so the vanilla check below would drop them
        // and they'd pass through one another. Allow missile-vs-missile explicitly so they collide/intercept
        // midair; everything else keeps the pickable requirement (this also keeps arrows from killing them).
        if (target instanceof MissileEntity other) {
            // Interceptors never resolve missile hits by physical collision — they use the closest-approach
            // roll in tryIntercept. Excluding both directions also stops a normal missile's own tick from
            // mutually detonating (and thus destroying) an interceptor's target without a roll being made.
            if (this.interceptor || other.interceptor) {
                return false;
            }
            // Friendlies never collide: same fragmentation family (swarm) or same launcher (control id).
            // This stops a salvo self-detonating while still letting rival launchers' missiles intercept.
            return !this.isFriendly(other);
        }
        return target.isPickable();
    }

    /**
     * @return true if {@code other} is on the same side — same swarm (frag family) or launcher (control id).
     */
    private boolean isFriendly(MissileEntity other) {
        if (SwarmManager.sameSwarm(this, other)) {
            return true;
        }
        if (this.controlId != null && this.controlId.equals(other.controlId)) {
            return true;
        }
        // Same / allied / truced WarForge faction
        return WarforgeCompat.areFactionsFriendly(this.teamId, other.teamId);
    }

    public UUID getTeamId() {
        return this.teamId;
    }

    public void setTeamId(UUID teamId) {
        this.teamId = teamId;
    }

    /**
     * A non-zero pick radius so {@code MixinProjectileUtil} inflates the target OBB when another missile
     * sweeps through it — makes fast crossing intercepts land reliably instead of needing a pixel-perfect
     * centerline crossing. Also lets look-at / raytrace targeting see the missile.
     */
    @Override
    public float getPickRadius() {
        return 0.5f;
    }

    /**
     * Swept, substepped block/entity collision over the traversed segment, extended forward by the body
     * length so the nose (not the base origin) triggers the hit. Non-tunneling at any speed. Fidelity and
     * substep budget are tuned via {@link MissileSimConfig}. See {@link SweptCollision}.
     */
    private HitResult sweepForImpact(Vec3 startPos, Vec3 delta) {
        return SweptCollision.sweep(this, this.level(), startPos, delta, this.noseForward(),
                this::canHitEntity,
                MissileSimConfig.COLLISION_MAX_SUBSTEP_DIST, MissileSimConfig.COLLISION_MAX_SUBSTEPS);
    }

    /**
     * Distance from the entity origin (mesh base) to the model's front face along the heading.
     */
    private double noseForward() {
        String id = this.getModelId();
        return MissileModels.center(id).y + MissileModels.dimensions(id).y * 0.5;
    }

    @Override
    public boolean enableAABB() {
        return false;
    }

    @Override
    public List<OBB> getOBBs() {
        // Kept current on demand so the box is right whichever side/tick order queries it (e.g. another
        // projectile's hit-check). refreshObb() is a no-op while the transform is unchanged, so the repeated
        // calls a single hit-check makes don't each rebuild it.
        this.refreshObb();
        return this.obbList;
    }

    /**
     * Rebuilds the body OBB (center, extents, rotation) from the current missile model and heading, unless
     * the position and heading are unchanged since the last build (then it's a no-op).
     * The model's local +Y axis is its nose/long axis, so we map +Y onto the velocity direction, matching
     * {@link MissileVisual}. Model units are rendered 1:1 with blocks, so mesh dimensions are used directly.
     */
    private void refreshObb() {
        double x = this.getX(), y = this.getY(), z = this.getZ();
        Vec3 move = this.getDeltaMovement();
        if (x == obbX && y == obbY && z == obbZ && move.x == obbDx && move.y == obbDy && move.z == obbDz) {
            return;
        }
        obbX = x;
        obbY = y;
        obbZ = z;
        obbDx = move.x;
        obbDy = move.y;
        obbDz = move.z;

        String modelId = this.getModelId();
        Vec3 dims = MissileModels.dimensions(modelId);
        Vec3 localCenter = MissileModels.center(modelId);

        double lenSq = move.lengthSqr();
        Quaterniond rot = new Quaterniond();
        if (lenSq > 1.0E-8) {
            double inv = 1.0 / Math.sqrt(lenSq);
            rot.rotationTo(0.0, 1.0, 0.0, move.x * inv, move.y * inv, move.z * inv);
        }
        // else: identity rotation (nose points straight up), which matches the ASCEND launch pose.

        // Box center = entity position + the rotated model-center offset (meshes sit base-at-origin).
        Vector3d worldCenter = new Vector3d(localCenter.x, localCenter.y, localCenter.z);
        rot.transform(worldCenter);
        worldCenter.add(x, y, z);

        this.obb.setCenter(worldCenter);
        this.obb.setExtents(new Vector3d(dims.x * 0.5, dims.y * 0.5, dims.z * 0.5));
        this.obb.updateRotation(rot);
    }

    /**
     * The vanilla AABB is fit tightly around the oriented model (the enclosing box of the OBB's corners),
     * so both frustum culling and the F3+B hitbox reflect the actual missile rather than a fixed cube.
     */
    @Override
    protected AABB makeBoundingBox() {
        // Called from the Entity constructor before our fields exist; fall back until the OBB is ready.
        if (this.obb == null) {
            return super.makeBoundingBox();
        }
        this.refreshObb();
        Vector3d ext = this.obb.extents();
        if (ext.x < 1.0E-3 && ext.y < 1.0E-3 && ext.z < 1.0E-3) {
            return super.makeBoundingBox();
        }
        // Enclosing AABB of the rotated box without allocating its 8 corners: the world half-size along
        // each axis is the sum of |axis component| * extent over the box's three (rotated) local axes.
        Vector3d c = this.obb.center();
        Vector3d[] ax = this.obb.getAxes();
        double hx = ext.x * Math.abs(ax[0].x) + ext.y * Math.abs(ax[1].x) + ext.z * Math.abs(ax[2].x);
        double hy = ext.x * Math.abs(ax[0].y) + ext.y * Math.abs(ax[1].y) + ext.z * Math.abs(ax[2].y);
        double hz = ext.x * Math.abs(ax[0].z) + ext.y * Math.abs(ax[1].z) + ext.z * Math.abs(ax[2].z);
        return new AABB(c.x - hx, c.y - hy, c.z - hz, c.x + hx, c.y + hy, c.z + hz);
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(MODEL_ID, MissileModels.DEFAULT);
        this.entityData.define(EXHAUST_COLOR, DEFAULT_EXHAUST_COLOR);
    }

    public String getModelId() {
        return this.entityData.get(MODEL_ID);
    }

    public void setModelId(String id) {
        this.entityData.set(MODEL_ID, MissileModels.exists(id) ? id : MissileModels.DEFAULT);
    }

    /**
     * @return the exhaust trail tint (hot RGB, 0xRRGGBB) the client-side plume fades from.
     */
    public int getExhaustColor() {
        return this.entityData.get(EXHAUST_COLOR);
    }

    public void setExhaustColor(int rgb) {
        this.entityData.set(EXHAUST_COLOR, rgb);
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

    public float getExplosionOffset() {
        return this.explosionOffset;
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

    public double getCruiseSpeed() {
        return this.cruiseSpeed;
    }

    public static double ascentSpeedFor(double cruiseSpeed) {
        return Math.max(MIN_ASCENT_SPEED, cruiseSpeed * ASCENT_SPEED_FACTOR);
    }

    public double getAscentSpeed() {
        return Double.isNaN(this.ascentSpeed) ? ascentSpeedFor(this.cruiseSpeed) : this.ascentSpeed;
    }

    public double getLaunchY() {
        return this.launchPos != null ? this.launchPos.y : this.getY();
    }

    /**
     * @return the desired terminal impact angle in degrees below horizontal (90 = straight down), or
     * {@link Double#NaN} for best fit (the attack stage's natural dive).
     */
    public double getAttackAngle() {
        return this.attackAngle;
    }

    /**
     * Cruise-altitude memory used by the cruise stage's altitude smoothing (NaN until the first cruise tick).
     */
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

    /**
     * Recompose the runtime flight profile from the current stage ids (call after any id changes).
     */
    private void rebuildFlightProfile() {
        this.flightProfile = FlightProfile.fromIds(this.ascentStageId, this.cruiseStageId, this.attackStageId);
    }

    /**
     * Rough remaining time-to-impact (ticks) from the current position, target and speed, accounting for the
     * climb, transit, terminal descent and any remaining loiter time. See {@link ArrivalEstimator}.
     */
    public int estimateArrivalTicks() {
        double cruiseAltitudeY = (this.cruiseMode == CruiseMode.HIGH_ALTITUDE)
                ? this.cruiseAltitude
                : this.getY() + this.terrainClearance;
        int loiterRemaining = FlightStageRegistry.keyOf(LoiterStage.INSTANCE).equals(this.cruiseStageId)
                ? Math.max(0, LoiterStage.LOITER_TICKS - this.loiterTicks)
                : 0;
        return ArrivalEstimator.estimateTicks(this.position(), this.getTarget(), this.cruiseSpeed,
                this.getAscentSpeed(), cruiseAltitudeY, loiterRemaining);
    }

    public ResourceLocation getDetonationId() {
        return this.detonationId;
    }

    public int getFragmentCount() {
        return this.fragmentCount;
    }

    public int getSplitDepth() {
        return this.splitDepth;
    }

    public long getSwarmId() {
        return this.swarmId;
    }

    public void setSwarmId(long swarmId) {
        this.swarmId = swarmId;
    }

    public boolean isCommander() {
        return this.commander;
    }

    public void setCommander(boolean commander) {
        this.commander = commander;
    }

    public UUID getControlId() {
        return this.controlId;
    }

    public void setControlId(UUID controlId) {
        this.controlId = controlId;
    }

    /**
     * Predictive deconfliction against nearby friendly missiles (same swarm or launcher). For each one whose
     * closest approach over the next {@link #AVOID_HORIZON} ticks would fall within {@link #AVOID_MIN_SEP}
     * blocks, adds a small offset away from where it will be — both missiles compute it, so the pair veers
     * apart (a "slight course offset": climb or a lateral shift), bounded by {@link #AVOID_STRENGTH}. Returns
     * {@link Vec3#ZERO} when nothing is actually on a collision course, so friendlies fly their normal path
     * until the moment they would ram.
     */
    private Vec3 avoidFriendlies() {
        AABB box = this.getBoundingBox().inflate(AVOID_RADIUS);
        List<MissileEntity> others = this.level().getEntitiesOfClass(MissileEntity.class, box,
                m -> m != this && m.isAlive() && this.isFriendly(m));
        if (others.isEmpty()) {
            return Vec3.ZERO;
        }
        Vec3 pos = this.position();
        Vec3 vel = this.getDeltaMovement();
        double ox = 0.0, oy = 0.0, oz = 0.0;
        for (MissileEntity other : others) {
            double rpx = other.getX() - pos.x, rpy = other.getY() - pos.y, rpz = other.getZ() - pos.z;
            Vec3 ov = other.getDeltaMovement();
            double rvx = ov.x - vel.x, rvy = ov.y - vel.y, rvz = ov.z - vel.z;
            double rv2 = rvx * rvx + rvy * rvy + rvz * rvz;
            // Time of closest approach (ticks); skip if already separating or too far in the future.
            double t = rv2 < 1.0e-6 ? 0.0 : -(rpx * rvx + rpy * rvy + rpz * rvz) / rv2;
            if (t < 0.0 || t > AVOID_HORIZON) {
                continue;
            }
            double sx = rpx + rvx * t, sy = rpy + rvy * t, sz = rpz + rvz * t;
            double miss = Math.sqrt(sx * sx + sy * sy + sz * sz);
            if (miss > AVOID_MIN_SEP) {
                continue; // they already clear each other — no course change
            }
            double urgency = (AVOID_MIN_SEP - miss) / AVOID_MIN_SEP; // 0..1, larger the tighter the miss
            if (miss > 1.0e-3) {
                // Veer away from the predicted closest-approach point (uses whichever axis separates them:
                // a climb if stacked, a sideways shift if abreast).
                double inv = urgency / miss;
                ox -= sx * inv;
                oy -= sy * inv;
                oz -= sz * inv;
            } else {
                // Dead-on (no defined "away"): split sideways, deterministically by id — a shift in x/z.
                double clen = Math.sqrt(rvx * rvx + rvz * rvz);
                double perpx = clen > 1.0e-4 ? -rvz / clen : 1.0;
                double perpz = clen > 1.0e-4 ? rvx / clen : 0.0;
                double dir = this.getId() < other.getId() ? 1.0 : -1.0;
                ox += perpx * dir * urgency;
                oz += perpz * dir * urgency;
            }
        }
        double len = Math.sqrt(ox * ox + oy * oy + oz * oz);
        if (len < 1.0e-6) {
            return Vec3.ZERO;
        }
        double scale = Math.min(AVOID_STRENGTH, len) / len;
        return new Vec3(ox * scale, oy * scale, oz * scale);
    }

    /**
     * Keep the turn-limited heading of {@code desired} but ramp the missile's <em>actual</em> speed toward
     * the desired speed at the {@link #acceleration} (spooling up) or {@link #deceleration} (braking) limit,
     * so speed changes are gradual instead of instantaneous.
     */
    private Vec3 applyThrust(Vec3 desired) {
        double targetSpeed = desired.length();
        Vec3 cur = this.getDeltaMovement();
        double curSpeed = cur.length();
        double rate = curSpeed <= targetSpeed ? this.effectiveAcceleration() : this.deceleration;
        double newSpeed = approach(curSpeed, targetSpeed, rate);
        Vec3 dir;
        if (targetSpeed > 1.0E-8) {
            dir = desired.scale(1.0 / targetSpeed);
        } else if (curSpeed > 1.0E-8) {
            dir = cur.scale(1.0 / curSpeed); // no commanded direction: coast on the current heading
        } else {
            dir = new Vec3(0.0, 1.0, 0.0);
        }
        return dir.scale(newSpeed);
    }

    private double effectiveAcceleration() {
        boolean fast = this.phase == Phase.ATTACK || this.boostTicks > 0;
        return fast ? Math.max(this.acceleration, DIVE_ACCELERATION) : this.acceleration;
    }

    /**
     * Formation guidance for a subordinate: steer to its wedge slot behind the commander while matching the
     * commander's velocity, so it holds station and throttles its speed to keep the slot (speeding up when it
     * falls behind, easing back once it's in place). The result is turn-rate + accel limited by the caller.
     */
    private Vec3 formationGuide(MissileEntity commander) {
        Vec3 slot = SwarmManager.formationSlot(commander, this);
        Vec3 cmdVel = commander.getDeltaMovement();
        Vec3 toSlot = slot.subtract(this.position());
        // Match the commander's velocity, plus a pull toward the slot (the "throttle" to open/close the gap).
        Vec3 desired = cmdVel.add(toSlot.scale(FORMATION_GAIN));
        double maxSpeed = Math.max(this.getCruiseSpeed(), cmdVel.length()) * FORMATION_MAX_OVERSPEED;
        double sp = desired.length();
        if (sp > maxSpeed && sp > 1.0E-6) {
            desired = desired.scale(maxSpeed / sp);
        }
        return desired;
    }

    /**
     * @return this commander's live formation subordinates if the whole swarm is eligible to offload as one
     * object (every member alive, still in formation, and clear of any listener), or null if not — in which
     * case the swarm keeps flying in-world. An empty list (commander with no surviving members) is eligible.
     */
    private List<MissileEntity> formationSubordinates(ServerLevel level) {
        List<MissileEntity> subs = new ArrayList<>();
        for (MissileEntity m : SwarmManager.members(level, this.swarmId)) {
            if (m == this) {
                continue;
            }
            if (!m.isAlive() || m.isRemoved() || m.brokeFormation || m.isCommander()) {
                return null;
            }
            if (SimMissileManager.nearAnyListener(level, m.position())) {
                return null;
            }
            subs.add(m);
        }
        return subs;
    }

    /**
     * A per-missile dispersed aim point around {@code base} (the mission target) for the saturation break:
     * a golden-angle spiral keyed on the entity id spreads the swarm evenly across the target area rather than
     * stacking every missile on one point.
     */
    private Vec3 saturationAim(Vec3 base) {
        int seed = this.getId();
        double angle = seed * 2.399963229; // golden angle (radians)
        double r = SATURATION_SPREAD * (1.0 + (seed % 3) * 0.5);
        return new Vec3(base.x + Math.cos(angle) * r, base.y, base.z + Math.sin(angle) * r);
    }

    /**
     * Unpowered flight (out of fuel): no thrust or guidance — the missile keeps its horizontal momentum
     * (lightly dragged) while gravity pulls it down toward a terminal speed, so it arcs over and falls with
     * its inertia preserved. Still sweeps for an impact so it detonates when it hits the ground.
     */
    private void ballisticFall(ServerLevel level, Vec3 currentPos) {
        // Keep the missile's own chunks loaded while it coasts down.
        this.chunkLoader.update(this, level, currentPos, this.getDeltaMovement(), true);

        Vec3 v = this.getDeltaMovement();
        double vy = Math.max(v.y - FUEL_OUT_GRAVITY, TERMINAL_FALL_SPEED);
        v = new Vec3(v.x * FALL_HORIZONTAL_DRAG, vy, v.z * FALL_HORIZONTAL_DRAG);
        this.setDeltaMovement(v);

        this.hasImpulse = true;
        this.move(net.minecraft.world.entity.MoverType.SELF, v);
        this.setBoundingBox(this.makeBoundingBox());

        if (this.isArmed()) {
            HitResult hitResult = this.sweepForImpact(currentPos, v);
            if (hitResult.getType() != HitResult.Type.MISS) {
                this.onMissileImpact(hitResult);
            }
        }
    }

    public FuelType getFuelType() {
        return this.fuelType;
    }

    public int getFuel() {
        return this.fuel;
    }

    public int getFuelCapacity() {
        return this.fuelCapacity;
    }

    // --- Interceptor guidance & kill (server-side) ---

    public double getAcceleration() {
        return this.acceleration;
    }

    public double getDeceleration() {
        return this.deceleration;
    }

    @Override
    public void remove(RemovalReason reason) {
        // Release forced chunks when the missile actually goes away (killed/discarded), but not on a
        // plain chunk unload — those tickets persist so the missile resumes after a reload.
        if (!this.level().isClientSide && reason.shouldDestroy() && this.level() instanceof ServerLevel sl) {
            this.chunkLoader.releaseAll(this, sl);
            if (this.interceptor) {
                MissileListenerRegistry.get(sl).deregister(this.getUUID());
            }
            // Commander lost (intercepted or impacted): hand the lead to the nearest surviving swarm member so
            // the rest obey a new commander.
            if (this.commander && this.swarmId != 0L) {
                SwarmManager.promoteSuccessor(sl, this.swarmId, this);
            }
        }
        super.remove(reason);
    }

    /**
     * Interceptor per-tick update: keep this interceptor registered as a listener (so nearby off-world
     * missiles rematerialize and a real target won't offload), resolve its current target, and write a lead
     * point via {@link #setTarget} so the shared flight code flies toward the predicted intercept. Self-
     * terminates (fizzle) once it exceeds its lifetime or has gone too long without any resolvable target.
     *
     * @return false if the interceptor removed itself this tick (the caller should stop ticking it).
     */
    private boolean updateInterceptTarget(ServerLevel level, Vec3 currentPos) {
        MissileListenerRegistry.get(level).register(this.getUUID(), this);

        if (this.tickCount >= MissileSimConfig.INTERCEPTOR_LIFETIME_TICKS) {
            this.detonate(currentPos, true);
            return false;
        }

        MissileEntity target = this.resolveInterceptTarget(level, currentPos);
        if (target == null) {
            this.currentTargetId = null;
            if (++this.noTargetTicks > MissileSimConfig.INTERCEPTOR_LOST_TARGET_TICKS) {
                this.detonate(currentPos, true);
                return false;
            }
            // Coast: keep aiming along the current heading (or straight up if barely moving) so guidance
            // stays stable while we wait for a target to appear/reappear.
            Vec3 vel = this.getDeltaMovement();
            Vec3 dir = vel.lengthSqr() > 1.0E-6 ? vel.normalize() : new Vec3(0.0, 1.0, 0.0);
            this.setTarget(currentPos.add(dir.scale(32.0)));
            return true;
        }
        this.currentTargetId = target.getUUID();
        this.noTargetTicks = 0;
        this.setTarget(this.leadPoint(currentPos, target));
        return true;
    }

    /**
     * @return the missile this interceptor should home on: in LOCK mode the exact {@link #lockTargetId} (any
     * side — an explicit override), in NEAREST mode the closest live, non-friendly, non-interceptor missile
     * within {@link MissileSimConfig#INTERCEPTOR_ACQUIRE_RANGE}. Null when none is resolvable.
     */
    private MissileEntity resolveInterceptTarget(ServerLevel level, Vec3 currentPos) {
        if (this.interceptMode == InterceptMode.LOCK) {
            if (this.lockTargetId != null && level.getEntity(this.lockTargetId) instanceof MissileEntity m
                    && m.isAlive() && !m.detonated) {
                return m;
            }
            return null;
        }
        double r = MissileSimConfig.INTERCEPTOR_ACQUIRE_RANGE;
        AABB box = this.getBoundingBox().inflate(r);
        List<MissileEntity> nearby = level.getEntitiesOfClass(MissileEntity.class, box, MissileEntity::isAlive);
        // Targets already covered by another committed interceptor, so a salvo spreads across threats instead
        // of dogpiling one missile. If every hostile is covered we still take the nearest as a fallback.
        Set<UUID> claimed = claimedTargets(nearby, this.getId());
        MissileEntity best = null;
        MissileEntity fallback = null;
        double bestSq = r * r;
        double fallbackSq = r * r;
        for (MissileEntity m : nearby) {
            if (m == this || m.detonated || m.interceptor || this.isFriendly(m)) {
                continue;
            }
            double dsq = m.position().distanceToSqr(currentPos);
            // Stealth missiles are only detectable at short range and with a per-scan chance (see detectableAt).
            if (!m.detectableAt(dsq, level.random)) {
                continue;
            }
            if (dsq <= fallbackSq) {
                fallbackSq = dsq;
                fallback = m;
            }
            if (!claimed.contains(m.getUUID()) && dsq <= bestSq) {
                bestSq = dsq;
                best = m;
            }
        }
        return best != null ? best : fallback;
    }

    /**
     * Predicted intercept point: where to aim so a run at cruise speed meets the target's straight-line
     * motion. Solves {@code |D + Vt·t| = s·t} for the smallest positive {@code t} (D = target − interceptor,
     * Vt = target velocity, s = interceptor speed); falls back to the target's current position when the
     * target outruns the interceptor (no positive solution). Re-solved every tick, so it self-corrects.
     */
    private Vec3 leadPoint(Vec3 currentPos, MissileEntity target) {
        Vec3 tPos = target.position();
        Vec3 vt = target.getDeltaMovement();
        double s = this.getCruiseSpeed();
        Vec3 d = tPos.subtract(currentPos);
        double a = vt.lengthSqr() - s * s;
        double b = 2.0 * d.dot(vt);
        double c = d.lengthSqr();
        double t;
        if (Math.abs(a) < 1.0E-6) {
            t = Math.abs(b) < 1.0E-6 ? -1.0 : -c / b; // linear b·t + c = 0
        } else {
            double disc = b * b - 4.0 * a * c;
            if (disc < 0.0) {
                t = -1.0;
            } else {
                double sq = Math.sqrt(disc);
                t = smallestPositive((-b - sq) / (2.0 * a), (-b + sq) / (2.0 * a));
            }
        }
        if (t > 0.0 && !Double.isNaN(t) && !Double.isInfinite(t)) {
            // A timed intercept exists: we can reach this lead point just as the target arrives.
            this.crossingShot = false;
            return tPos.add(vt.scale(t));
        }
        // Uncatchable — the target outruns us, so a stern chase can never connect. Aim at the nearest point on
        // its forward flight path and try to cross it instead (a much lower-odds shot; see tryIntercept).
        this.crossingShot = true;
        double vlen2 = vt.lengthSqr();
        if (vlen2 < 1.0E-8) {
            return tPos; // (near-)stationary target: go straight for it
        }
        double u = Math.max(0.0, -d.dot(vt) / vlen2); // foot of the perpendicular onto the forward path
        return tPos.add(vt.scale(u));
    }

    /**
     * Closest-approach kill test against the current target over this tick's motion. If the interceptor and
     * target pass within {@link MissileSimConfig#INTERCEPTOR_KILL_RADIUS}, roll {@link #interceptChance} once:
     * on success the target runs its (neutralised) intercept effect; either way the interceptor is spent
     * (one-shot). Robust to extremely fast missiles because it uses the two segments' relative-velocity
     * closest approach, not an overlap snapshot.
     *
     * @return true if the interceptor resolved (killed a target or was spent) and should stop ticking.
     */
    private boolean tryIntercept(Vec3 currentPos) {
        if (this.currentTargetId == null || !(this.level() instanceof ServerLevel level)) {
            return false;
        }
        if (!(level.getEntity(this.currentTargetId) instanceof MissileEntity tgt)
                || !tgt.isAlive() || tgt.detonated || tgt.interceptor) {
            return false;
        }
        Vec3 iV = this.getDeltaMovement();
        Vec3 tEnd = tgt.position();
        Vec3 tV = tgt.getDeltaMovement();
        Vec3 tStart = tEnd.subtract(tV);
        // Relative motion of the target vs. the interceptor over this tick's two segments.
        Vec3 rp = tStart.subtract(currentPos);
        Vec3 rv = tV.subtract(iV);
        double rv2 = rv.lengthSqr();
        double tStar = rv2 < 1.0E-9 ? 0.0 : Mth.clamp(-rp.dot(rv) / rv2, 0.0, 1.0);
        double minSep = rp.add(rv.scale(tStar)).length();
        if (minSep > MissileSimConfig.INTERCEPTOR_KILL_RADIUS) {
            return false;
        }
        Vec3 point = currentPos.add(iV.scale(tStar));
        // A crossing shot (we couldn't run the target down, only cut across its path) is much less reliable.
        float chance = this.crossingShot
                ? this.interceptChance * MissileSimConfig.INTERCEPTOR_CROSSING_HIT_FACTOR
                : this.interceptChance;
        boolean kill = level.random.nextFloat() < chance;
        // A higher-tier target can boost clear at the cost of fuel — but only if it can out-run THIS
        // interceptor (its speed decides the odds, see evadeBoost), so a fast interceptor still connects.
        if (kill && tgt.evadeBoost(this.getCruiseSpeed(), this.position())) {
            kill = false;
        }
        spawnInterceptBurst(level, point, kill); // readable flak burst: big + boom on a kill, small on a miss
        if (MissileSimConfig.INTERCEPTOR_CHIP_MODE) {
            // Chip mode: damage the shared health pool (combines with CIWS; tougher missiles survive more
            // hits). A hit deals heavy damage, a miss a graze; damageMissile downs it when the pool empties.
            tgt.damageMissile(kill ? MissileSimConfig.INTERCEPTOR_HIT_DAMAGE : MissileSimConfig.INTERCEPTOR_GRAZE_DAMAGE);
        } else if (kill) {
            tgt.detonate(point, true); // binary mode: a successful roll neutralises the target outright
        }
        this.detonate(point, true); // spent whether it hits or misses (one-shot)
        return true;
    }

    // --- IMissileListener (interceptors only; a normal missile is never registered) ---

    @Override
    public Vec3 listenerCenter() {
        return this.position();
    }

    @Override
    public double listenerRange() {
        return MissileSimConfig.INTERCEPTOR_LISTENER_RANGE;
    }

    @Override
    public boolean listenerValid() {
        return this.interceptor && !this.isRemoved() && !this.detonated
                && this.level() instanceof ServerLevel sl && sl.isLoaded(this.blockPosition());
    }

    public boolean isInterceptor() {
        return this.interceptor;
    }

    /**
     * @return true if this missile is stealth (invisible to automatic detection; engageable only by a manual
     * UUID lock).
     */
    public boolean isStealth() {
        return this.stealth;
    }

    public float getEvasion() {
        return this.evasion;
    }

    /**
     * @return true if evasion boosts also jink the missile off its course (see {@link #evasiveManeuver}).
     */
    public boolean isEvasiveManeuver() {
        return this.evasiveManeuver;
    }

    /**
     * @return this missile's evasion right now (0..1): its base {@link #evasion}, amplified by
     * {@link MissileSimConfig#DIVE_EVASION_MULTIPLIER} while in the terminal ATTACK dive (a maneuvering
     * warhead is hardest to intercept on its way down). The interceptor's kill chance is scaled by
     * {@code (1 - this)}.
     */
    public float effectiveEvasion() {
        if (this.evasion <= 0.0f) {
            return 0.0f;
        }
        double e = this.phase == Phase.ATTACK ? this.evasion * MissileSimConfig.DIVE_EVASION_MULTIPLIER : this.evasion;
        return (float) Math.min(1.0, e);
    }

    /**
     * @return true if the missile boosted clear (the incoming hit becomes a miss).
     */
    private boolean evadeBoost(double interceptorSpeed, Vec3 interceptorPos) {
        if (this.evasion <= 0.0f || this.fuel < BOOST_FUEL_COST || !(this.level() instanceof ServerLevel sl)) {
            return false; // no evasion capability
        }
        this.fuel -= BOOST_FUEL_COST;
        this.boostTicks = BOOST_DURATION;
        this.boostManeuver = this.evasiveManeuver ? this.computeJink(sl.random, interceptorPos) : null;
        double boostedSpeed = Math.max(this.getDeltaMovement().length(), this.getCruiseSpeed() * BOOST_SPEED_MULT);
        double speedFactor = Math.min(1.0, boostedSpeed / Math.max(1.0E-3, interceptorSpeed));
        float escapeChance = (float) (this.effectiveEvasion() * speedFactor);
        return sl.random.nextFloat() < escapeChance;
    }

    /**
     * A break direction for a maneuvering dodge
     */
    private Vec3 computeJink(net.minecraft.util.RandomSource rand, Vec3 interceptorPos) {
        Vec3 v = this.getDeltaMovement();
        double vlen = v.length();
        Vec3 vhat = vlen > 1.0E-6 ? v.scale(1.0 / vlen) : new Vec3(0.0, 1.0, 0.0);
        // Orthonormal basis spanning the plane perpendicular to the heading.
        Vec3 ref = Math.abs(vhat.y) < 0.95 ? new Vec3(0.0, 1.0, 0.0) : new Vec3(1.0, 0.0, 0.0);
        Vec3 right = vhat.cross(ref).normalize();
        Vec3 up = right.cross(vhat).normalize();
        // Away-from-interceptor direction, projected into that plane, gives the base break angle.
        Vec3 away = this.position().subtract(interceptorPos);
        double ar = away.dot(right);
        double au = away.dot(up);
        double angle = (ar * ar + au * au) > 1.0E-8 ? Math.atan2(au, ar) : rand.nextDouble() * Math.PI * 2.0;
        angle += rand.nextGaussian() * 0.6; // ~+/-35 degrees of jitter so the jink isn't a fixed plane
        return right.scale(Math.cos(angle)).add(up.scale(Math.sin(angle))).normalize();
    }

    /**
     * @return whether an automatic detector may see this missile this scan, at squared distance {@code distSq}.
     * A normal missile is always visible within the detector's own range; a stealth missile is visible only
     * within the short {@link MissileSimConfig#STEALTH_DETECT_RANGE} and then only with
     * {@link MissileSimConfig#STEALTH_DETECT_CHANCE} probability per scan — hard to engage, not invisible.
     * Manual UUID locking bypasses this entirely.
     */
    public boolean detectableAt(double distSq, net.minecraft.util.RandomSource rand) {
        if (!this.stealth) {
            return true;
        }
        double r = MissileSimConfig.STEALTH_DETECT_RANGE;
        return distSq <= r * r && rand.nextFloat() < MissileSimConfig.STEALTH_DETECT_CHANCE;
    }

    public float getInterceptChance() {
        return this.interceptChance;
    }

    /**
     * Switch this (already-built) interceptor to LOCK mode on a specific target missile's UUID. Used by the
     * launch item / command after building the interceptor.
     */
    public void setInterceptLock(UUID targetId) {
        this.interceptMode = InterceptMode.LOCK;
        this.lockTargetId = targetId;
    }

    /**
     * Assign a specific entity for this (non-interceptor) missile/drone to strike. It re-aims onto the entity
     * each tick while the entity is alive; pair with the {@code "loiter"} cruise stage for a loitering munition
     * that orbits the area and pounces when the target is present.
     */
    public void setDesignatedTarget(UUID entityId) {
        this.designatedTargetId = entityId;
    }

    public UUID getDesignatedTargetId() {
        return this.designatedTargetId;
    }

    /**
     * @return true if a designated strike target has been assigned (whether or not it is currently present).
     */
    public boolean hasDesignatedTarget() {
        return this.designatedTargetId != null;
    }

    /**
     * @return true if the designated target is currently resolvable and alive (server-side).
     */
    public boolean hasLiveDesignatedTarget() {
        if (this.designatedTargetId == null || !(this.level() instanceof ServerLevel sl)) {
            return false;
        }
        Entity e = sl.getEntity(this.designatedTargetId);
        return e != null && e.isAlive();
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        Vec3 target = getTarget();
        tag.putDouble("TargetX", target.x);
        tag.putDouble("TargetY", target.y);
        tag.putDouble("TargetZ", target.z);
        tag.putString("Phase", this.phase.name());
        tag.putString("CruiseMode", this.cruiseMode.name());
        tag.putDouble("CruiseAltitude", this.cruiseAltitude);
        tag.putDouble("TerrainClearance", this.terrainClearance);
        tag.putFloat("ExplosionOffset", this.explosionOffset);
        tag.putDouble("MaxTurnRate", this.maxTurnRate);
        tag.putDouble("CruiseSpeed", this.cruiseSpeed);
        if (!Double.isNaN(this.ascentSpeed)) {
            tag.putDouble("AscentSpeed", this.ascentSpeed);
        }
        if (!Double.isNaN(this.attackAngle)) {
            tag.putDouble("AttackAngle", this.attackAngle);
        }
        tag.putString("ModelId", this.getModelId());
        tag.putInt("ExhaustColor", this.getExhaustColor());
        tag.putInt("CruiseTicks", this.cruiseTicks);
        tag.putString("DetonationId", this.detonationId.toString());
        tag.putString("AscentStage", this.ascentStageId.toString());
        tag.putString("CruiseStage", this.cruiseStageId.toString());
        tag.putString("AttackStage", this.attackStageId.toString());
        tag.putInt("LoiterTicks", this.loiterTicks);
        tag.putInt("FragmentCount", this.fragmentCount);
        tag.putInt("SplitDepth", this.splitDepth);
        tag.putLong("SwarmId", this.swarmId);
        tag.putBoolean("Commander", this.commander);
        tag.putBoolean("BrokeFormation", this.brokeFormation);
        if (this.controlId != null) {
            tag.putUUID("ControlId", this.controlId);
        }
        if (this.teamId != null) {
            tag.putUUID("TeamId", this.teamId);
        }
        tag.putBoolean("Interceptor", this.interceptor);
        tag.putString("InterceptMode", this.interceptMode.name());
        if (this.lockTargetId != null) {
            tag.putUUID("LockTargetId", this.lockTargetId);
        }
        tag.putFloat("InterceptChance", this.interceptChance);
        tag.putInt("NoTargetTicks", this.noTargetTicks);
        if (this.designatedTargetId != null) {
            tag.putUUID("DesignatedTarget", this.designatedTargetId);
        }
        tag.putBoolean("Stealth", this.stealth);
        tag.putFloat("Evasion", this.evasion);
        tag.putBoolean("EvasiveManeuver", this.evasiveManeuver);
        tag.putString("FuelType", this.fuelType.name());
        tag.putInt("Fuel", this.fuel);
        tag.putInt("FuelCapacity", this.fuelCapacity);
        tag.putDouble("Acceleration", this.acceleration);
        tag.putDouble("Deceleration", this.deceleration);
        tag.putFloat("Health", this.health);
        tag.putBoolean("Armed", this.armed);
        if (this.launchPos != null) {
            tag.putDouble("LaunchX", this.launchPos.x);
            tag.putDouble("LaunchY", this.launchPos.y);
            tag.putDouble("LaunchZ", this.launchPos.z);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);

        if (tag.contains("TargetX")) {
            double x = tag.getDouble("TargetX");
            double y = tag.getDouble("TargetY");
            double z = tag.getDouble("TargetZ");
            this.setTarget(new Vec3(x, y, z));
        }

        if (tag.contains("Phase")) {
            try {
                this.phase = Phase.valueOf(tag.getString("Phase"));
            } catch (IllegalArgumentException nignored) {
            }
        }

        if (tag.contains("CruiseMode")) {
            try {
                this.cruiseMode = CruiseMode.valueOf(tag.getString("CruiseMode"));
            } catch (IllegalArgumentException nignored) {
            }
        }

        if (tag.contains("CruiseAltitude")) {
            this.cruiseAltitude = tag.getDouble("CruiseAltitude");
        }

        if (tag.contains("TerrainClearance")) {
            this.terrainClearance = tag.getDouble("TerrainClearance");
        }

        if (tag.contains("ExplosionOffset")) {
            this.explosionOffset = tag.getFloat("ExplosionOffset");
        }

        if (tag.contains("MaxTurnRate")) {
            this.maxTurnRate = tag.getDouble("MaxTurnRate");
        }

        if (tag.contains("CruiseSpeed")) {
            this.cruiseSpeed = tag.getDouble("CruiseSpeed");
        }

        if (tag.contains("AscentSpeed")) {
            this.ascentSpeed = tag.getDouble("AscentSpeed");
        }

        if (tag.contains("AttackAngle")) {
            this.attackAngle = tag.getDouble("AttackAngle");
        }

        if (tag.contains("ModelId")) {
            this.setModelId(tag.getString("ModelId"));
        }

        if (tag.contains("ExhaustColor")) {
            this.setExhaustColor(tag.getInt("ExhaustColor"));
        }

        if (tag.contains("CruiseTicks")) {
            this.cruiseTicks = tag.getInt("CruiseTicks");
        }

        if (tag.contains("DetonationId")) {
            this.detonationId = WarheadRegistry.parse(tag.getString("DetonationId"));
            this.detonation = WarheadRegistry.get(this.detonationId);
        }

        if (tag.contains("AscentStage")) {
            this.ascentStageId = FlightStageRegistry.parse(Phase.ASCEND, tag.getString("AscentStage"));
        }
        if (tag.contains("CruiseStage")) {
            this.cruiseStageId = FlightStageRegistry.parse(Phase.CRUISE, tag.getString("CruiseStage"));
        }
        if (tag.contains("AttackStage")) {
            this.attackStageId = FlightStageRegistry.parse(Phase.ATTACK, tag.getString("AttackStage"));
        }
        this.rebuildFlightProfile();
        this.loiterTicks = tag.getInt("LoiterTicks");

        if (tag.contains("FragmentCount")) {
            this.fragmentCount = tag.getInt("FragmentCount");
        }

        if (tag.contains("SplitDepth")) {
            this.splitDepth = tag.getInt("SplitDepth");
        }
        this.swarmId = tag.getLong("SwarmId");
        this.commander = tag.getBoolean("Commander");
        this.brokeFormation = tag.getBoolean("BrokeFormation");
        if (tag.hasUUID("ControlId")) {
            this.controlId = tag.getUUID("ControlId");
        }
        if (tag.hasUUID("TeamId")) {
            this.teamId = tag.getUUID("TeamId");
        }

        this.interceptor = tag.getBoolean("Interceptor");
        if (tag.contains("InterceptMode")) {
            try {
                this.interceptMode = InterceptMode.valueOf(tag.getString("InterceptMode"));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (tag.hasUUID("LockTargetId")) {
            this.lockTargetId = tag.getUUID("LockTargetId");
        }
        if (tag.contains("InterceptChance")) {
            this.interceptChance = tag.getFloat("InterceptChance");
        }
        this.noTargetTicks = tag.getInt("NoTargetTicks");
        if (tag.hasUUID("DesignatedTarget")) {
            this.designatedTargetId = tag.getUUID("DesignatedTarget");
        }
        this.stealth = tag.getBoolean("Stealth");
        this.evasiveManeuver = tag.getBoolean("EvasiveManeuver");
        if (tag.contains("Evasion")) {
            this.evasion = tag.getFloat("Evasion");
        }

        if (tag.contains("FuelType")) {
            try {
                this.fuelType = FuelType.valueOf(tag.getString("FuelType"));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (tag.contains("Fuel")) {
            this.fuel = tag.getInt("Fuel");
        }
        if (tag.contains("FuelCapacity")) {
            this.fuelCapacity = tag.getInt("FuelCapacity");
        }
        if (tag.contains("Acceleration")) {
            this.acceleration = tag.getDouble("Acceleration");
        }
        if (tag.contains("Deceleration")) {
            this.deceleration = tag.getDouble("Deceleration");
        }

        this.armed = tag.getBoolean("Armed");
        if (tag.contains("LaunchX")) {
            this.launchPos = new Vec3(tag.getDouble("LaunchX"), tag.getDouble("LaunchY"), tag.getDouble("LaunchZ"));
        }

        if (tag.contains("Health")) {
            this.health = tag.getFloat("Health");
        }
    }

    private void onMissileImpact(HitResult hitResult) {
        // Missile-vs-missile: this was an interception, not a strike on the target. Neutralise both missiles
        // with the (cheap) intercept effect rather than running two full open-air warhead blasts in one tick.
        // A hit on any other entity (or a block) is a real impact and detonates normally.
        if (hitResult instanceof EntityHitResult ehr && ehr.getEntity() instanceof MissileEntity other) {
            Vec3 pos = hitResult.getLocation();
            this.detonate(pos, true);
            other.detonate(pos, true); // guarded by other.detonated, so a mutual hit only fires each once
            return;
        }
        this.detonate(hitResult.getLocation(), false);
    }

    /**
     * Fires the configured warhead at the given position and removes the missile (a full target detonation).
     */
    private void detonate(Vec3 pos) {
        this.detonate(pos, false);
    }

    /**
     * Removes the missile, running either the full warhead {@link WarheadRegistry.Detonation} or — when
     * {@code intercepted} — the (typically neutralised) {@link WarheadRegistry#getIntercept intercept effect}
     * used when the missile is shot down / rammed mid-air instead of reaching its target.
     */
    private void detonate(Vec3 pos, boolean intercepted) {
        if (this.detonated) {
            return;
        }
        this.detonated = true; // set before the blast: it can hurt() this missile before discard() runs
        if (this.level() instanceof ServerLevel sl) {
            this.chunkLoader.releaseAll(this, sl);
        }
        if (intercepted) {
            WarheadRegistry.getIntercept(this.detonationId).detonate(this, pos);
        } else {
            this.detonation.detonate(this, pos);
        }
        this.discard();
    }

    public float getHealth() {
        return this.health;
    }

    public void damageMissile(float amount) {
        if (this.level().isClientSide || this.isRemoved() || this.detonated || amount <= 0.0f) {
            return;
        }
        // While unarmed, absorb damage without cooking off — a stray hit shouldn't blow it up on the launcher.
        if (!this.isArmed()) {
            return;
        }
        this.health -= amount;
        if (this.health <= 0.0f) {
            // Shot down (CIWS / interceptor fire) counts as an interception: run the neutralised effect so a
            // downed missile fizzles instead of dropping a full warhead blast (e.g. over friendly ground).
            this.detonate(this.position(), true);
        }
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        // TODO: don't let stray projectiles (e.g. arrows) destroy the missile.
        if (this.level().isClientSide || this.isRemoved()) {
            return false;
        }
        this.damageMissile(amount);
        return true;
    }

    public enum Phase {
        ASCEND,  // boost straight up until clear of surrounding terrain
        CRUISE,  // fly toward the target while holding a terrain-safe altitude
        ATTACK   // steep terminal dive onto the target
    }

    public enum CruiseMode {
        TERRAIN_FOLLOW,
        HIGH_ALTITUDE
    }

    /**
     * How an interceptor picks its target: re-acquire the closest hostile each tick, or home on one UUID.
     */
    public enum InterceptMode {
        NEAREST,
        LOCK
    }

    /**
     * Propellant kind: SOLID is a preloaded charge; LIQUID is kerosene (see {@code WFFluids}). Burn behaviour
     * is identical (ticks of thrust) — the type is carried for flavour and future tank/bucket refuelling.
     */
    public enum FuelType {
        SOLID,
        LIQUID
    }

    /**
     * Example use:
     * <pre>{@code
     * MissileEntity m = MissileEntity.builder(ModEntities.STEALTH_MISSILE.get(), level)
     *         .target(pos)
     *         .highAltitude(250.0)   // or .terrainFollow(24.0)
     *         .explosionOffset(30f)  // airburst 30 blocks above the target
     *         .build();
     * level.addFreshEntity(m);
     * }</pre>
     */
    public static final class Builder {
        private final EntityType<? extends Projectile> type;
        private final Level level;

        private Vec3 target;
        private CruiseMode cruiseMode = CruiseMode.TERRAIN_FOLLOW;
        private double cruiseAltitude = 200.0;
        private double terrainClearance = 24.0;
        private float explosionOffset = 0.0f;
        private ResourceLocation detonationId = WarheadRegistry.defaultId();
        private ResourceLocation ascentStageId = null; // null = keep the phase's default stage
        private ResourceLocation cruiseStageId = null;
        private ResourceLocation attackStageId = null;
        private int fragmentCount = DEFAULT_FRAGMENT_COUNT;
        private double cruiseSpeed = CRUISE_SPEED;
        private Double ascentSpeed = null;
        private Double attackAngle = null;
        private Double maxTurnRate = null; // null = keep the model-size default
        private String modelId = MissileModels.DEFAULT;
        private int exhaustColor = DEFAULT_EXHAUST_COLOR;
        private boolean startInCruise = false;
        private boolean startInAttack = false;
        private boolean startArmed = false;
        private float health = DEFAULT_HEALTH;
        private Integer splitDepth = null; // null = default by warhead (recursive_frag gets a depth, others 0)
        private long swarmId = 0L;
        private boolean commander = false;
        private UUID controlId = null;
        private UUID teamId = null;
        private boolean interceptor = false;
        private InterceptMode interceptMode = InterceptMode.NEAREST;
        private UUID lockTargetId = null;
        private Float interceptChance = null; // null = MissileSimConfig.DEFAULT_INTERCEPT_CHANCE
        private FuelType fuelType = FuelType.SOLID;
        private int fuelTicks = DEFAULT_FUEL_TICKS;
        private double acceleration = DEFAULT_ACCELERATION;
        private double deceleration = DEFAULT_DECELERATION;
        private UUID designatedTargetId = null;
        private boolean stealth = false;
        private float evasion = 0.0f;
        private boolean evasiveManeuver = false;

        private Builder(EntityType<? extends Projectile> type, Level level) {
            this.type = type;
            this.level = level;
        }

        public Builder target(Vec3 target) {
            this.target = target;
            return this;
        }

        /**
         * Fly at a fixed altitude, ignoring terrain.
         */
        public Builder highAltitude(double cruiseAltitude) {
            this.cruiseMode = CruiseMode.HIGH_ALTITUDE;
            this.cruiseAltitude = cruiseAltitude;
            return this;
        }

        /**
         * Hug the ground, holding {@code terrainClearance} blocks above nearby terrain.
         */
        public Builder terrainFollow(double terrainClearance) {
            this.cruiseMode = CruiseMode.TERRAIN_FOLLOW;
            this.terrainClearance = terrainClearance;
            return this;
        }

        /**
         * Airburst {@code offset} blocks above the target; 0 (default) is a contact detonation.
         */
        public Builder explosionOffset(float offset) {
            this.explosionOffset = offset;
            return this;
        }

        /**
         * Pick the warhead by its registered id (see {@link #registerWarhead}); defaults to
         * {@code "standard"}. Using an id (rather than a raw lambda) lets the warhead survive save/load.
         */
        public Builder detonation(ResourceLocation detonationId) {
            this.detonationId = detonationId;
            return this;
        }

        /**
         * Pick the ascent-phase stage by its registered id (see {@link FlightStageRegistry}).
         */
        public Builder ascentStage(ResourceLocation id) {
            this.ascentStageId = id;
            return this;
        }

        /**
         * Pick the cruise-phase stage (e.g. {@code "cruise"} or {@code "loiter"}) by its registered id.
         */
        public Builder cruiseStage(ResourceLocation id) {
            this.cruiseStageId = id;
            return this;
        }

        /**
         * Pick the attack-phase stage (e.g. {@code "attack"} or {@code "dive"}) by its registered id.
         */
        public Builder attackStage(ResourceLocation id) {
            this.attackStageId = id;
            return this;
        }

        /**
         * Number of bomblets the {@code "fragmentation"} warhead scatters; defaults to
         * {@link #DEFAULT_FRAGMENT_COUNT}. No effect for other warheads.
         */
        public Builder fragmentCount(int fragmentCount) {
            this.fragmentCount = fragmentCount;
            return this;
        }

        /**
         * Override the max heading change per tick (radians); default scales from the model's length.
         */
        public Builder turnRate(double radiansPerTick) {
            this.maxTurnRate = radiansPerTick;
            return this;
        }

        /**
         * Set the horizontal cruise speed (blocks/tick); default {@link #CRUISE_SPEED}. The off-world
         * simulation advances at this same rate, so travel time matches whether flown or simulated.
         */
        public Builder cruiseSpeed(double blocksPerTick) {
            this.cruiseSpeed = blocksPerTick;
            return this;
        }

        public Builder ascentSpeed(double blocksPerTick) {
            this.ascentSpeed = blocksPerTick;
            return this;
        }

        /**
         * Desired terminal impact angle in degrees below horizontal (90 = straight down / top-attack). Leave
         * unset for best fit (the attack stage's natural dive). Clamped to a workable range.
         */
        public Builder attackAngle(double degrees) {
            this.attackAngle = Math.max(MIN_ATTACK_ANGLE, Math.min(MAX_ATTACK_ANGLE, degrees));
            return this;
        }

        /**
         * Pick which missile model/skin to render and fly as (see {@link MissileModels}).
         */
        public Builder model(String modelId) {
            this.modelId = modelId;
            return this;
        }

        /**
         * Tint of the exhaust trail: the hot RGB (0xRRGGBB) the client-side plume fades from as it cools and
         * dissipates. Default {@link #DEFAULT_EXHAUST_COLOR} (an orange rocket flame).
         */
        public Builder exhaustColor(int rgb) {
            this.exhaustColor = rgb;
            return this;
        }

        /**
         * Start the missile already in the CRUISE phase (used when respawning a simulated missile
         * that had long since finished its ascent).
         */
        public Builder startInCruise() {
            this.startInCruise = true;
            return this;
        }

        /**
         * Spawn already in the terminal ATTACK dive. Used by {@link RecursiveFrag} so split missilelets go
         * straight into their attack run ("instant attack mode") instead of ascending/cruising first.
         */
        public Builder startInAttack() {
            this.startInAttack = true;
            return this;
        }

        /**
         * Number of recursive split generations (see {@link RecursiveFrag}); leave unset to take the
         * warhead default ({@link RecursiveFrag#DEFAULT_DEPTH} for {@code recursive_frag}, else none).
         */
        public Builder splitDepth(int splitDepth) {
            this.splitDepth = splitDepth;
            return this;
        }

        /**
         * Group this missile into a fragmentation family (a non-zero id shared by all its descendants) so
         * the family's members won't collide with each other (see {@link #canHitEntity}).
         */
        public Builder swarmId(long swarmId) {
            this.swarmId = swarmId;
            return this;
        }

        /**
         * Mark this missile as the swarm commander: it flies the mission and the rest of its {@link #swarmId}
         * hold formation on it (see {@link SwarmManager}). Exactly one member of a swarm should be commander.
         */
        public Builder commander(boolean commander) {
            this.commander = commander;
            return this;
        }

        /**
         * Stamp this missile with a launcher/control id (see {@link MissileDispenserBlockEntity}) so missiles
         * fired from the same launcher treat each other as friendly and never collide.
         */
        public Builder controlId(UUID controlId) {
            this.controlId = controlId;
            return this;
        }

        /**
         * Stamp this missile with a WarForge faction/team id (see {@link WarforgeCompat}); missiles of the
         * same / allied / truced faction are friendly. Usually the launching player's faction, or the faction
         * whose land a launcher/battery sits on.
         */
        public Builder teamId(UUID teamId) {
            this.teamId = teamId;
            return this;
        }

        /**
         * Spawn with the warhead already armed (used when respawning a simulated missile that has long since
         * flown clear of its launcher). A freshly launched missile leaves this off and arms by distance.
         */
        public Builder startArmed() {
            this.startArmed = true;
            return this;
        }

        /**
         * Set the interception damage pool (see {@link #DEFAULT_HEALTH}); higher = harder to shoot down.
         */
        public Builder health(float health) {
            this.health = health;
            return this;
        }

        /**
         * Make this an interceptor: it homes on another missile and resolves a kill by a random roll (see
         * {@link #tryIntercept}) instead of a warhead. Forces the flight profile to all-{@code "intercept"}
         * and launches already armed and cruising. Combine with {@link #lockTarget}/{@link #interceptMode}.
         */
        public Builder interceptor(boolean interceptor) {
            this.interceptor = interceptor;
            return this;
        }

        /**
         * Pick the interceptor targeting mode: {@code NEAREST} (closest hostile, re-acquired each tick) or
         * {@code LOCK} (a specific UUID, set via {@link #lockTarget}).
         */
        public Builder interceptMode(InterceptMode mode) {
            this.interceptMode = mode;
            return this;
        }

        /**
         * Home on one specific target missile's UUID (also selects {@code LOCK} mode).
         */
        public Builder lockTarget(UUID targetId) {
            this.interceptMode = InterceptMode.LOCK;
            this.lockTargetId = targetId;
            return this;
        }

        /**
         * Per-interceptor kill probability rolled on closest approach; default
         * {@link MissileSimConfig#DEFAULT_INTERCEPT_CHANCE}.
         */
        public Builder interceptChance(float chance) {
            this.interceptChance = chance;
            return this;
        }

        /**
         * Load the tank: {@code type} of propellant and {@code ticks} of powered flight before it runs dry
         * (after which the missile falls ballistically). Default {@link #DEFAULT_FUEL_TICKS} of SOLID fuel.
         */
        public Builder fuel(FuelType type, int ticks) {
            this.fuelType = type;
            this.fuelTicks = ticks;
            return this;
        }

        /**
         * Max change in actual speed per tick while spooling up toward the guidance speed (blocks/tick^2).
         */
        public Builder acceleration(double acceleration) {
            this.acceleration = acceleration;
            return this;
        }

        /**
         * Max change in actual speed per tick while braking down toward the guidance speed (blocks/tick^2).
         */
        public Builder deceleration(double deceleration) {
            this.deceleration = deceleration;
            return this;
        }

        /**
         * Assign a designated strike target entity (see {@link MissileEntity#setDesignatedTarget}).
         */
        public Builder designatedTarget(UUID entityId) {
            this.designatedTargetId = entityId;
            return this;
        }

        /**
         * Low-observable: make this missile invisible to automatic detection (only a manual UUID lock can
         * engage it — see {@link MissileEntity#isStealth}).
         */
        public Builder stealth(boolean stealth) {
            this.stealth = stealth;
            return this;
        }

        /**
         * Evasion (0..1): how often this missile shrugs off an interception attempt — the tier that lets a
         * better missile escape interceptors more often (amplified in its terminal dive). See
         * {@link MissileEntity#effectiveEvasion}.
         */
        public Builder evasion(float evasion) {
            this.evasion = evasion;
            return this;
        }

        /**
         * Evasive maneuvering: when on, an evasion boost also jinks the missile off its course (a hard lateral
         * break away from the interceptor) instead of only sprinting straight, making the dodge unpredictable
         * and genuinely displacing it from the interceptor's lead. Needs {@link #evasion} to fire at all.
         */
        public Builder evasiveManeuver(boolean evasiveManeuver) {
            this.evasiveManeuver = evasiveManeuver;
            return this;
        }

        public MissileEntity build() {
            MissileEntity missile = new MissileEntity(this.type, this.level);
            missile.cruiseMode = this.cruiseMode;
            missile.cruiseAltitude = this.cruiseAltitude;
            missile.terrainClearance = this.terrainClearance;
            missile.explosionOffset = this.explosionOffset;
            missile.detonationId = this.detonationId;
            missile.detonation = WarheadRegistry.get(this.detonationId);
            if (this.ascentStageId != null) {
                missile.ascentStageId = this.ascentStageId;
            }
            if (this.cruiseStageId != null) {
                missile.cruiseStageId = this.cruiseStageId;
            }
            if (this.attackStageId != null) {
                missile.attackStageId = this.attackStageId;
            }
            missile.rebuildFlightProfile();
            missile.fragmentCount = this.fragmentCount;
            // Recursive-frag payload + defaults so a "recursive_frag" missile picked from the dispenser GUI
            // just works: it gets a split depth and, unless the launcher set one, an airburst altitude to
            // split at. Non-recursive missiles are unaffected (depth 0, swarm 0).
            boolean recursive = RecursiveFrag.ID.equals(this.detonationId);
            missile.splitDepth = (this.splitDepth != null)
                    ? this.splitDepth
                    : (recursive ? RecursiveFrag.DEFAULT_DEPTH : 0);
            missile.swarmId = this.swarmId;
            missile.commander = this.commander;
            missile.controlId = this.controlId;
            missile.teamId = this.teamId;
            if (recursive && this.explosionOffset <= 0.0f) {
                missile.explosionOffset = RecursiveFrag.splitAltitude(missile.splitDepth);
            }
            missile.cruiseSpeed = this.cruiseSpeed;
            if (this.ascentSpeed != null) {
                missile.ascentSpeed = this.ascentSpeed;
            }
            if (this.attackAngle != null) {
                missile.attackAngle = this.attackAngle;
            }
            missile.health = this.health;
            missile.setModelId(this.modelId);
            missile.setExhaustColor(this.exhaustColor);
            // Default the turn rate off the chosen model's length unless explicitly overridden.
            missile.maxTurnRate = (this.maxTurnRate != null)
                    ? this.maxTurnRate
                    : TURN_AGILITY / MissileModels.length(missile.getModelId());
            if (this.target != null) {
                missile.setTarget(this.target);
            }
            if (this.startInAttack) {
                missile.phase = Phase.ATTACK;
            } else if (this.startInCruise) {
                missile.phase = Phase.CRUISE;
            }
            missile.armed = this.startArmed;

            missile.fuelType = this.fuelType;
            missile.fuelCapacity = this.fuelTicks;
            missile.fuel = this.fuelTicks;
            missile.acceleration = this.acceleration;
            missile.deceleration = this.deceleration;
            missile.designatedTargetId = this.designatedTargetId;
            missile.stealth = this.stealth;
            missile.evasion = this.evasion;
            missile.evasiveManeuver = this.evasiveManeuver;

            missile.interceptor = this.interceptor;
            missile.interceptMode = this.interceptMode;
            missile.lockTargetId = this.lockTargetId;
            if (this.interceptChance != null) {
                missile.interceptChance = this.interceptChance;
            }
            if (this.interceptor) {
                // Home in every phase, launched already armed (it can kill immediately, no arming-distance
                // delay) and with no ground-attack terrain-follow. It starts in ASCEND so InterceptStage first
                // boosts straight up clear of any silo/depression walls before homing (see InterceptStage);
                // in the open that clear is satisfied almost at once, so reaction time is barely affected.
                missile.ascentStageId = FlightStageRegistry.keyOf(InterceptStage.INSTANCE);
                missile.cruiseStageId = FlightStageRegistry.keyOf(InterceptStage.INSTANCE);
                missile.attackStageId = FlightStageRegistry.keyOf(InterceptStage.INSTANCE);
                missile.rebuildFlightProfile();
                // A fresh launch starts in ASCEND so it climbs clear of any silo/depression before homing; a
                // rematerialized interceptor (startInCruise) is already airborne mid-intercept, so it keeps
                // homing without a launch clear.
                missile.phase = this.startInCruise ? Phase.CRUISE : Phase.ASCEND;
                missile.armed = true;
            }
            return missile;
        }
    }
}
