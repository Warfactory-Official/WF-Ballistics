package com.wf.wfballistics.orbital;

import com.wf.wfballistics.aef.ExplosionAEF;
import com.wf.wfballistics.aef.standard.BlockAllocatorStandard;
import com.wf.wfballistics.aef.standard.BlockProcessorStandard;
import com.wf.wfballistics.aef.standard.EntityProcessorCross;
import com.wf.wfballistics.aef.standard.PlayerProcessorStandard;
import com.wf.wfballistics.block.ModBlocks;
import com.wf.wfballistics.block.entity.CargoShuttleBlockEntity;
import com.wf.wfballistics.chunk.DetonationChunkGuard;
import com.wf.wfballistics.fx.ExplosionCreator;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class OrbitalImpact {

    static final int PRELOAD_CHUNK_RADIUS = 2;
    private static final float ROD_SIZE = 11.0f;
    private static final float CAPSULE_SIZE = 4.0f;
    private static final float BALLISTIC_LOSS = 0.25f;

    private OrbitalImpact() {
    }

    public static void apply(ServerLevel level, double x, double z, ResourceLocation effect,
                             @Nullable UUID faction, @Nullable UUID shuttle) {
        DetonationChunkGuard.hold(level, new Vec3(x, 128.0, z), PRELOAD_CHUNK_RADIUS);
        double y = OrbitalDrop.groundAt(level, x, z);
        if (OrbitalDrop.KINETIC_ROD.equals(effect)) {
            rod(level, x, y, z, faction);
        } else {
            land(level, x, y, z, faction, shuttle, OrbitalDrop.CARGO_LANDING_SOFT.equals(effect));
        }
    }

    private static void rod(ServerLevel level, double x, double y, double z, @Nullable UUID faction) {
        new ExplosionAEF(level, x, y + 2.0, z, ROD_SIZE)
                .makeShapedCharge(new Vec3(0.0, -1.0, 0.0))
                .igniterFaction(faction)
                .explode();
        ExplosionCreator.composeEffectSmall(level, x, y + 1.0, z);
    }

    private static void land(ServerLevel level, double x, double y, double z, @Nullable UUID faction,
                             @Nullable UUID shuttle, boolean soft) {
        List<ItemStack> manifest = shuttle == null
                ? List.of() : new ArrayList<>(CargoStore.get(level).take(shuttle));
        if (!soft) {
            ExplosionAEF crater = new ExplosionAEF(level, x, y + 1.0, z, CAPSULE_SIZE);
            crater.setBlockAllocator(new BlockAllocatorStandard(12));
            crater.setBlockProcessor(new BlockProcessorStandard().setNoDrop());
            crater.setEntityProcessor(new EntityProcessorCross());
            crater.setPlayerProcessor(new PlayerProcessorStandard());
            crater.igniterFaction(faction);
            crater.explode();
            manifest.removeIf(stack -> level.getRandom().nextFloat() < BALLISTIC_LOSS);
        }
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
        BlockPos at = new BlockPos(bx, Math.max(level.getMinBuildHeight() + 1, top), bz);
        level.setBlockAndUpdate(at, ModBlocks.CARGO_SHUTTLE.get().defaultBlockState());
        if (level.getBlockEntity(at) instanceof CargoShuttleBlockEntity block) {
            block.fill(manifest);
        }
        ExplosionCreator.composeEffectSmall(level, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5);
    }
}
