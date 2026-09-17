package com.wf.wfballistics.block.entity;

import com.wf.wfballistics.block.ModBlockEntities;
import com.wf.wfballistics.block.RadarSurveillanceBlock;
import com.wf.wfballistics.compat.WarforgeCompat;
import com.wf.wfballistics.network.ScopeFramePacket;
import com.wf.wfballistics.recon.ReconBound;
import com.wf.wfballistics.recon.ReconNet;
import com.wf.wfballistics.recon.ReconNetwork;
import com.wf.wfballistics.recon.ReconOwners;
import com.wf.wfballistics.recon.SensorHandle;
import com.wf.wfballistics.recon.SensorSpec;
import com.wf.wfballistics.recon.alert.Alert;
import com.wf.wfballistics.recon.alert.AlertTier;
import com.wf.wfballistics.recon.alert.EngagementAuthority;
import com.wf.wfballistics.recon.scope.ScopeFrame;
import com.wf.wfballistics.recon.track.Track;
import com.wf.wfballistics.recon.track.TrackPicture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/** A surveillance radar that is not attached to a gun. */
public class RadarSurveillanceBlockEntity extends BlockEntity implements ReconBound {

    /** Detection range against a reference signature of 1.0. */
    public static final double RANGE = 512.0;
    /** A tall mast. */
    public static final double MAST = 12.0;
    /** A slow sweep, because this is a warning set rather than a tracking one. */
    public static final int SWEEP_TICKS = 20;
    /** Ticks an alert holds the redstone line up. */
    public static final int ALERT_HOLD_TICKS = 60;

    private UUID cachedTeamId;
    private int teamRefresh;
    /** Whose net this set answers to, when the claim is not the answer. See {@link ReconBound}. */
    @Nullable
    private UUID bound;
    private int signal;
    private int signalTicks;
    private int trackCount;
    private long sentAt = Long.MIN_VALUE;

    public RadarSurveillanceBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.RADAR_SURVEILLANCE.get(), pos, state);
    }

    public void serverTick() {
        if (!(this.level instanceof ServerLevel sl)) {
            return;
        }
        if (--this.teamRefresh <= 0) {
            this.resolve(sl);
            this.teamRefresh = 100;
        }
        ReconNet.registerSensor(sl, this.worldPosition,
                SensorSpec.surveillanceRadar(ReconNet.netId(this.cachedTeamId), RANGE)
                        .withMast(MAST).withSweep(SWEEP_TICKS));

        TrackPicture picture = ReconNet.picture(sl, ReconNet.netId(this.cachedTeamId));
        this.trackCount = picture.tracks().size();
        this.consumeAlerts(sl, picture.alerts());
        this.broadcast(sl, picture);
        if (this.signalTicks > 0 && --this.signalTicks == 0) {
            this.setSignal(sl, 0);
        }
    }

    /** Send this network's picture to the players who might be looking at a display for it. */
    private void broadcast(ServerLevel sl, TrackPicture picture) {
        if (picture.gameTime() == this.sentAt || !this.isFrameSender(sl)) {
            return;
        }
        this.sentAt = picture.gameTime();
        ScopeFrame frame = ScopeFrame.of(ReconNet.netId(this.cachedTeamId),
                this.worldPosition.getX() + 0.5, this.worldPosition.getY() + MAST,
                this.worldPosition.getZ() + 0.5, RANGE, picture);
        PacketDistributor.sendToPlayersTrackingChunk(sl, new ChunkPos(this.worldPosition),
                new ScopeFramePacket(frame));
    }

    /**
     * @return true if this is the lowest-positioned sensor on its network, and so the one that speaks for it.
     */
    private boolean isFrameSender(ServerLevel sl) {
        ReconNetwork net = ReconNet.existing(sl, ReconNet.netId(this.cachedTeamId));
        if (net == null) {
            return false;
        }
        if (net.grid().rootCount() > 0) {
            return false;
        }
        long mine = this.worldPosition.asLong();
        for (SensorHandle handle : net.sensorHandles()) {
            if (handle.id() < mine) {
                return false;
            }
        }
        return true;
    }

    /** Raise the line to the strongest alert in this picture, and hold it. */
    private void consumeAlerts(ServerLevel sl, List<Alert> alerts) {
        int strongest = 0;
        for (int i = 0; i < alerts.size(); i++) {
            Alert alert = alerts.get(i);
            int strength = alert.tier() == AlertTier.CRITICAL ? 15 : 7;
            if (strength > strongest) {
                strongest = strength;
            }
        }
        if (strongest > 0) {
            this.signalTicks = ALERT_HOLD_TICKS;
            if (strongest > this.signal) {
                this.setSignal(sl, strongest);
            }
        }
    }

    private void setSignal(ServerLevel sl, int strength) {
        if (this.signal == strength) {
            return;
        }
        this.signal = strength;
        BlockState state = this.getBlockState();
        if (state.hasProperty(RadarSurveillanceBlock.ALERTING)) {
            sl.setBlock(this.worldPosition, state.setValue(RadarSurveillanceBlock.ALERTING, strength > 0), 3);
        }
        sl.updateNeighborsAt(this.worldPosition, state.getBlock());
    }

    public int signal() {
        return this.signal;
    }

    /**
     * @return the picture this set feeds, for the block's right-click report.
     */
    public TrackPicture picture() {
        return this.level instanceof ServerLevel sl
                ? ReconNet.picture(sl, ReconNet.netId(this.cachedTeamId))
                : TrackPicture.EMPTY;
    }

    /** Work out whose set this is: the binding if it has one, and otherwise whoever claims the ground under it. */
    private void resolve(ServerLevel sl) {
        this.cachedTeamId = this.bound != null ? this.bound
                : ReconOwners.owningAt(sl, this.worldPosition);
    }

    @Override
    public long netId() {
        return ReconNet.netId(this.cachedTeamId);
    }

    @Nullable
    @Override
    public UUID boundNet() {
        return this.bound;
    }

    @Override
    public void bindNet(@Nullable UUID id) {
        ServerLevel sl = this.level instanceof ServerLevel s ? s : null;
        if (sl != null) {
            ReconNet.unregisterSensor(sl, this.worldPosition, this.netId());
        }
        this.bound = id;
        this.setChanged();
        if (sl != null) {
            this.resolve(sl);
        }
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
    }

    public int trackCount() {
        return this.trackCount;
    }

    /**
     * @return the nearest track this set holds, or null. Here so the block can name something in chat without
     *      every caller having to know how a picture is queried.
     */
    public Track nearest() {
        return this.picture().nearest(this.worldPosition.getX() + 0.5, this.worldPosition.getY() + MAST,
                this.worldPosition.getZ() + 0.5, RANGE, t -> true);
    }

    @Override
    public void setRemoved() {
        if (this.level instanceof ServerLevel sl) {
            ReconNet.unregisterSensor(sl, this.worldPosition, this.netId());
        }
        super.setRemoved();
    }
}
