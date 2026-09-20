package com.wf.wflib.recon.source;

import com.wf.wflib.MissileEntity;
import com.wf.wflib.drone.DroneEntity;
import com.wf.wflib.entity.glyphid.EntityGlyphid;
import com.wf.wflib.entity.glyphid.GlyphidTasks;
import com.wf.wflib.recon.ContactClass;
import com.wf.wflib.recon.CountermeasureRegistry;
import com.wf.wflib.recon.EmconState;
import com.wf.wflib.recon.Signature;
import com.wf.wflib.recon.SignatureProvider;
import com.wf.wflib.recon.SignatureRegistry;
import com.wf.wflib.recon.SourceIds;
import com.wf.wflib.recon.TargetSink;
import com.wf.wflib.recon.TargetSource;
import com.wf.wflib.recon.snapshot.TargetSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/** Targets that are loaded entities. */
public final class EntityTargetSource implements TargetSource {

    /**
     * Blocks below the surface at which something stops being a radar target and becomes a seismic one.
     */
    private static final int BURIED_DEPTH = 2;

    @Override
    public void collect(ServerLevel level, AABB volume, TargetSink sink) {
        List<MissileEntity> missiles = level.getEntitiesOfClass(MissileEntity.class, volume, e -> !e.isRemoved());
        for (int i = 0; i < missiles.size(); i++) {
            sink.accept(missile(missiles.get(i)));
        }
        List<DroneEntity> drones = level.getEntitiesOfClass(DroneEntity.class, volume, e -> !e.isRemoved());
        for (int i = 0; i < drones.size(); i++) {
            sink.accept(drone(drones.get(i)));
        }
        List<EntityGlyphid> glyphids = level.getEntitiesOfClass(EntityGlyphid.class, volume, Entity::isAlive);
        for (int i = 0; i < glyphids.size(); i++) {
            sink.accept(glyphid(level, glyphids.get(i)));
        }
        for (ServerPlayer player : level.players()) {
            if (!player.isSpectator() && !player.isRemoved() && volume.contains(player.position())) {
                sink.accept(player(player));
            }
        }
    }

    private static TargetSnapshot missile(MissileEntity missile) {
        UUID team = missile.getTeamId();
        return snapshot(missile, ContactClass.MISSILE, SignatureRegistry.of(missile), EmconState.ACTIVE,
                team == null ? 0L : SourceIds.of(team), false);
    }

    private static TargetSnapshot drone(DroneEntity drone) {
        // A drone flying its own program needs no link, so it is dark; one being flown is talking.
        EmconState emcon = drone.getProgram().isEmpty() ? EmconState.ACTIVE : EmconState.SILENT;
        return snapshot(drone, ContactClass.DRONE, SignatureRegistry.of(drone), emcon, 0L, false);
    }

    private static TargetSnapshot glyphid(ServerLevel level, EntityGlyphid glyphid) {
        return snapshot(glyphid, ContactClass.GLYPHID, SignatureRegistry.of(glyphid), EmconState.ACTIVE,
                0L, buried(level, glyphid));
    }

    private static TargetSnapshot player(ServerPlayer player) {
        return snapshot(player, ContactClass.PLAYER, SignatureRegistry.of(player), EmconState.ACTIVE, 0L, false);
    }

    /**
     * Velocity from the last tick's displacement rather than {@code getDeltaMovement}, which for a pathing mob is
     * the impulse it was given and not the distance it went.
     */
    private static TargetSnapshot snapshot(Entity entity, ContactClass kind, Signature signature,
                                           EmconState emcon, long iffCode, boolean buried) {
        boolean wet = inWater(entity);
        return new TargetSnapshot(SourceIds.of(entity), kind,
                entity.getX(), entity.getY() + entity.getBbHeight() * 0.5, entity.getZ(),
                entity.getX() - entity.xo, entity.getY() - entity.yo, entity.getZ() - entity.zo,
                signature, CountermeasureRegistry.of(entity), emcon, iffCode,
                buried || (wet && submerged(entity)), wet);
    }

    /**
     * The fluid state rather than {@code Entity.isInWater}, which is set in {@code baseTick} and so is stale
     * for anything moving under its own guidance: a torpedo never reports itself wet.
     */
    private static boolean inWater(Entity entity) {
        return entity.level().getFluidState(entity.blockPosition()).is(FluidTags.WATER);
    }

    /** Under, not merely in: this is what makes radar and thermal blind, and both already gate on it. */
    private static boolean submerged(Entity entity) {
        return entity.level().getFluidState(
                BlockPos.containing(entity.getX(), entity.getEyeY(), entity.getZ())).is(FluidTags.WATER);
    }

    /**
     * @return true if this glyphid is far enough under the surface that radar has nothing to work with.
     */
    private static boolean buried(ServerLevel level, EntityGlyphid glyphid) {
        if (glyphid.getCurrentTask() != GlyphidTasks.TASK_DIG) {
            return false;
        }
        int x = glyphid.getBlockX();
        int z = glyphid.getBlockZ();
        LevelChunk chunk = level.getChunkSource().getChunkNow(
                SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
        return chunk != null && glyphid.getBlockY() < chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - BURIED_DEPTH;
    }

    /**
     * The signatures this mod's own entities present, at the bottom of the chain so anything registered later takes
     * precedence.
     */
    public static final class BuiltIn implements SignatureProvider {

        @Override
        public int priority() {
            return 0;
        }

        @Override
        @Nullable
        public Signature of(Entity entity) {
            if (entity instanceof MissileEntity missile) {
                return ReconSignatures.missile(missile.getRcs(), missile.isSubmergedMedium());
            }
            if (entity instanceof DroneEntity) {
                return ReconSignatures.drone();
            }
            if (entity instanceof EntityGlyphid glyphid) {
                return ReconSignatures.glyphid(glyphid);
            }
            if (entity instanceof Player) {
                return ReconSignatures.player();
            }
            return null;
        }
    }
}
