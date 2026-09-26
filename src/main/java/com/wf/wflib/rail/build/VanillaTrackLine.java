package com.wf.wflib.rail.build;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Locale;

/**
 * Vanilla rail, for a world with no Immersive Railroading in it.
 *
 * <p>Not a substitute for IR track and not pretending to be: a minecart is what runs on it. It exists
 * because the rail package is deliberately not gated on IR - a route is worth surveying, tunnelling and
 * arguing over on a server that has never installed it - and a tunnel with nothing to run in it is a
 * corridor.</p>
 */
public final class VanillaTrackLine implements TrackLine {

    private final RailPath rails;
    private final int floorY;
    private final BlockState bed;
    private final int boostSpacing;

    private int cursor;
    /** Cells taken as already built, so a resumed route does not claim to have laid them again. */
    private int skipped;
    private TrackLayer.Laid laid = TrackLayer.Laid.NOTHING;

    public VanillaTrackLine(RailPath rails, int floorY, BlockState bed, int boostSpacing) {
        this.rails = rails;
        this.floorY = floorY;
        this.bed = bed;
        this.boostSpacing = boostSpacing;
    }

    @Override
    public int layTo(ServerLevel level, double chainage) {
        List<RailPath.Cell> cells = this.rails.cells();
        int done = 0;
        while (this.cursor < cells.size() && cells.get(this.cursor).chainage() <= chainage) {
            boolean boost = this.boostSpacing > 0 && this.cursor % this.boostSpacing == 0;
            TrackLayer.Laid one = TrackLayer.lay(level, this.rails, this.cursor, this.floorY, boost,
                    this.bed);
            if (!one.any()) {
                // Its chunk went away under us. The same piece is tried again next call rather than
                // leaving a gap in the line that nothing would ever come back for.
                break;
            }
            this.laid = this.laid.plus(one);
            this.cursor++;
            done++;
        }
        return done;
    }

    @Override
    public int skipTo(double chainage) {
        List<RailPath.Cell> cells = this.rails.cells();
        int was = this.cursor;
        while (this.cursor < cells.size() && cells.get(this.cursor).chainage() <= chainage) {
            this.cursor++;
        }
        this.skipped += this.cursor - was;
        return this.cursor - was;
    }

    @Override
    public int laid() {
        return this.cursor;
    }

    @Override
    public int total() {
        return this.rails.cells().size();
    }

    @Override
    public String kind() {
        return "vanilla rail";
    }

    @Override
    public BlockPos railhead() {
        List<RailPath.Cell> cells = this.rails.cells();
        if (cells.isEmpty()) {
            return BlockPos.ZERO;
        }
        RailPath.Cell cell = cells.get(Math.max(0, Math.min(this.cursor, cells.size() - 1)));
        return new BlockPos(cell.x(), this.floorY, cell.z());
    }

    @Override
    public String describe() {
        return String.format(Locale.ROOT, "%d rail + %d booster(s)%s", this.laid.rails(),
                this.laid.boosters(), this.skipped > 0 ? " (" + this.skipped + " already there)" : "");
    }
}
