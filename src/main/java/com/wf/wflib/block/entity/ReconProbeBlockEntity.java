package com.wf.wflib.block.entity;

import com.wf.wflib.block.ModBlockEntities;
import com.wf.wflib.block.ProbeKind;
import com.wf.wflib.block.ReconProbeBlock;
import com.wf.wflib.compat.WarforgeCompat;
import com.wf.wflib.recon.ReconBound;
import com.wf.wflib.recon.ReconNet;
import com.wf.wflib.recon.ReconOwners;
import com.wf.wflib.recon.SourceIds;
import com.wf.wflib.recon.grid.GridNode;
import com.wf.wflib.recon.grid.HubIndex;
import com.wf.wflib.recon.grid.ReconGrid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** One remote sensor on a grid: it draws power, warms up, links home, and only then contributes anything. */
public class ReconProbeBlockEntity extends BlockEntity implements ReconBound {

    /** Ticks between re-checks of which hub this belongs to. */
    private static final int HUB_REFRESH = 40;

    private final ProbeEnergy energy;
    private long netId = ReconNet.UNAFFILIATED;
    /** Set by hand, and then {@link #refile} stops choosing. Null is the normal case. See {@link ReconBound}. */
    @Nullable
    private UUID bound;
    private int hubRefresh;
    private int calibration;
    private int lastHops = -1;
    private boolean online;
    /** Sonar only: whether this set is standing in water, and whether redstone is telling it to ping. */
    private boolean wet = true;
    private boolean pinging;

    public ReconProbeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.RECON_PROBE.get(), pos, state);
        this.energy = new ProbeEnergy(this.kind(state).feCapacity());
    }

    /**
     * @return which probe this is, read off the block rather than stored. One block entity type serves all
     *      three, and the block is the only thing that has to know which.
     */
    private ProbeKind kind(BlockState state) {
        return state.getBlock() instanceof ReconProbeBlock probe ? probe.kind() : ProbeKind.RADAR;
    }

    public ProbeKind kind() {
        return this.kind(this.getBlockState());
    }

    public void serverTick() {
        if (!(this.level instanceof ServerLevel sl)) {
            return;
        }
        ProbeKind kind = this.kind();
        if (kind.underwater()) {
            // Redstone is the ping: a pulse you wire up and fire, not a mode you leave on.
            this.pinging = sl.hasNeighborSignal(this.worldPosition);
            this.wet = inWater(sl, this.worldPosition);
        }
        if (this.energy.consume(this.draw(kind))) {
            this.tickOnline(sl, kind);
        } else {
            this.goDark(sl);
        }
        this.pushState(sl);
    }

    /**
     * @return FE per tick at this moment. A hydrophone listening is the cheapest thing on a grid and one
     *      pinging is the most expensive, and nothing else varies.
     */
    public int draw(ProbeKind kind) {
        return this.pinging ? kind.feDraw() * ProbeKind.PING_DRAW : kind.feDraw();
    }

    /** Its own block, or the one above it: a hydrophone on the seabed is in the water it sits under. */
    private static boolean inWater(ServerLevel sl, BlockPos pos) {
        return sl.getFluidState(pos).is(FluidTags.WATER) || sl.getFluidState(pos.above()).is(FluidTags.WATER);
    }

    private void tickOnline(ServerLevel sl, ProbeKind kind) {
        if (--this.hubRefresh <= 0) {
            this.hubRefresh = HUB_REFRESH;
            this.refile(sl, kind);
        }
        GridNode node = ReconNet.registerNode(sl, this.worldPosition, this.netId,
                kind.linkRange(), kind.linkMast(), false, kind.id());
        int hops = node.hops();
        if (hops != this.lastHops) {
            this.lastHops = hops;
            this.calibration = 0;
        }
        if (hops < 0) {
            this.online = false;
            return;
        }
        if (this.calibration < kind.calibrationTicks()) {
            this.calibration++;
            this.online = false;
            return;
        }
        if (kind.underwater() && !this.wet) {
            // Still a relay. A dry hydrophone carries links home and hears nothing at all.
            ReconNet.unregisterSensor(sl, this.worldPosition, this.netId);
            this.online = false;
            return;
        }
        this.online = true;
        if (kind.sensing()) {
            ReconNet.registerSensor(sl, this.worldPosition, kind.spec(this.netId, this.pinging));
        } else {
            ReconNet.registerStation(sl, this.worldPosition, this.netId, kind.stationRange());
        }
    }

    /** Work out which hub this probe answers to. */
    private void refile(ServerLevel sl, ProbeKind kind) {
        long resolved;
        if (this.bound != null) {
            resolved = SourceIds.of(this.bound);
        } else {
            long fallback = ReconNet.netId(ReconOwners.owningAt(sl, this.worldPosition));
            resolved = HubIndex.netFor(sl, this.worldPosition,
                    kind.linkRange() * ReconGrid.MAX_HOPS, fallback);
        }
        if (resolved == this.netId) {
            return;
        }
        this.leave(sl, this.netId);
        this.netId = resolved;
        this.calibration = 0;
        this.lastHops = -1;
    }

    private void goDark(ServerLevel sl) {
        if (this.calibration != 0 || this.online || this.lastHops != -1) {
            this.leave(sl, this.netId);
        }
        this.calibration = 0;
        this.lastHops = -1;
        this.online = false;
    }

    /** Drop every membership this probe might hold on a network. */
    private void leave(ServerLevel sl, long net) {
        ReconNet.unregisterNode(sl, this.worldPosition, net);
        ReconNet.unregisterSensor(sl, this.worldPosition, net);
        ReconNet.unregisterStation(sl, this.worldPosition, net);
    }

    /**
     * Keep the block's own state in step, so the grid can be read by walking it rather than by opening a screen at
     * every node.
     */
    private void pushState(ServerLevel sl) {
        BlockState state = this.getBlockState();
        if (!state.hasProperty(ReconProbeBlock.ONLINE) || state.getValue(ReconProbeBlock.ONLINE) == this.online) {
            return;
        }
        sl.setBlock(this.worldPosition, state.setValue(ReconProbeBlock.ONLINE, this.online), 3);
    }

    public IEnergyStorage energy() {
        return this.energy;
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
        if (!(this.level instanceof ServerLevel sl)) {
            return;
        }
        this.refile(sl, this.kind());
        this.hubRefresh = HUB_REFRESH;
    }

    public boolean online() {
        return this.online;
    }

    public int calibration() {
        return this.calibration;
    }

    public int hops() {
        return this.lastHops;
    }

    /**
     * @return this probe's node on its grid, or null if it is not on one.
     */
    @Nullable
    public GridNode node() {
        if (!(this.level instanceof ServerLevel sl)) {
            return null;
        }
        ReconGrid grid = ReconNet.grid(sl, this.netId);
        return grid == null ? null : grid.node(this.worldPosition);
    }

    /**
     * @return where this probe is in the lifecycle above, for a readout.
     */
    public String status() {
        ProbeKind kind = this.kind();
        if (this.energy.getEnergyStored() < this.draw(kind)) {
            return "NO POWER";
        }
        if (this.lastHops < 0) {
            return "ORPHANED";
        }
        if (kind.underwater() && !this.wet) {
            return "DRY";
        }
        if (!this.online) {
            return "CALIBRATING " + this.calibration + "/" + kind.calibrationTicks();
        }
        return this.pinging ? "PINGING" : "ONLINE";
    }

    /**
     * @return true if this set is radiating. Only ever true for a hydrophone under a redstone signal, and it
     *      is what puts the set on other people's nets as a contact of its own.
     */
    public boolean pinging() {
        return this.pinging;
    }

    public boolean wet() {
        return this.wet;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("Energy", this.energy.getEnergyStored());
        tag.putInt("Calibration", this.calibration);
        tag.putLong("NetId", this.netId);
        if (this.bound != null) {
            tag.putUUID("BoundNet", this.bound);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.energy.set(tag.getInt("Energy"));
        this.calibration = tag.getInt("Calibration");
        this.netId = tag.getLong("NetId");
        this.bound = tag.hasUUID("BoundNet") ? tag.getUUID("BoundNet") : null;
    }

    @Override
    public void setRemoved() {
        if (this.level instanceof ServerLevel sl) {
            this.leave(sl, this.netId);
        }
        super.setRemoved();
    }

    /** A buffer that takes energy from anything and gives none back. */
    private static final class ProbeEnergy extends EnergyStorage {

        private ProbeEnergy(int capacity) {
            super(capacity, Math.max(1, capacity / 20), 0);
        }

        /**
         * @return true if the draw was paid in full. Partial payment is refused rather than taken: a probe
         *      running at half power is not a coherent state, and letting it dribble away the last of a buffer
         *      would make "no power" flicker instead of latch.
         */
        private boolean consume(int amount) {
            if (this.energy < amount) {
                return false;
            }
            this.energy -= amount;
            return true;
        }

        private void set(int stored) {
            this.energy = Math.max(0, Math.min(this.capacity, stored));
        }
    }
}
