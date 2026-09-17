package com.wf.wfballistics.block.entity;

import com.wf.wfballistics.block.ModBlockEntities;
import com.wf.wfballistics.compat.WarforgeCompat;
import com.wf.wfballistics.network.ScopeFramePacket;
import com.wf.wfballistics.recon.ReconBound;
import com.wf.wfballistics.recon.ReconNet;
import com.wf.wfballistics.recon.ReconOwners;
import com.wf.wfballistics.recon.SourceIds;
import com.wf.wfballistics.recon.event.SeismicFix;
import com.wf.wfballistics.recon.event.SeismicLog;
import com.wf.wfballistics.recon.grid.HubIndex;
import com.wf.wfballistics.recon.grid.ReconGrid;
import com.wf.wfballistics.recon.scope.ScopeFrame;
import com.wf.wfballistics.recon.track.TrackPicture;
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

/** The root of a sensor grid, and the thing that decides what a network <em>is</em>. */
public class ReconHubBlockEntity extends BlockEntity implements ReconBound {

    /** Blocks the hub itself can span in one link. */
    public static final double LINK_RANGE = 192.0;
    /** The hub's own mast, in blocks. */
    public static final double LINK_MAST = 8.0;

    private UUID gridId;
    /** Whether {@link #gridId} was handed to this hub or arrived at by it. */
    private boolean named;
    private int teamRefresh;
    private long sentAt = Long.MIN_VALUE;
    /** What this hub has heard go off. */
    private final SeismicLog events = new SeismicLog();
    /** The network's event counter as of the last pull. */
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

    /** Take anything new off the network's event buffer. */
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

    /**
     * @return this hub's record of what it has heard go off, newest first. Live: writing to it writes to the
     *      hub's own log, which is what an implementer wants and why it is not a copy.
     */
    public SeismicLog events() {
        return this.events;
    }

    /** Resize the event log. */
    public void setEventLogCapacity(int capacity) {
        this.events.setCapacity(capacity);
        this.setChanged();
    }

    /** Take the claiming faction's identity if there is one, and mint a fresh one if there is not. */
    private void adoptIdentity(ServerLevel sl) {
        UUID faction = ReconOwners.owningAt(sl, this.worldPosition);
        this.gridId = faction != null ? faction : UUID.randomUUID();
        this.named = false;
        this.setChanged();
    }

    /** Throw away this grid's identity and mint a new one. */
    public UUID remint() {
        this.setIdentity(UUID.randomUUID(), false);
        return this.gridId;
    }

    /**
     * Adopt an explicit identity, for a command that wants two hubs on one network: a second root is a second way
     * home, and the BFS treats them as equals.
     */
    public void adopt(UUID id) {
        this.setIdentity(id, true);
    }

    /** Become something else, and stop being what this was first. */
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

    /** Null unless somebody chose this identity, even though there is always an identity to report. */
    @Nullable
    @Override
    public UUID boundNet() {
        return this.named ? this.gridId : null;
    }

    /** Always the grid id, named or minted: a hub has a network to give away either way. */
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

    /** Ship this network's picture to anyone who might be looking at a display for it. */
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

    /**
     * @return how far out the scope should draw, which is not this block's own range: it has none. A hub is
     *      blind; what it can show is whatever its furthest online probe can see, so the display grows as the grid
     *      is built out. That is the coverage of the network made visible without a coverage overlay.
     */
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
