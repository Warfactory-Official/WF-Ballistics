package com.wf.wflib.api;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;
import org.jetbrains.annotations.Nullable;

/**
 * Server, {@code NeoForge.EVENT_BUS}, once per second per {@link PreciseHitbox} target within reach of a burning
 * incendiary charge (thermite). Vehicles route it to the facing armour region and systems.
 *
 * @param heat     damage-equivalent this second at {@code at}
 * @param contact  charge attached to / resting on {@code target}
 * @param distance charge to nearest point of the target's shape, blocks
 */
public class ThermalAttackEvent extends Event {

    private final Entity target;
    private final Vec3 at;
    private final float heat;
    private final boolean contact;
    private final double distance;
    @Nullable
    private final Entity attacker;
    private final Entity charge;

    public ThermalAttackEvent(Entity target, Vec3 at, float heat, boolean contact, double distance,
                              @Nullable Entity attacker, Entity charge) {
        this.target = target;
        this.at = at;
        this.heat = heat;
        this.contact = contact;
        this.distance = distance;
        this.attacker = attacker;
        this.charge = charge;
    }

    public Entity target() {
        return target;
    }

    public Vec3 at() {
        return at;
    }

    public float heat() {
        return heat;
    }

    public boolean contact() {
        return contact;
    }

    public double distance() {
        return distance;
    }

    @Nullable
    public Entity attacker() {
        return attacker;
    }

    public Entity charge() {
        return charge;
    }
}
