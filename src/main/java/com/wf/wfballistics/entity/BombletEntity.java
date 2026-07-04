package com.wf.wfballistics.entity;

import com.wf.wfballistics.ModEntities;
import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.aef.nuke.ExplosionNukeGeneric;
import com.wf.wfballistics.fx.ExplosionSmallCreator;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * A bomblet: a small, tumbling orange fragment flung out by a fragmentation warhead (see
 * {@link com.wf.wfballistics.util.FragmentationUtil}). It's a gravity-affected projectile that carries a
 * pluggable {@link Detonation} — the same warhead pattern {@link com.wf.wfballistics.MissileEntity} uses — and
 * goes off on the first thing it hits or when its fuse runs out, whichever comes first.
 *
 * <p>Because a burst spawns many of these at once, the payload is intentionally light: a small radius kill and
 * a compact FX puff by default. Pick the warhead by registered id so it survives save/load, exactly like the
 * missile's warheads.
 */
public class BombletEntity extends Projectile {

    public static final float BLAST_RADIUS = 4.0f;
    public static final float MAX_DAMAGE = 40.0f;
    // Ticks an airborne bomblet flies before self-detonating, so a cluster still goes off over a gap/void.
    public static final int DEFAULT_FUSE = 60;
    /**
     * Default sub-munition: a small radius kill plus a compact fireball puff.
     */
    public static final Detonation STANDARD = (bomblet, pos) -> {
        Level level = bomblet.level();
        if (level.isClientSide) {
            return;
        }
        ExplosionNukeGeneric.dealDamage(level, pos, BLAST_RADIUS, MAX_DAMAGE);
        ExplosionSmallCreator.composeEffect(level, pos.x, pos.y, pos.z, 3, 1.0f, 0.6f);
    };
    /**
     * A dud: falls and vanishes without a bang (useful for tracer-only or test bursts).
     */
    public static final Detonation INERT = (bomblet, pos) -> {
    };
    public static final Detonation FIRE = (bomblet, pos) -> {
        Level level = bomblet.level();
        if (level.isClientSide) {
            return;
        }
        ExplosionNukeGeneric.dealDamage(level, pos, BLAST_RADIUS, MAX_DAMAGE * 0.5f);
        FireLingeringEntity.spawn(level, pos.x, pos.y, pos.z, 3.0f, 2.0f, 120, FireLingeringEntity.TYPE_DIESEL);
        ExplosionSmallCreator.composeEffect(level, pos.x, pos.y, pos.z, 3, 1.0f, 0.6f);
    };
    public static final ResourceLocation STANDARD_ID = rl("standard");
    public static final ResourceLocation INERT_ID = rl("inert");
    public static final ResourceLocation FIRE_ID = rl("fire");
    private static final double GRAVITY = 0.05;
    private static final double DRAG = 0.99;
    private static final Map<ResourceLocation, Detonation> WARHEADS = new HashMap<>();

    static {
        WARHEADS.put(STANDARD_ID, STANDARD);
        WARHEADS.put(INERT_ID, INERT);
        WARHEADS.put(FIRE_ID, FIRE);
    }

    private ResourceLocation detonationId = STANDARD_ID;
    private Detonation detonation = STANDARD;
    private int maxFuse = DEFAULT_FUSE;
    // Guards against re-entrant detonation (the blast can hurt/hit this bomblet before discard() runs).
    private boolean detonated = false;

    public BombletEntity(EntityType<? extends BombletEntity> type, Level level) {
        super(type, level);
    }

    /**
     * @param velocity     initial launch vector (blocks/tick); gravity and drag take over from here
     * @param detonation   warhead fired on impact / fuse-out
     * @param detonationId registered id for that warhead so it persists across save/load
     * @param fuse         ticks before self-detonation (<= 0 disables the fuse)
     */
    public BombletEntity(Level level, Vec3 pos, Vec3 velocity, Detonation detonation, ResourceLocation detonationId, int fuse) {
        this(ModEntities.BOMBLET.get(), level);
        this.setPos(pos.x, pos.y, pos.z);
        this.setDeltaMovement(velocity);
        this.detonation = detonation != null ? detonation : STANDARD;
        this.detonationId = detonationId != null ? detonationId : STANDARD_ID;
        this.maxFuse = fuse;

        // Cosmetic heading so the entity's yaw/pitch match its travel (the cube tumbles in the renderer).
        double horizontal = velocity.horizontalDistance();
        this.setYRot((float) (Mth.atan2(velocity.x, velocity.z) * (180.0 / Math.PI)));
        this.setXRot((float) (Mth.atan2(velocity.y, horizontal) * (180.0 / Math.PI)));
        this.yRotO = this.getYRot();
        this.xRotO = this.getXRot();
    }

    public static void registerWarhead(ResourceLocation id, Detonation detonation) {
        WARHEADS.put(id, detonation);
    }

    private static Detonation warhead(ResourceLocation id) {
        return WARHEADS.getOrDefault(id, STANDARD);
    }

    private static ResourceLocation rl(String path) {
        return new ResourceLocation(WFBallistics.MODID, path);
    }

    @Override
    public void tick() {
        super.tick();

        if (this.level().isClientSide || this.detonated) {
            return;
        }

        // Fuse: airborne self-destruct so a cluster always goes off, even with nothing beneath it.
        if (this.maxFuse > 0 && this.tickCount >= this.maxFuse) {
            this.detonate(this.position());
            return;
        }

        HitResult hit = ProjectileUtil.getHitResultOnMoveVector(this, this::canHitEntity);
        if (hit.getType() != HitResult.Type.MISS) {
            this.onHit(hit);
            if (this.isRemoved()) {
                return;
            }
        }

        Vec3 motion = this.getDeltaMovement();
        double nx = this.getX() + motion.x;
        double ny = this.getY() + motion.y;
        double nz = this.getZ() + motion.z;

        this.setDeltaMovement(motion.scale(DRAG));
        if (!this.isNoGravity()) {
            this.setDeltaMovement(this.getDeltaMovement().subtract(0.0, GRAVITY, 0.0));
        }
        this.setPos(nx, ny, nz);
    }

    @Override
    protected void onHit(HitResult result) {
        super.onHit(result);
        if (!this.level().isClientSide) {
            this.detonate(result.getLocation());
        }
    }

    @Override
    protected boolean canHitEntity(Entity entity) {
        // Bomblets pass harmlessly through one another so a dense burst doesn't chain-detonate at the muzzle,
        // and through their owner so a point-blank burst doesn't detonate on the firer at spawn.
        if (entity instanceof BombletEntity || entity == this.getOwner()) {
            return false;
        }
        return super.canHitEntity(entity);
    }

    /**
     * Fires the configured warhead at {@code pos} and removes the bomblet.
     */
    private void detonate(Vec3 pos) {
        if (this.detonated) {
            return;
        }
        this.detonated = true; // set before the blast: it can hit/hurt this bomblet before discard() runs
        this.detonation.detonate(this, pos);
        this.discard();
    }

    @Override
    protected void defineSynchedData() {
        // No synced state: position/velocity ride the vanilla spawn + tracker packets, and the cube is a
        // fixed-colour render.
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("Detonation", this.detonationId.toString());
        tag.putInt("MaxFuse", this.maxFuse);
        tag.putBoolean("Detonated", this.detonated);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("Detonation")) {
            ResourceLocation parsed = ResourceLocation.tryParse(tag.getString("Detonation"));
            this.detonationId = parsed != null ? parsed : STANDARD_ID;
            this.detonation = warhead(this.detonationId);
        }
        if (tag.contains("MaxFuse")) {
            this.maxFuse = tag.getInt("MaxFuse");
        }
        this.detonated = tag.getBoolean("Detonated");
    }

    @FunctionalInterface
    public interface Detonation {
        void detonate(BombletEntity bomblet, Vec3 pos);
    }
}
