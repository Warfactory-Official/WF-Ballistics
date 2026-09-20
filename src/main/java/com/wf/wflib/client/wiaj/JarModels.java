package com.wf.wflib.client.wiaj;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.lib.model.baked.BlockModelBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** A {@link WorldInAJar} as a Flywheel model. */
public final class JarModels {

    /** Nothing smaller than this rests convincingly on a surface, whatever the jar actually holds. */
    private static final float MIN_RADIUS = 0.5f;

    private JarModels() {
    }

    /**
     * @return the jar as a model, or {@code null} if it holds nothing that draws
     */
    /** One block, as a jar of one: what a mining charge throws, as opposed to a scoop of terrain. */
    private static final Map<BlockState, Rubble> SINGLES = new ConcurrentHashMap<>();

    /** A single block as a model, cached per state. */
    @Nullable
    public static Rubble ofBlock(BlockState state) {
        if (state == null || state.isAir()) {
            return null;
        }
        return SINGLES.computeIfAbsent(state, one -> {
            WorldInAJar jar = new WorldInAJar(1, 1, 1);
            jar.setBlock(0, 0, 0, one);
            return bake(jar);
        });
    }

    @Nullable
    public static Rubble bake(WorldInAJar jar) {
        List<BlockPos> filled = new ArrayList<>();
        float centreY = jar.sizeY / 2.0f;
        double spread = 0.0;

        for (int x = 0; x < jar.sizeX; x++) {
            for (int y = 0; y < jar.sizeY; y++) {
                for (int z = 0; z < jar.sizeZ; z++) {
                    BlockState state = jar.getBlockState(x, y, z);
                    if (state.isAir() || state.getRenderShape() != RenderShape.MODEL) {
                        continue;
                    }
                    filled.add(new BlockPos(x, y, z));
                    double below = y + 0.5f - centreY;
                    spread += below * below;
                }
            }
        }

        if (filled.isEmpty()) {
            return null;
        }

        PoseStack pose = new PoseStack();
        pose.translate(-jar.sizeX / 2.0f, -jar.sizeY / 2.0f, -jar.sizeZ / 2.0f);

        Model model = new BlockModelBuilder(jar, filled).poseStack(pose)
                .build();

        float radius = Math.max(MIN_RADIUS, (float) Math.sqrt(spread / filled.size()));
        return new Rubble(model, radius);
    }

    /**
     * @param radius how far the model reaches below its own centre, which is how far short of a surface the
     *      particle has to stop to look like it is lying on it
     */
    public record Rubble(Model model, float radius) {
    }
}
