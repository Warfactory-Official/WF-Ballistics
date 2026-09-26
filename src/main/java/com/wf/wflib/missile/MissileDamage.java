package com.wf.wflib.missile;

import com.wf.wflib.MissileEntity;
import com.wf.wflib.MissileEntity.DownedAction;
import com.wf.wflib.MissileModels;
import com.wf.wflib.api.WFEventType;
import com.wf.wflib.damage.MissileDamageRegistry;
import com.wf.wflib.damage.MissileDamageResponse;
import com.wf.wflib.mine.DefuseMethod;
import com.wf.wflib.mine.MineEntity;
import com.wf.wflib.mine.MineTags;
import com.wf.wflib.sim.MissileSimConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.UUID;

/** Health, getting shot down, and what is left afterwards: a crash, a spin-out, a dud on the ground. */
public final class MissileDamage {

    static final double FALL_HORIZONTAL_DRAG = 0.99;
    private static final double POWER_LOSS_DRAG = 0.93;
    static final double FUEL_OUT_GRAVITY = 0.05;
    static final double TERMINAL_FALL_SPEED = -3.9;
    private static final double SPINOUT_GRAVITY = 0.03;
    private static final double SPINOUT_RATE_KICK = 0.15;    // rad/tick, the hit's initial yank
    private static final double SPINOUT_RATE_JITTER = 0.06;  // rad/tick std per tick per axis
    private static final double SPINOUT_RATE_DAMPING = 0.92;
    private static final double SPINOUT_MAX_RATE = 0.35;     // rad/tick
    private static final int SPINOUT_MIN_BURN = 20;          // motor ticks left once guidance is gone
    private static final int SPINOUT_MAX_BURN = 80;
    private static final float SPINOUT_ROLL_MIN = 0.4f;      // rad/tick visual roll about the nose
    private static final float SPINOUT_ROLL_MAX = 1.0f;
    private static final float CRASH_ROLL = 0.08f;
    /** Nose buried this fraction of the body length. */
    private static final double DUD_BURY = 0.3;
    /** Non-explosive hit on a dud sets it off at this chance. */
    private static final float DUD_DISTURB_CHANCE = 0.25f;
    private static final double DUD_DROP_SPEED = 0.05;
    private static final int DUD_DEFUSE_TICKS = 100;
    private static final float DUD_DEFUSE_FAIL_CHANCE = 0.1f;
    private static final double DUD_DEFUSE_REACH = 4.0;
    /** Ticks a fresh shoot-down ignores re-hits before one forces the warhead. */
    private static final int DOWNED_REHIT_GRACE = 60;

    private final MissileEntity missile;

    float health = MissileEntity.DEFAULT_HEALTH;
    ResourceLocation responseId = MissileDamageRegistry.defaultId();
    MissileDamageResponse response = MissileDamageRegistry.STANDARD;
    private boolean downed;
    DownedAction downedAction = DownedAction.CRASH;
    /** Angular velocity (axis * rad/tick) of the uncontrolled heading. */
    @Nullable
    private Vec3 spinOutRate;
    private int spinOutBurn;
    double dudChance = MissileEntity.DEFAULT_DUD_CHANCE;
    @Nullable
    private UUID dudDefuserId;
    private int dudDefuseProgress;
    private int downedGrace;

    public MissileDamage(MissileEntity missile) {
        this.missile = missile;
    }

    void setResponse(ResourceLocation id) {
        this.responseId = id;
        this.response = MissileDamageRegistry.get(id);
    }

    public float getHealth() {
        return this.health;
    }

    public ResourceLocation getResponseId() {
        return this.responseId;
    }

    public boolean isDowned() {
        return this.downed;
    }

    /** Entity hurt: a dud goes off on an explosion (or by chance); live missiles take response-scaled damage. */
    public boolean hurt(DamageSource source, float amount) {
        if (this.missile.isDud()) {
            if (!this.missile.fuze().detonated()
                    && (source.is(DamageTypeTags.IS_EXPLOSION) || this.missile.getRandom().nextFloat() < DUD_DISTURB_CHANCE)) {
                this.missile.fuze().detonate(this.missile.position(), false);
            }
            return true;
        }
        if (source.is(DamageTypeTags.IS_PROJECTILE) && amount < MissileSimConfig.MIN_PROJECTILE_DAMAGE) {
            return false;
        }
        float effective = this.response.apply(this.missile, source, amount);
        if (effective <= 0.0f) {
            return false;
        }
        this.damage(effective);
        return true;
    }

    /** Unarmed missiles absorb damage: a stray hit must not cook one off on its launcher. */
    public void damage(float amount) {
        if (this.missile.level().isClientSide || this.missile.isRemoved() || this.missile.fuze().detonated()
                || amount <= 0.0f || !this.missile.fuze().isArmed()) {
            return;
        }
        this.health -= amount;
        this.missile.recordEvent(WFEventType.DAMAGED, String.format("dmg=%.1f hp=%.1f", amount, this.health));
        if (this.health <= 0.0f) {
            this.shootDown();
        }
    }

    public void shootDown() {
        this.shootDown(this.downedAction);
    }

    /** Re-hit on a downed missile past its grace => full warhead on the spot. */
    public void shootDown(DownedAction action) {
        if (this.missile.fuze().detonated()) {
            return;
        }
        if (this.downed) {
            if (this.downedGrace <= 0) {
                this.missile.fuze().detonate(this.missile.position(), false);
            }
            return;
        }
        switch (action) {
            case FIZZLE -> this.missile.fuze().detonate(this.missile.position(), true);
            case DETONATE -> this.missile.fuze().detonate(this.missile.position(), false);
            default -> {
                this.downedAction = action;
                this.downed = true;
                if (action != DownedAction.SPIN_OUT) {
                    this.missile.syncDownedRoll(this.missile.getRandom().nextBoolean() ? CRASH_ROLL : -CRASH_ROLL);
                }
                this.missile.motor().cut();
                this.downedGrace = DOWNED_REHIT_GRACE;
                this.missile.recordEvent(WFEventType.DESTROYED, "shot down: " + action);
            }
        }
    }

    void tickDowned(ServerLevel level, Vec3 currentPos) {
        if (this.downedGrace > 0) {
            this.downedGrace--;
        }
        switch (this.downedAction) {
            case SPIN_OUT -> this.spinOut(level, currentPos);
            case POWER_LOSS -> MissileTicker.coast(this.missile, level, currentPos, POWER_LOSS_DRAG, true);
            default -> MissileTicker.coast(this.missile, level, currentPos, FALL_HORIZONTAL_DRAG, true);
        }
    }

    /** Guidance gone, motor not: turn rate random-walks (corkscrew, loop, dive), then ballistic once burnt out. */
    private void spinOut(ServerLevel level, Vec3 currentPos) {
        this.missile.chunkLoader().update(this.missile, level, currentPos, this.missile.getDeltaMovement(), true);
        // Own stream: level.random is drawn by everything else ticking.
        RandomSource random = this.missile.getRandom();
        if (this.spinOutRate == null) {
            this.spinOutRate = new Vec3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian())
                    .normalize().scale(SPINOUT_RATE_KICK);
            this.spinOutBurn = SPINOUT_MIN_BURN + random.nextInt(SPINOUT_MAX_BURN - SPINOUT_MIN_BURN + 1);
            float roll = SPINOUT_ROLL_MIN + random.nextFloat() * (SPINOUT_ROLL_MAX - SPINOUT_ROLL_MIN);
            this.missile.syncDownedRoll(random.nextBoolean() ? roll : -roll);
        }
        Vec3 cur = this.missile.getDeltaMovement();
        Vec3 v;
        if (this.spinOutBurn > 0) {
            this.spinOutBurn--;
            this.spinOutRate = this.spinOutRate.scale(SPINOUT_RATE_DAMPING)
                    .add(random.nextGaussian() * SPINOUT_RATE_JITTER, random.nextGaussian() * SPINOUT_RATE_JITTER,
                            random.nextGaussian() * SPINOUT_RATE_JITTER);
            double rate = this.spinOutRate.length();
            if (rate > SPINOUT_MAX_RATE) {
                this.spinOutRate = this.spinOutRate.scale(SPINOUT_MAX_RATE / rate);
                rate = SPINOUT_MAX_RATE;
            }
            double speed = Math.max(cur.length(), 1.0);
            Vec3 dir = cur.lengthSqr() > 1.0E-8 ? cur.normalize() : new Vec3(0.0, -1.0, 0.0);
            if (rate > 1.0E-6) {
                dir = rotate(dir, this.spinOutRate.scale(1.0 / rate), rate);
            }
            v = dir.scale(speed);
            v = new Vec3(v.x, Math.max(v.y - SPINOUT_GRAVITY, TERMINAL_FALL_SPEED), v.z);
            level.sendParticles(ParticleTypes.FLAME, this.missile.getX(), this.missile.getY(), this.missile.getZ(),
                    2, 0.15, 0.15, 0.15, 0.01);
        } else {
            v = new Vec3(cur.x * FALL_HORIZONTAL_DRAG, Math.max(cur.y - FUEL_OUT_GRAVITY, TERMINAL_FALL_SPEED),
                    cur.z * FALL_HORIZONTAL_DRAG);
        }
        level.sendParticles(ParticleTypes.LARGE_SMOKE, this.missile.getX(), this.missile.getY(), this.missile.getZ(),
                2, 0.2, 0.2, 0.2, 0.02);
        HitResult hit = this.missile.flyTo(currentPos, v);
        if (hit != null) {
            this.downedImpact(hit.getLocation(), v, false);
        }
    }

    /** Rodrigues rotation of unit {@code v} about unit {@code axis}. */
    private static Vec3 rotate(Vec3 v, Vec3 axis, double angle) {
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        return v.scale(cos).add(axis.cross(v).scale(sin)).add(axis.scale(axis.dot(v) * (1.0 - cos)));
    }

    /** Downed missile reaches the ground: dud at {@link #dudChance}, else warhead ({@code fizzle} = intercept effect). */
    void downedImpact(Vec3 at, Vec3 velocity, boolean fizzle) {
        if (this.dudChance > 0.0D && this.missile.getRandom().nextDouble() < this.dudChance) {
            this.becomeDud(at, velocity);
            return;
        }
        this.missile.fuze().detonate(at, fizzle);
    }

    private void becomeDud(Vec3 at, Vec3 velocity) {
        Vec3 heading = velocity.lengthSqr() > 1.0E-8 ? velocity.normalize() : new Vec3(0.0, -1.0, 0.0);
        double length = MissileModels.dimensions(this.missile.getModelId()).y;
        Vec3 base = at.subtract(heading.scale(length * (1.0 - DUD_BURY)));
        this.missile.setDeltaMovement(Vec3.ZERO);
        this.missile.setPos(base.x, base.y, base.z);
        this.missile.syncDud(new Vector3f((float) heading.x, (float) heading.y, (float) heading.z));
        this.missile.syncDownedRoll(0.0f);
        this.missile.refitBounds();
        if (this.missile.level() instanceof ServerLevel sl) {
            this.missile.chunkLoader().releaseAll(this.missile, sl);
            sl.playSound(null, at.x, at.y, at.z, SoundEvents.ANVIL_LAND, SoundSource.HOSTILE, 1.0f, 0.5f);
        }
        this.missile.recordEvent(WFEventType.DESTROYED, "dud");
    }

    /** Contact fuze failed: falls clear of what it struck and lies as a dud. */
    public void dropAsDud() {
        if (this.missile.fuze().detonated() || this.downed) {
            return;
        }
        this.missile.fuze().fuseFailed = true;
        this.dudChance = 1.0D;
        this.shootDown(DownedAction.CRASH);
        Vec3 v = this.missile.getDeltaMovement();
        this.missile.setDeltaMovement(v.x * DUD_DROP_SPEED, 0.0, v.z * DUD_DROP_SPEED);
    }

    /** Crouch with a {@link MineTags#DEFUSER} and hold still: the mine gesture, on a dud. */
    public InteractionResult interact(Player player) {
        if (!this.missile.isDud() || !player.isSecondaryUseActive()) {
            return InteractionResult.PASS;
        }
        if (this.missile.level().isClientSide) {
            return InteractionResult.SUCCESS;
        }
        Component refusal = MineEntity.defuseRefusal(player, DefuseMethod.TOOL, MineTags.DEFUSER);
        if (refusal != null) {
            player.displayClientMessage(refusal.copy().withStyle(ChatFormatting.YELLOW), true);
            return InteractionResult.FAIL;
        }
        this.dudDefuserId = player.getUUID();
        this.dudDefuseProgress = 0;
        return InteractionResult.CONSUME;
    }

    void tickDudDefuse(ServerLevel level) {
        if (this.dudDefuserId == null) {
            return;
        }
        Player player = level.getPlayerByUUID(this.dudDefuserId);
        if (player == null || !player.isAlive() || !player.isCrouching()
                || player.distanceToSqr(this.missile) > DUD_DEFUSE_REACH * DUD_DEFUSE_REACH) {
            this.dudDefuserId = null;
            this.dudDefuseProgress = 0;
            return;
        }
        if (++this.dudDefuseProgress % 10 == 0) {
            level.playSound(null, this.missile.getX(), this.missile.getY(), this.missile.getZ(),
                    SoundEvents.STONE_BUTTON_CLICK_ON, SoundSource.NEUTRAL, 0.3f, 1.4f);
        }
        if (this.dudDefuseProgress < DUD_DEFUSE_TICKS) {
            return;
        }
        if (this.missile.getRandom().nextFloat() < DUD_DEFUSE_FAIL_CHANCE) {
            this.missile.fuze().detonate(this.missile.position(), false);
            return;
        }
        level.playSound(null, this.missile.getX(), this.missile.getY(), this.missile.getZ(), SoundEvents.ITEM_PICKUP,
                SoundSource.NEUTRAL, 0.7f, 1.2f);
        this.missile.recordEvent(WFEventType.INTERCEPTED, "dud defused");
        this.missile.discard();
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putFloat("Health", this.health);
        tag.putString("Response", this.responseId.toString());
        tag.putBoolean("Downed", this.downed);
        tag.putInt("DownedGrace", this.downedGrace);
        tag.putString("DownedAction", this.downedAction.name());
        tag.putDouble("DudChance", this.dudChance);
        return tag;
    }

    public void load(CompoundTag tag) {
        this.health = tag.getFloat("Health");
        this.setResponse(MissileDamageRegistry.parse(tag.getString("Response")));
        this.downed = tag.getBoolean("Downed");
        this.downedGrace = tag.getInt("DownedGrace");
        this.downedAction = DownedAction.valueOf(tag.getString("DownedAction"));
        this.dudChance = tag.getDouble("DudChance");
    }
}
