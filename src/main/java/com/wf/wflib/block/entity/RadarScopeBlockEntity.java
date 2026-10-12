package com.wf.wflib.block.entity;

import com.wf.wflib.block.ModBlockEntities;
import com.wf.wflib.compat.WarforgeCompat;
import com.wf.wflib.recon.ReconBound;
import com.wf.wflib.recon.ReconNet;
import com.wf.wflib.recon.ReconOwners;
import com.wf.wflib.recon.SourceIds;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class RadarScopeBlockEntity extends BlockEntity implements ReconBound {

    /** Claims move rarely; one-interval-stale screen harmless. */
    private static final int TEAM_REFRESH = 100;

    private long netId;
    private int teamRefresh;
    @Nullable
    private UUID bound;

    public RadarScopeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.RADAR_SCOPE.get(), pos, state);
    }

    public void serverTick() {
        if (!(this.level instanceof ServerLevel sl)) {
            return;
        }
        if (--this.teamRefresh > 0) {
            return;
        }
        this.teamRefresh = TEAM_REFRESH;
        this.resolve(sl);
    }

    private void resolve(ServerLevel sl) {
        long resolved = this.bound != null ? SourceIds.of(this.bound)
                : ReconNet.netId(ReconOwners.owningAt(sl, this.worldPosition));
        if (resolved != this.netId) {
            this.netId = resolved;
            this.setChanged();
            sl.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), 3);
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
        this.bound = id;
        this.setChanged();
        if (this.level instanceof ServerLevel sl) {
            this.resolve(sl);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putLong("NetId", this.netId);
        if (this.bound != null) {
            tag.putUUID("BoundNet", this.bound);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.netId = tag.getLong("NetId");
        // Omitted from getUpdateTag: client needs the resolved net only.
        this.bound = tag.hasUUID("BoundNet") ? tag.getUUID("BoundNet") : null;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.putLong("NetId", this.netId);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
