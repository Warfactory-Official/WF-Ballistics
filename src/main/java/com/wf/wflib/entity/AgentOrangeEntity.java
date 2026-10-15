package com.wf.wflib.entity;

import com.wf.wflib.ModEntities;
import com.wf.wflib.compat.WarforgeCompat;
import com.wf.wflib.warhead.AgentOrangeWarhead;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * Defoliant spray sinking over a disc of columns, one layer per tick. Per column: {@link AgentOrangeWarhead#TARGETS}
 * -> air, keeps falling; air / no collision + no fluid -> falls through; anything else (dirt, stone, water,
 * claim-protected target) -> column stops. Entities untouched.
 */
public class AgentOrangeEntity extends Entity {

    /** Depth cap below the release layer. */
    public static final int MAX_FALL = 160;
    private static final DustParticleOptions MIST = new DustParticleOptions(new Vector3f(0.85f, 0.45f, 0.1f), 2.0f);
    private static final int PARTICLES_PER_TICK = 24;

    private static final EntityDataAccessor<Integer> RADIUS =
            SynchedEntityData.defineId(AgentOrangeEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> FALLEN =
            SynchedEntityData.defineId(AgentOrangeEntity.class, EntityDataSerializers.INT);

    /** Disc column offsets, {dx, dz} interleaved. */
    private int[] columns = new int[0];
    private BitSet stopped = new BitSet();
    private UUID igniterFaction;

    public AgentOrangeEntity(EntityType<? extends AgentOrangeEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    public static AgentOrangeEntity spawn(Level level, Vec3 at, int radius, UUID igniterFaction) {
        AgentOrangeEntity spray = new AgentOrangeEntity(ModEntities.AGENT_ORANGE.get(), level);
        spray.moveTo(at.x, at.y, at.z);
        spray.setRadius(radius);
        spray.igniterFaction = igniterFaction;
        level.addFreshEntity(spray);
        return spray;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(RADIUS, 0);
        builder.define(FALLEN, 0);
    }

    private void setRadius(int radius) {
        this.entityData.set(RADIUS, radius);
        int r2 = radius * radius;
        IntArrayList out = new IntArrayList();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz <= r2) {
                    out.add(dx);
                    out.add(dz);
                }
            }
        }
        this.columns = out.toIntArray();
    }

    public int radius() {
        return this.entityData.get(RADIUS);
    }

    public int fallen() {
        return this.entityData.get(FALLEN);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            mist();
            return;
        }
        int fallen = fallen();
        int y = this.getBlockY() - fallen;
        if (fallen > MAX_FALL || y < this.level().getMinBuildHeight()) {
            this.discard();
            return;
        }
        int n = this.columns.length / 2;
        List<BlockPos> targets = new ArrayList<>();
        IntArrayList targetColumns = new IntArrayList();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int i = this.stopped.nextClearBit(0); i < n; i = this.stopped.nextClearBit(i + 1)) {
            at.set(this.getBlockX() + this.columns[2 * i], y, this.getBlockZ() + this.columns[2 * i + 1]);
            if (!this.level().isLoaded(at)) {
                this.stopped.set(i);
                continue;
            }
            BlockState state = this.level().getBlockState(at);
            if (state.is(AgentOrangeWarhead.TARGETS)) {
                targets.add(at.immutable());
                targetColumns.add(i);
            } else if (!state.isAir() && (!state.getFluidState().isEmpty()
                    || !state.getCollisionShape(this.level(), at).isEmpty())) {
                this.stopped.set(i);
            }
        }
        if (!targets.isEmpty()) {
            HashSet<BlockPos> allowed = new HashSet<>(targets);
            WarforgeCompat.filterClaimProtected(this.level(), this.igniterFaction, allowed);
            for (int k = 0; k < targets.size(); k++) {
                BlockPos pos = targets.get(k);
                if (allowed.contains(pos)) {
                    this.level().setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                } else {
                    this.stopped.set(targetColumns.getInt(k));
                }
            }
        }
        if (this.stopped.cardinality() >= n) {
            this.discard();
            return;
        }
        this.entityData.set(FALLEN, fallen + 1);
    }

    private void mist() {
        int radius = Math.max(1, radius());
        double y = this.getY() - fallen();
        for (int k = 0; k < PARTICLES_PER_TICK; k++) {
            double a = this.random.nextDouble() * Math.PI * 2.0;
            double r = Math.sqrt(this.random.nextDouble()) * radius;
            this.level().addParticle(MIST, this.getX() + Math.cos(a) * r, y + this.random.nextDouble() * 2.0,
                    this.getZ() + Math.sin(a) * r, 0.0, -0.05, 0.0);
        }
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        setRadius(tag.getInt("Radius"));
        this.entityData.set(FALLEN, tag.getInt("Fallen"));
        this.stopped = BitSet.valueOf(tag.getLongArray("Stopped"));
        this.igniterFaction = tag.hasUUID("Faction") ? tag.getUUID("Faction") : null;
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("Radius", radius());
        tag.putInt("Fallen", fallen());
        tag.putLongArray("Stopped", this.stopped.toLongArray());
        if (this.igniterFaction != null) {
            tag.putUUID("Faction", this.igniterFaction);
        }
    }
}
