package com.wf.wfballistics.recon;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** Who owns a place, or a thing. */
public interface OwnerSource {

    /**
     * Higher runs first. The built-in WarForge source sits at zero.
     */
    int priority();

    /**
     * @return who holds this ground, or null for "not mine, ask the next". Null from every source means the
     *      ground is unclaimed, which is a real answer rather than a failure.
     */
    @Nullable
    UUID owningAt(ServerLevel level, BlockPos pos);

    /**
     * @return whose side this entity is on, or null to defer.
     */
    @Nullable
    default UUID owningEntity(Entity entity) {
        return null;
    }
}
