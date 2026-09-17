package com.wf.wfballistics.block.entity;

import com.wf.wfballistics.block.ModBlockEntities;
import com.wf.wfballistics.compat.WarforgeCompat;
import com.wf.wfballistics.recon.ReconBound;
import com.wf.wfballistics.recon.ReconNet;
import com.wf.wfballistics.recon.ReconOwners;
import com.wf.wfballistics.recon.SourceIds;
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

/** An in-world display for a sensor network. */
public class RadarScopeBlockEntity extends BlockEntity implements ReconBound {

    /** Ticks between faction re-checks. Claims move rarely; a screen that lags one behind is harmless. */
    private static final int TEAM_REFRESH = 100;

    private long netId;
    private int teamRefresh;
    /** Which net to show, when the claim is not the answer. See {@link ReconBound}. */
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
        // Not in getUpdateTag: the client is sent the resolved net and has no use for how it was arrived at.
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
