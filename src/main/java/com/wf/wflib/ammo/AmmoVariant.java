package com.wf.wflib.ammo;

import com.google.gson.JsonObject;
import com.wf.wflib.round.effect.ImpactTrigger;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * One load of a {@link Calibre}; SI units: speed m/s, masses kg, gravity m/s^2, {@code quadraticDrag} 1/m,
 * {@code drag} fraction per tick, {@code blockPen} / {@code armorPenMm} mm. {@code tracer} / {@code tip} rgb, -1 =
 * none. {@code look} = override onto the per-shape defaults in {@link ProjectileLook#resolve}.
 */
public record AmmoVariant(ResourceLocation id, ResourceLocation calibre, String name, float speed,
                          float projectileMassKg, float drag, float quadraticDrag, float gravity, float damagePerKJ,
                          int pellets, int tracer, int tip, @Nullable Pierce pierce, @Nullable Explosive explosive,
                          @Nullable Motor motor, float blockPen, float armorPenMm, List<Effect> effects,
                          ProjectileLook.Override look) {

    public static final int NONE = -1;
    /** Unset {@code armorPenMm}, no explosive => {@code blockPen} x this. Estimate. */
    public static final float RHA_PER_BLOCK_PEN = 0.5f;

    public AmmoVariant {
        effects = List.copyOf(effects);
        if (!(speed > 0) || !(projectileMassKg > 0)) {
            throw new IllegalArgumentException(id + ": speed " + speed + " / projectileMassKg " + projectileMassKg);
        }
        if (drag < 0 || drag >= 1 || quadraticDrag < 0 || gravity < 0 || damagePerKJ < 0 || blockPen < 0
                || armorPenMm < 0) {
            throw new IllegalArgumentException(id + ": negative or out-of-range ballistics");
        }
        if (pellets < 1 || pellets > 256) {
            throw new IllegalArgumentException(id + ": pellets " + pellets + " not 1..256");
        }
        if (tracer == 0) {
            throw new IllegalArgumentException(id + ": tracer 0 (WFLib none); use -1");
        }
        if (motor != null && explosive == null) {
            throw new IllegalArgumentException(id + ": motor without explosive");
        }
        if (motor != null && blockPen > 0) {
            throw new IllegalArgumentException(id + ": blockPen with motor");
        }
    }

    public record Pierce(float dt, float dr) {
    }

    /** {@code size <= 0} => warhead's own. */
    public record Explosive(ResourceLocation warhead, float size) {
    }

    /** accel m/s^2, burn s. */
    public record Motor(float accel, float burn) {
    }

    /** WFLib impact effect; {@code params} = entry minus {@code id}/{@code on}/{@code chance}. */
    public record Effect(ResourceLocation id, ImpactTrigger on, float chance, JsonObject params) {
    }

    public static Builder builder(ResourceLocation id, ResourceLocation calibre, String name) {
        return new Builder(id, calibre, name);
    }

    public static final class Builder {
        private final ResourceLocation id;
        private final ResourceLocation calibre;
        private final String name;
        private float speed;
        private float mass;
        private float drag;
        private float quadraticDrag;
        private float gravity = 9.8f;
        private float damagePerKJ;
        private int pellets = 1;
        private int tracer = NONE;
        private int tip = NONE;
        private Pierce pierce;
        private Explosive explosive;
        private Motor motor;
        private float blockPen;
        private float armorPenMm;
        private final List<Effect> effects = new java.util.ArrayList<>();
        private ProjectileLook.Override look = ProjectileLook.Override.NONE;

        private Builder(ResourceLocation id, ResourceLocation calibre, String name) {
            this.id = id;
            this.calibre = calibre;
            this.name = name;
        }

        public Builder flight(float speed, float projectileMassKg, float quadraticDrag) {
            this.speed = speed;
            this.mass = projectileMassKg;
            this.quadraticDrag = quadraticDrag;
            return this;
        }

        public Builder drag(float drag) {
            this.drag = drag;
            return this;
        }

        public Builder gravity(float g) {
            this.gravity = g;
            return this;
        }

        public Builder damagePerKJ(float d) {
            this.damagePerKJ = d;
            return this;
        }

        public Builder pellets(int n) {
            this.pellets = n;
            return this;
        }

        public Builder tracer(int rgb) {
            this.tracer = rgb;
            return this;
        }

        public Builder tip(int rgb) {
            this.tip = rgb;
            return this;
        }

        public Builder pierce(float dt, float dr) {
            this.pierce = new Pierce(dt, dr);
            return this;
        }

        public Builder explosive(ResourceLocation warhead, float size) {
            this.explosive = new Explosive(warhead, size);
            return this;
        }

        public Builder motor(float accel, float burn) {
            this.motor = new Motor(accel, burn);
            return this;
        }

        public Builder blockPen(float mm) {
            this.blockPen = mm;
            return this;
        }

        public Builder armorPen(float mm) {
            this.armorPenMm = mm;
            return this;
        }

        public Builder effect(Effect e) {
            this.effects.add(e);
            return this;
        }

        public Builder look(ProjectileLook.Override look) {
            this.look = look;
            return this;
        }

        public AmmoVariant build() {
            float armor = armorPenMm > 0 || explosive != null ? armorPenMm
                    : Math.round(blockPen * RHA_PER_BLOCK_PEN * 2) / 2f;
            return new AmmoVariant(id, calibre, name, speed, mass, drag, quadraticDrag, gravity, damagePerKJ,
                    pellets, tracer, tip, pierce, explosive, motor, blockPen, armor, effects, look);
        }
    }
}
