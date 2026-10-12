package com.wf.wflib.block.entity;

import com.wf.wflib.block.ModBlockEntities;
import com.wf.wflib.compat.WarforgeCompat;
import com.wf.wflib.network.ScopeFramePacket;
import com.wf.wflib.recon.ReconBound;
import com.wf.wflib.recon.ReconNet;
import com.wf.wflib.recon.ReconOwners;
import com.wf.wflib.recon.SourceIds;
import com.wf.wflib.recon.event.SeismicFix;
import com.wf.wflib.recon.event.SeismicLog;
import com.wf.wflib.recon.grid.HubIndex;
import com.wf.wflib.recon.grid.ReconGrid;
import com.wf.wflib.recon.scope.ScopeFrame;
import com.wf.wflib.recon.track.TrackPicture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

public class ReconHubBlockEntity extends BlockEntity implements ReconBound {

    public static final double LINK_RANGE = 192.0;
    public static final double LINK_MAST = 8.0;

    private UUID gridId;
    private boolean named;
    private int teamRefresh;
    private long sentAt = Long.MIN_VALUE;
    private final SeismicLog events = new SeismicLog();
    private long eventStamp = Long.MIN_VALUE;

    public ReconHubBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.RECON_HUB.get(), pos, state);
    }

    public void serverTick() {
        if (!(this.level instanceof ServerLevel sl)) {
            return;
        }
        if (this.gridId == null && --this.teamRefresh <= 0) {
            this.adoptIdentity(sl);
            this.teamRefresh = 20;
        }
        if (this.gridId == null) {
            return;
        }
        long netId = this.netId();
        HubIndex.register(sl, this.worldPosition, netId);
        ReconNet.registerNode(sl, this.worldPosition, netId, LINK_RANGE, LINK_MAST, true, "hub");
        this.broadcast(sl, ReconNet.picture(sl, netId), netId);
        this.pullEvents(sl, netId);
    }

    private void pullEvents(ServerLevel sl, long netId) {
        long stamp = ReconNet.eventStamp(sl, netId);
        if (stamp == this.eventStamp) {
            return;
        }
        this.eventStamp = stamp;
        for (SeismicFix fix : ReconNet.events(sl, netId)) {
            this.events.record(fix);
        }
        this.setChanged();
    }

    /** @return hub's live log, not a copy: mutating it mutates the hub. */
    public SeismicLog events() {
        return this.events;
    }

    public void setEventLogCapacity(int capacity) {
        this.events.setCapacity(capacity);
        this.setChanged();
    }

    private void adoptIdentity(ServerLevel sl) {
        UUID faction = ReconOwners.owningAt(sl, this.worldPosition);
        this.gridId = faction != null ? faction : UUID.randomUUID();
        this.named = false;
        this.setChanged();
    }

    public UUID remint() {
        this.setIdentity(UUID.randomUUID(), false);
        return this.gridId;
    }

    public void adopt(UUID id) {
        this.setIdentity(id, true);
    }

    private void setIdentity(@Nullable UUID id, boolean named) {
        this.named = named;
        if (Objects.equals(this.gridId, id)) {
            this.setChanged();
            return;
        }
        if (this.gridId != null && this.level instanceof ServerLevel sl) {
            HubIndex.unregister(sl, this.worldPosition);
            ReconNet.unregisterNode(sl, this.worldPosition, this.netId());
        }
        this.gridId = id;
        this.teamRefresh = 0;
        this.eventStamp = Long.MIN_VALUE;
        this.setChanged();
    }

    public UUID gridId() {
        return this.gridId;
    }

    /** Null means unchosen (minted), not absent: a hub always has an identity. */
    @Nullable
    @Override
    public UUID boundNet() {
        return this.named ? this.gridId : null;
    }

    /** Unlike the default: always non-null, minted or named alike. */
    @Nullable
    @Override
    public UUID identity() {
        return this.gridId;
    }

    @Override
    public void bindNet(@Nullable UUID id) {
        this.setIdentity(id, id != null);
        if (id == null && this.level instanceof ServerLevel sl) {
            this.adoptIdentity(sl);
        }
    }

    @Override
    public long netId() {
        return this.gridId == null ? ReconNet.UNAFFILIATED : SourceIds.of(this.gridId);
    }

    public TrackPicture picture() {
        return this.level instanceof ServerLevel sl ? ReconNet.picture(sl, this.netId()) : TrackPicture.EMPTY;
    }

    public ReconGrid grid() {
        return this.level instanceof ServerLevel sl ? ReconNet.grid(sl, this.netId()) : null;
    }

    private void broadcast(ServerLevel sl, TrackPicture picture, long netId) {
        if (picture.gameTime() == this.sentAt) {
            return;
        }
        this.sentAt = picture.gameTime();
        ScopeFrame frame = ScopeFrame.of(netId, this.worldPosition.getX() + 0.5,
                this.worldPosition.getY() + LINK_MAST, this.worldPosition.getZ() + 0.5,
                this.reach(), picture, this.events.recent(ScopeFrame.MAX_EVENTS), sl.getGameTime());
        PacketDistributor.sendToPlayersTrackingChunk(sl, new ChunkPos(this.worldPosition),
                new ScopeFramePacket(frame));
    }

    /** @return display heuristic, not real range: hub itself is blind; grows with furthest online probe. */
    public double reach() {
        ReconGrid grid = this.grid();
        double out = 128.0;
        if (grid == null) {
            return out;
        }
        for (var node : grid.nodes()) {
            if (!node.online()) {
                continue;
            }
            double dist = Math.sqrt(node.pos().distSqr(this.worldPosition));
            out = Math.max(out, dist + 64.0);
        }
        return Math.min(out, 2048.0);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (this.gridId != null) {
            tag.putUUID("GridId", this.gridId);
        }
        if (this.named) {
            tag.putBoolean("NamedNet", true);
        }
        if (!this.events.isEmpty()) {
            tag.put("Events", this.events.save());
        }
        if (this.events.capacity() != SeismicLog.DEFAULT_CAPACITY) {
            tag.putInt("EventCap", this.events.capacity());
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.gridId = tag.hasUUID("GridId") ? tag.getUUID("GridId") : null;
        this.named = tag.getBoolean("NamedNet");
        if (tag.contains("EventCap")) {
            this.events.setCapacity(tag.getInt("EventCap"));
        }
        this.events.load(tag.getList("Events", Tag.TAG_COMPOUND));
    }

    @Override
    public void setRemoved() {
        if (this.level instanceof ServerLevel sl) {
            HubIndex.unregister(sl, this.worldPosition);
            ReconNet.unregisterNode(sl, this.worldPosition, this.netId());
        }
        super.setRemoved();
    }
}
