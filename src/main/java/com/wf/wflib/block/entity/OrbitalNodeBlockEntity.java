package com.wf.wflib.block.entity;

import com.wf.wflib.compat.WarforgeCompat;
import com.wf.wflib.recon.ReconBound;
import com.wf.wflib.recon.ReconNet;
import com.wf.wflib.recon.ReconOwners;
import com.wf.wflib.recon.SourceIds;
import com.wf.wflib.recon.grid.HubIndex;
import com.wf.wflib.recon.grid.ReconGrid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public abstract class OrbitalNodeBlockEntity extends BlockEntity implements ReconBound {

    public static final double LINK_RANGE = 128.0;
    private static final int RESOLVE_INTERVAL = 100;

    @Nullable
    private UUID bound;
    private long netId;
    private int resolveIn;

    protected OrbitalNodeBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    protected abstract String nodeLabel();

    protected boolean nodeActive() {
        return true;
    }

    public void serverTick() {
        if (!(this.level instanceof ServerLevel sl)) {
            return;
        }
        if (--this.resolveIn <= 0) {
            long fallback = ReconNet.netId(ReconOwners.owningAt(sl, this.worldPosition));
            long resolved = this.bound != null ? SourceIds.of(this.bound)
                    : HubIndex.netFor(sl, this.worldPosition, LINK_RANGE * ReconGrid.MAX_HOPS, fallback);
            if (resolved != this.netId) {
                ReconNet.unregisterNode(sl, this.worldPosition, this.netId);
                this.netId = resolved;
            }
            this.resolveIn = RESOLVE_INTERVAL;
        }
        if (nodeActive()) {
            ReconNet.registerNode(sl, this.worldPosition, netId(), LINK_RANGE, 0.0, false, nodeLabel());
        } else {
            ReconNet.unregisterNode(sl, this.worldPosition, netId());
        }
    }

    @Override
    public long netId() {
        return this.netId;
    }

    @Nullable
    @Override
    public UUID boundNet() {
        return this.bound;
    }

    @Override
    public void bindNet(@Nullable UUID id) {
        if (this.level instanceof ServerLevel sl) {
            ReconNet.unregisterNode(sl, this.worldPosition, netId());
        }
        this.bound = id;
        this.resolveIn = 0;
        this.setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (this.bound != null) {
            tag.putUUID("BoundNet", this.bound);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.bound = tag.hasUUID("BoundNet") ? tag.getUUID("BoundNet") : null;
        this.resolveIn = 0;
    }

    @Override
    public void setRemoved() {
        if (this.level instanceof ServerLevel sl) {
            ReconNet.unregisterNode(sl, this.worldPosition, netId());
        }
        super.setRemoved();
    }
}
