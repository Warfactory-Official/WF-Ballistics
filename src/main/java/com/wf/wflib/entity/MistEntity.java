package com.wf.wflib.entity;

import com.wf.wflib.ModEntities;
import com.wf.wflib.client.fx.MistClientFX;
import com.wf.wflib.entity.mist.MistEffect;
import com.wf.wflib.entity.mist.MistEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;

import java.util.List;

/** A stationary cloud of fluid hanging in the air: gas, spray or vapour. */
public class MistEntity extends Entity {

    private static final EntityDataAccessor<Float> RADIUS = SynchedEntityData.defineId(MistEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> HEIGHT = SynchedEntityData.defineId(MistEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<String> FLUID = SynchedEntityData.defineId(MistEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Float> BOX_X = SynchedEntityData.defineId(MistEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> BOX_Y = SynchedEntityData.defineId(MistEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> BOX_Z = SynchedEntityData.defineId(MistEntity.class, EntityDataSerializers.FLOAT);

    private int maxAge = 150;
    private int effectInterval = 1;

    public MistEntity(EntityType<? extends MistEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    /**
     * Creates, fits and spawns a mist cloud.
     *
     * @param radius intended horizontal radius (shrunk by the wall check)
     * @param height vertical extent, growing up from {@code y}
     * @param duration lifetime in ticks
     */
    public static MistEntity spawn(Level level, Fluid fluid, double x, double y, double z,
                                   float radius, float height, int duration) {
        MistEntity mist = new MistEntity(ModEntities.MIST.get(), level);
        mist.setPos(x, y, z);
        mist.setFluid(fluid);
        mist.setArea(radius, height);
        mist.setDuration(duration);
        mist.fitToBounds();
        level.addFreshEntity(mist);
        return mist;
    }

    /**
     * Spawns a single box-mode gas cell: an exact cuboid whose min corner is {@code (minX, minY, minZ)} and whose
     * extents are {@code (sizeX, sizeY, sizeZ)} blocks.
     *
     * @param effectInterval process entities in this cell every N ticks (>=1) to bound cost across a cloud
     */
    public static MistEntity spawnBox(Level level, Fluid fluid, double minX, double minY, double minZ,
                                      float sizeX, float sizeY, float sizeZ, int duration, int effectInterval) {
        MistEntity gas = new MistEntity(ModEntities.MIST.get(), level);
        gas.setFluid(fluid);
        gas.setDuration(duration);
        gas.effectInterval = Math.max(1, effectInterval);
        gas.setPos(minX, minY, minZ);     // position = box min corner
        gas.setBox(sizeX, sizeY, sizeZ);  // recomputes the exact bounding box from that corner
        level.addFreshEntity(gas);
        return gas;
    }

    private static boolean isAffectable(Entity entity) {
        if (entity instanceof MistEntity) return false;
        return !(entity instanceof Player player) || (!player.isSpectator() && !player.isCreative());
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(RADIUS, 1F);
        builder.define(HEIGHT, 1F);
        builder.define(FLUID, "");
        builder.define(BOX_X, 0F);
        builder.define(BOX_Y, 0F);
        builder.define(BOX_Z, 0F);
    }

    /**
     * @return true if this cloud is a box-mode gas cell rather than a radius/height puff.
     */
    public boolean isBox() {
        return this.entityData.get(BOX_X) > 0F;
    }

    /**
     * Sets the exact cuboid extents (blocks) and refits the bounding box from the min-corner position.
     */
    public MistEntity setBox(float sizeX, float sizeY, float sizeZ) {
        this.entityData.set(BOX_X, sizeX);
        this.entityData.set(BOX_Y, sizeY);
        this.entityData.set(BOX_Z, sizeZ);
        this.setBoundingBox(this.makeBoundingBox());
        return this;
    }

    @Override
    protected AABB makeBoundingBox() {
        float bx = this.entityData.get(BOX_X);
        if (bx > 0F) {
            // Box mode: an exact cuboid growing in +x/+y/+z from the entity's (min-corner) position.
            return new AABB(getX(), getY(), getZ(),
                    getX() + bx, getY() + this.entityData.get(BOX_Y), getZ() + this.entityData.get(BOX_Z));
        }
        return super.makeBoundingBox();
    }

    @Override
    public void tick() {
        super.tick();

        if (!level().isClientSide) {
            if (this.tickCount >= maxAge) {
                discard();
                return;
            }

            MistEffect effect = MistEffects.get(getFluid());
            if (effect == null) return;

            if (this.tickCount % this.effectInterval == 0) {
                double intensity = 1.0 - (double) this.tickCount / maxAge;
                effect.areaTick(this, intensity);

                List<Entity> targets = level().getEntities(this, getBoundingBox(), MistEntity::isAffectable);
                for (Entity target : targets) {
                    effect.affect(this, target, intensity);
                }
            }
        } else {
            // isClientSide is already true here; call directly (DistExecutor is gone in NeoForge).
            MistClientFX.spawn(this);
        }
    }

    /** Shrinks the cloud's radius so it doesn't poke through walls. */
    public void fitToBounds() {
        float requested = getRadius();
        double cx = getX();
        double cy = getY() + getMistHeight() * 0.5;
        double cz = getZ();

        float fit = requested;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            for (float d = 0.5F; d <= requested; d += 0.5F) {
                cursor.set(cx + dir.getStepX() * d, cy, cz + dir.getStepZ() * d);
                if (level().getBlockState(cursor).isSolidRender(level(), cursor)) {
                    fit = Math.min(fit, d - 0.5F);
                    break;
                }
            }
        }

        setArea(Math.max(fit, 0.5F), getMistHeight());
    }

    // --- accessors / synced data ----------------------------------------------------------------------

    public MistEntity setArea(float radius, float height) {
        this.entityData.set(RADIUS, radius);
        this.entityData.set(HEIGHT, height);
        refreshDimensions();
        return this;
    }

    public MistEntity setDuration(int duration) {
        this.maxAge = duration;
        return this;
    }

    public Fluid getFluid() {
        String id = this.entityData.get(FLUID);
        if (id.isEmpty()) return Fluids.EMPTY;
        Fluid fluid = BuiltInRegistries.FLUID.get(ResourceLocation.parse(id));
        return fluid == null ? Fluids.EMPTY : fluid;
    }

    public MistEntity setFluid(Fluid fluid) {
        ResourceLocation key = BuiltInRegistries.FLUID.getKey(fluid);
        this.entityData.set(FLUID, key == null ? "" : key.toString());
        return this;
    }

    public float getRadius() {
        return this.entityData.get(RADIUS);
    }

    public float getMistHeight() {
        return this.entityData.get(HEIGHT);
    }

    public int getMaxAge() {
        return maxAge;
    }

    // --- entity plumbing ------------------------------------------------------------------------------

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        float radius = 1F, height = 1F;
        try {
            float bx = this.entityData.get(BOX_X);
            if (bx > 0F) {
                // Box mode: makeBoundingBox() is the authority; this is only a rough hull for culling.
                float by = this.entityData.get(BOX_Y);
                float bz = this.entityData.get(BOX_Z);
                return EntityDimensions.scalable(Math.max(Math.max(bx, bz), 0.2F), Math.max(by, 0.2F));
            }
            radius = this.entityData.get(RADIUS);
            height = this.entityData.get(HEIGHT);
        } catch (Exception ignored) {
            // entityData not yet populated during construction; fall back to a unit box
        }
        return EntityDimensions.scalable(Math.max(radius * 2F, 0.2F), Math.max(height, 0.2F));
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        if (BOX_X.equals(key) || BOX_Y.equals(key) || BOX_Z.equals(key)) {
            // Box extents changed (or arrived on the client): rebuild the exact cuboid bounding box.
            this.setBoundingBox(this.makeBoundingBox());
        } else if (RADIUS.equals(key) || HEIGHT.equals(key)) {
            refreshDimensions();
        }
        super.onSyncedDataUpdated(key);
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        setFluid(BuiltInRegistries.FLUID.get(ResourceLocation.parse(tag.getString("fluid"))));
        setArea(tag.getFloat("radius"), tag.getFloat("height"));
        this.maxAge = tag.getInt("maxAge");
        if (tag.contains("effectInterval")) {
            this.effectInterval = Math.max(1, tag.getInt("effectInterval"));
        }
        if (tag.contains("boxX")) {
            setBox(tag.getFloat("boxX"), tag.getFloat("boxY"), tag.getFloat("boxZ"));
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        ResourceLocation key = BuiltInRegistries.FLUID.getKey(getFluid());
        tag.putString("fluid", key == null ? "" : key.toString());
        tag.putFloat("radius", getRadius());
        tag.putFloat("height", getMistHeight());
        tag.putInt("maxAge", maxAge);
        tag.putInt("effectInterval", effectInterval);
        tag.putFloat("boxX", this.entityData.get(BOX_X));
        tag.putFloat("boxY", this.entityData.get(BOX_Y));
        tag.putFloat("boxZ", this.entityData.get(BOX_Z));
    }
}
