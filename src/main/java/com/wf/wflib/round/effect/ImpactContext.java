package com.wf.wflib.round.effect;

import com.norwood.ahf.part.HitboxPart;
import com.wf.wflib.kinetic.KineticPreset;
import com.wf.wflib.round.RoundEnd;
import com.wf.wflib.round.pen.BlockPen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * @param key      round key ({@code Long}) or the rocket entity, as {@code StrikeContext.key}
 * @param at       HIT: entry point; BLOCK: stop point; PIERCE_IN: entry; PIERCE_OUT: exit or stop; END: end point
 * @param velocity at {@code at}, blocks/tick (PIERCE_OUT: after the block)
 * @param block    BLOCK, PIERCE_*: the struck block
 * @param entity   HIT: the target
 * @param parts    HIT: AHF parts, entry order; null = none
 * @param pass     PIERCE_*
 * @param end      END
 */
public record ImpactContext(ServerLevel level, ImpactTrigger trigger, KineticPreset preset, Object key,
                            @Nullable Entity projectile, @Nullable Entity shooter, @Nullable UUID faction, Vec3 at,
                            Vec3 velocity, @Nullable BlockHitResult block, @Nullable Entity entity,
                            @Nullable List<HitboxPart> parts, @Nullable BlockPen.Pass pass, @Nullable RoundEnd end) {

    /** Kinetic energy at {@code at}, J. */
    public double energy() {
        return 0.5 * preset.mass() * velocity.lengthSqr() * 400.0;
    }

    /** Voxel on the open side of {@code at}: behind it along the flight, ahead for PIERCE_OUT. */
    public BlockPos openSide() {
        double len = velocity.length();
        if (len < 1.0e-9) {
            return BlockPos.containing(at);
        }
        double s = (trigger == ImpactTrigger.PIERCE_OUT ? 1.0e-3 : -1.0e-3) / len;
        return BlockPos.containing(at.add(velocity.scale(s)));
    }
}
