package com.wf.wflib.kinetic;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** A shell that is not in the world: four numbers and who fired it. */
public final class KineticSim {

    public final UUID id;
    public final KineticPreset preset;
    @Nullable
    public final UUID ownerId;
    @Nullable
    public final UUID factionId;

    /** The tick the shell was handed over on. */
    public long handOffTime;

    public Vec3 pos;
    public Vec3 velocity;
    public int remainingLife;

    public KineticSim(UUID id, KineticPreset preset, Vec3 pos, Vec3 velocity, int remainingLife,
                      @Nullable UUID ownerId, @Nullable UUID factionId) {
        this.id = id;
        this.preset = preset;
        this.pos = pos;
        this.velocity = velocity;
        this.remainingLife = remainingLife;
        this.ownerId = ownerId;
        this.factionId = factionId;
    }

    /** Take the shell from an entity that is on its way out of the world. */
    public static KineticSim of(KineticShellEntity shell, long gameTime) {
        Entity owner = shell.getOwner();
        KineticSim sim = new KineticSim(shell.getUUID(), shell.preset(), shell.position(),
                shell.getDeltaMovement(), shell.remainingLife(), owner == null ? null : owner.getUUID(),
                shell.factionId());
        sim.handOffTime = gameTime;
        return sim;
    }

    /**
     * One tick of flight, in the order {@link KineticShellEntity} flies it: move, then let drag and gravity work on
     * the velocity for next time.
     */
    public void advance() {
        this.pos = this.pos.add(this.velocity);
        this.velocity = this.velocity.scale(preset.decay()).subtract(0.0, preset.gravity(), 0.0);
    }

    /** True once the shell is on its way back down, which is when the world has to have it again. */
    public boolean descending() {
        return this.velocity.y <= 0.0;
    }

    /** Rebuild the round. The entity is new; the shot is the same one. */
    public KineticShellEntity toEntity(ServerLevel level) {
        KineticShellEntity shell = new KineticShellEntity(level, preset);
        shell.setPos(pos.x, pos.y, pos.z);
        shell.setDeltaMovement(velocity);
        shell.alignToMotion();
        shell.setRemainingLife(remainingLife);
        shell.setFactionId(factionId);
        if (ownerId != null) {
            Entity owner = level.getEntity(ownerId);
            if (owner != null) {
                shell.setOwner(owner);
            }
        }
        shell.setResumedFromSim();
        return shell;
    }
}
