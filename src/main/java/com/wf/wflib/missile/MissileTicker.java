package com.wf.wflib.missile;

import com.wf.wflib.MissileEntity;
import com.wf.wflib.MissileEntity.Phase;
import com.wf.wflib.flight.FlightContext;
import com.wf.wflib.sim.MissileListenerRegistry;
import com.wf.wflib.sim.MissileSimConfig;
import com.wf.wflib.sim.SimMissileManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Server tick order across the components. Early returns = the missile ended or left the world this tick. */
public final class MissileTicker {

    private MissileTicker() {
    }

    public static void tick(MissileEntity m, ServerLevel level) {
        if (m.isDud()) {
            m.damage().tickDudDefuse(level);
            return;
        }
        MissileFlight flight = m.flight();
        flight.noteLaunch();
        Vec3 pos = m.position();
        m.initTelemetry();
        if (!m.interceptor().isActive() && !m.damage().isDowned()) {
            MissileListenerRegistry.get(level).noteThreat(pos, level.getGameTime());
        }

        double speed = m.getDeltaMovement().length();
        boolean gliding = m.motor().gliding(speed);
        if (m.motor().getFuel() <= 0 && !gliding) {
            m.noteFuelOut(pos);
            if (m.damage().isDowned()) {
                m.damage().tickDowned(level, pos);
            } else {
                coast(m, level, pos, MissileDamage.FALL_HORIZONTAL_DRAG, false);
            }
            return;
        }
        if (!gliding) {
            m.motor().burn();
        }

        if (m.interceptor().isActive() && !m.interceptor().update(level, pos)) {
            return;
        }
        m.seeker().tick(level);

        FlightContext ctx = flight.context(pos);
        boolean loadFan = flight.getCruiseMode() == MissileEntity.CruiseMode.TERRAIN_FOLLOW
                || flight.getPhase() == Phase.ATTACK
                || ctx.horizontalDist() < MissileSimConfig.FAN_TERMINAL_RANGE;
        boolean preloadTarget = !m.interceptor().isActive() && m.fuze().isArmed()
                && m.fuze().getImpactPreloadRadius() > 0
                && (flight.getPhase() == Phase.ATTACK || ctx.horizontalDist() < MissileSimConfig.FAN_TERMINAL_RANGE);
        m.chunkLoader().setTargetPreload(preloadTarget ? flight.getTarget() : null, m.fuze().getImpactPreloadRadius());
        m.chunkLoader().update(m, level, pos, m.getDeltaMovement(), loadFan);

        MissileEntity lead = m.swarm().formationLead(level);
        Vec3 velocity = m.seeker().orderedVelocity();
        if (velocity == null) {
            if (lead != null) {
                velocity = m.swarm().formationGuide(lead);
            } else {
                velocity = flight.stageVelocity(ctx);
                if (m.getControlId() != null || m.swarm().getSwarmId() != 0L) {
                    velocity = velocity.add(m.swarm().avoidFriendlies());
                }
            }
        }
        velocity = MissileFlight.constrainTurn(m.getDeltaMovement(), velocity, flight.turnRateAt(speed));
        velocity = m.motor().boost(level, velocity);
        if (gliding) {
            double v = velocity.length();
            velocity = v < 1.0E-8 ? velocity : velocity.scale(m.motor().glideSpeed(speed, velocity.y / v) / v);
        } else {
            velocity = m.motor().applyThrust(velocity);
        }

        HitResult hit = m.flyTo(pos, velocity);
        m.logFlightDebug(ctx);
        // After the move: what matters is whether it ended the tick in the water.
        if (flight.tickBroach()) {
            return;
        }
        m.emitFlightNoise();

        if (m.interceptor().isActive() && m.interceptor().tryIntercept(pos)) {
            return;
        }
        if (offload(m, level, ctx)) {
            return;
        }
        if (m.fuze().isArmed()) {
            if (m.fuze().airburstDue(flight.getTarget()) || hit == null && m.fuze().proximityDue(level)) {
                m.fuze().detonate(m.position(), false);
                return;
            }
            if (hit != null) {
                m.fuze().impact(hit);
            }
        }
    }

    /** Long cruise, nobody listening, no seeker lock to keep => leave the world as a {@link com.wf.wflib.sim.SimMissile}. */
    private static boolean offload(MissileEntity m, ServerLevel level, FlightContext ctx) {
        if (m.interceptor().isActive() || m.seeker().operated() || m.seeker().mode() != SeekerMode.DESIGNATED
                || m.flight().getMedium().hasCeiling()
                || m.flight().getPhase() != Phase.CRUISE
                || m.flight().cruiseTicks() <= MissileSimConfig.CRUISE_SIM_DELAY_TICKS
                || ctx.horizontalDist() <= MissileSimConfig.DESTINATION_RANGE
                || SimMissileManager.nearAnyListener(level, m.position())) {
            return false;
        }
        if (m.swarm().getSwarmId() == 0L) {
            SimMissileManager.startSim(m);
            return true;
        }
        if (m.swarm().isCommander()) {
            List<MissileEntity> subs = m.swarm().offloadableSubordinates(level);
            if (subs != null) {
                SimMissileManager.startSimSwarm(m, subs);
                return true;
            }
        }
        return false;
    }

    /** Unpowered: gravity toward terminal speed + horizontal {@code drag}; a hit fizzles (downed) or strikes. */
    static void coast(MissileEntity m, ServerLevel level, Vec3 pos, double drag, boolean fizzleOnImpact) {
        m.chunkLoader().update(m, level, pos, m.getDeltaMovement(), true);
        Vec3 v = m.getDeltaMovement();
        v = new Vec3(v.x * drag, Math.max(v.y - MissileDamage.FUEL_OUT_GRAVITY, MissileDamage.TERMINAL_FALL_SPEED),
                v.z * drag);
        HitResult hit = m.flyTo(pos, v);
        if (hit == null) {
            return;
        }
        if (fizzleOnImpact) {
            m.damage().downedImpact(hit.getLocation(), v, true);
        } else {
            m.fuze().impact(hit);
        }
    }
}
