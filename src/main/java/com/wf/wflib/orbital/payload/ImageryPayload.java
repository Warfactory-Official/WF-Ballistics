package com.wf.wflib.orbital.payload;

import com.wf.wflib.chunk.DetonationChunkGuard;
import com.wf.wflib.orbital.OrbitalConfig;
import com.wf.wflib.orbital.SatPayload;
import com.wf.wflib.orbital.SatVerb;
import com.wf.wflib.orbital.Satellite;
import com.wf.wflib.orbital.survey.SurveyRaster;
import com.wf.wflib.orbital.survey.SurveyTile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

public final class ImageryPayload implements SatPayload {

    private boolean active = true;
    private int cursor;
    private int columns;
    private int misses;

    @Override
    public boolean emitting() {
        return false;
    }

    @Override
    public List<SatVerb> verbs() {
        return List.of(
                SatVerb.action("image", 0, "ok", "run the camera over whatever is under the footprint"),
                SatVerb.action("stow", 0, "ok", "stop imaging"),
                SatVerb.value("survey", "text", "columns imaged, and how complete the tile under it is"));
    }

    @Override
    public String command(ServerLevel level, Satellite self, String verb, String[] args) {
        switch (verb) {
            case "image" -> {
                active = true;
                return "imaging";
            }
            case "stow" -> {
                active = false;
                return "stowed";
            }
            case "survey" -> {
                Vec3 at = self.position(level.getGameTime());
                SurveyTile tile = SurveyRaster.get(level).existing(self.netId(),
                        SurveyTile.tileOf(at.x), SurveyTile.tileOf(at.z));
                int done = tile == null ? 0 : tile.imaged();
                return String.format(Locale.ROOT,
                        "%s, %d column(s) imaged, %d skipped, %.0f-block strip, tile under it %d%% "
                                + "complete, %d tile(s) held",
                        active ? "imaging" : "stowed", columns, misses,
                        Math.min(self.swath(), OrbitalConfig.IMAGERY_PRELOAD_CHUNKS * 16.0) * 2.0,
                        done * 100 / (SurveyTile.PIXELS * SurveyTile.PIXELS),
                        SurveyRaster.get(level).tileCount(self.netId()));
            }
            default -> {
                return null;
            }
        }
    }

    @Override
    public void tick(ServerLevel level, Satellite self) {
        if (!active || !self.inContact()) {
            return;
        }
        if (!self.spendPower(OrbitalConfig.IMAGERY_POWER_PER_SLOW_TICK)) {
            self.report("imaging stopped: battery flat");
            return;
        }
        long now = level.getGameTime();
        Vec3 at = self.position(now);
        double swath = Math.min(self.swath(), OrbitalConfig.IMAGERY_PRELOAD_CHUNKS * 16.0);
        SurveyRaster raster = SurveyRaster.get(level);

        for (int i = 0; i < OrbitalConfig.IMAGERY_COLUMNS_PER_SLOW_TICK; i++) {
            cursor = (cursor + 1) & 0x3FFFFFFF;
            int span = (int) (swath * 2.0 / SurveyTile.BLOCKS_PER_PIXEL);
            if (span <= 0) {
                return;
            }
            int ox = (cursor % span) * SurveyTile.BLOCKS_PER_PIXEL;
            int oz = ((cursor / span) % span) * SurveyTile.BLOCKS_PER_PIXEL;
            int wx = Mth.floor(at.x - swath) + ox;
            int wz = Mth.floor(at.z - swath) + oz;
            double dx = wx - at.x;
            double dz = wz - at.z;
            if (dx * dx + dz * dz > swath * swath) {
                continue;
            }
            if (!sample(level, raster, self.netId(), wx, wz, now)) {
                misses++;
            } else {
                columns++;
            }
        }
        DetonationChunkGuard.hold(level, new Vec3(at.x, 128.0, at.z), OrbitalConfig.IMAGERY_PRELOAD_CHUNKS);
        raster.setDirty();
    }

    private boolean sample(ServerLevel level, SurveyRaster raster, long netId, int wx, int wz, long now) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(
                SectionPos.blockToSectionCoord(wx), SectionPos.blockToSectionCoord(wz));
        if (chunk == null) {
            return false;
        }
        int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wx, wz);
        BlockPos pos = new BlockPos(wx, top, wz);
        BlockState state = chunk.getBlockState(pos);
        MapColor colour = state.getMapColor(level, pos);
        if (colour == MapColor.NONE) {
            colour = MapColor.COLOR_LIGHT_GRAY;
        }
        int north = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wx,
                Math.max(wz - SurveyTile.BLOCKS_PER_PIXEL, wz - 8));
        int brightness = top > north ? 2 : top < north ? 0 : 1;
        byte packed = (byte) (colour.id * 4 + brightness);
        byte height = (byte) Mth.clamp(top + 64, 0, 255);

        int tileX = SurveyTile.tileOf(wx);
        int tileZ = SurveyTile.tileOf(wz);
        SurveyTile tile = raster.tile(netId, tileX, tileZ);
        int px = Math.floorMod(wx, SurveyTile.BLOCKS) / SurveyTile.BLOCKS_PER_PIXEL;
        int pz = Math.floorMod(wz, SurveyTile.BLOCKS) / SurveyTile.BLOCKS_PER_PIXEL;
        tile.set(px, pz, packed, height, now);
        return true;
    }

    @Override
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("Active", active);
        tag.putInt("Cursor", cursor);
        tag.putInt("Columns", columns);
        return tag;
    }

    @Override
    public void load(CompoundTag tag) {
        active = !tag.contains("Active") || tag.getBoolean("Active");
        cursor = tag.getInt("Cursor");
        columns = tag.getInt("Columns");
    }
}
