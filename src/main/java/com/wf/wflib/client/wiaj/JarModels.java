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

    private static final Map<BlockState, Rubble> SINGLES = new ConcurrentHashMap<>();

    /** 1x1x1 jar per state, cached: single-block debris, vs {@link #bake} for a multi-block scoop. */
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

    /** @return jar as model; null if it holds nothing that draws. */
    @Nullable
    public static Rubble bake(WorldInAJar jar) {
        List<BlockPos> filled = new ArrayList<>();
        float reach = 0.0f;

        for (int x = 0; x < jar.sizeX; x++) {
            for (int y = 0; y < jar.sizeY; y++) {
                for (int z = 0; z < jar.sizeZ; z++) {
                    BlockState state = jar.getBlockState(x, y, z);
                    if (state.isAir() || state.getRenderShape() != RenderShape.MODEL) {
                        continue;
                    }
                    filled.add(new BlockPos(x, y, z));
                    reach = Math.max(reach, Math.max(Math.abs(x + 0.5f - jar.sizeX / 2.0f),
                            Math.max(Math.abs(y + 0.5f - jar.sizeY / 2.0f), Math.abs(z + 0.5f - jar.sizeZ / 2.0f)))
                            + 0.5f);
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

        return new Rubble(model, Math.max(MIN_RADIUS, reach));
    }

    /** @param radius farthest filled block face from the centre on any axis: the sweep's extent */
    public record Rubble(Model model, float radius) {
    }
}
