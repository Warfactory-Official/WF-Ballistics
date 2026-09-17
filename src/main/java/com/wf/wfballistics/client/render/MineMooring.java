package com.wf.wfballistics.client.render;

import com.wf.gemrender.rope.GemRenderRopeTypes;
import com.wf.gemrender.rope.RopeInstance;
import com.wf.gemrender.rope.RopeModels;
import com.wf.wfballistics.mine.MineEntity;
import dev.engine_room.flywheel.api.instance.Instancer;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/** The chain a moored naval mine hangs on. */
public final class MineMooring {
    /** The chain, wrapped for a tube rather than for a block. */
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath("wfballistics", "textures/models/mines/mooring_chain.png");

    /**
     * A mooring chain hangs straight: the mine is buoyant and pulling up on it, so there is no slack in a real one
     * and the sway below is what keeps it from looking like a rod.
     */
    private static final float SAG = 0.0f;

    /** Half of vanilla's own chain, which is three sixteenths wide. Thinner reads as a hairline. */
    private static final float RADIUS = 0.09f;

    /** How far down to look for something to be moored to before giving up. */
    private static final int MAX_DROP = 64;

    /** How far the mine has to move before the sea floor under it is looked for again. */
    private static final double RESCAN_SQR = 0.25;

    private final Instancer<RopeInstance> instancer;

    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    @Nullable
    private RopeInstance rope;

    /** The anchor in world space, and the mine position it was found from. */
    private double anchorY;
    private double scannedX = Double.NaN;
    private double scannedY = Double.NaN;
    private double scannedZ = Double.NaN;
    private boolean anchored;

    public MineMooring(VisualizationContext context) {
        this.instancer = context.instancerProvider()
                .instancer(GemRenderRopeTypes.ROPE, RopeModels.cutout(TEXTURE));
    }

    /** Draws or withdraws the chain for where the mine is now. */
    public void update(MineEntity mine, Vec3i renderOrigin, float x, float y, float z, int light) {
        if (!mine.isMoored()) {
            release();
            return;
        }

        double worldY = y + renderOrigin.getY();
        if (!scan(mine, x + renderOrigin.getX(), worldY, z + renderOrigin.getZ())) {
            release();
            return;
        }

        float floor = (float) (anchorY - renderOrigin.getY());
        if (floor >= y) {
            release();
            return;
        }

        if (rope == null) {
            rope = instancer.createInstance();
            rope.radius(RADIUS)
                    // One texture repeat per block of chain, so a deep mooring is not one stretched link.
                    .tiling(Math.max(1.0f, y - floor))
                    .sway(0.06f, 0.55f, (float) ((Mth.floor(anchorY) * 31 + mine.getId()) % 64) * 0.1f, 0.5f);
        }

        rope.between(x, y, z, x, floor, z)
                .sag(SAG)
                .tiling(Math.max(1.0f, y - floor))
                .litUniformly(light)
                // The sea floor is darker than the surface; the chain should not be uniformly bright.
                .lightB(floorLight(mine.level(), x + renderOrigin.getX(), anchorY,
                        z + renderOrigin.getZ()))
                .refresh()
                .setChanged();
    }

    /** Finds the first thing under the mine that is neither air nor fluid. */
    private boolean scan(MineEntity mine, double worldX, double worldY, double worldZ) {
        double moved = anchored
                ? Mth.square(worldX - scannedX) + Mth.square(worldY - scannedY) + Mth.square(worldZ - scannedZ)
                : Double.MAX_VALUE;
        if (moved < RESCAN_SQR) {
            return true;
        }

        scannedX = worldX;
        scannedY = worldY;
        scannedZ = worldZ;
        anchored = false;

        Level level = mine.level();
        cursor.set(Mth.floor(worldX), Mth.floor(worldY), Mth.floor(worldZ));

        for (int step = 0; step < MAX_DROP; step++) {
            cursor.move(0, -1, 0);
            if (level.isOutsideBuildHeight(cursor)) {
                return false;
            }
            if (!level.getFluidState(cursor).isEmpty() || level.getBlockState(cursor).isAir()) {
                continue;
            }
            // The top face of the block it rests on, not the block's own corner.
            anchorY = cursor.getY() + 1.0;
            anchored = true;
            return true;
        }
        return false;
    }

    private int floorLight(Level level, double worldX, double worldY, double worldZ) {
        cursor.set(Mth.floor(worldX), Mth.floor(worldY), Mth.floor(worldZ));
        return LevelRenderer.getLightColor(level, cursor);
    }

    private void release() {
        if (rope != null) {
            rope.delete();
            rope = null;
        }
    }

    public void delete() {
        release();
    }
}
