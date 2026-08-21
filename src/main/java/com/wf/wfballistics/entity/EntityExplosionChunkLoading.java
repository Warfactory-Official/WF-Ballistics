package com.wf.wfballistics.entity;

import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.chunk.WFChunkValidation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.world.chunk.TicketController;

public abstract class EntityExplosionChunkLoading extends Entity {
    // Replaces the old ForgeChunkManager.forceChunk(modid, entity, ...) calls. Registered on the mod bus via
    // WFServerEvents.ModBusEvents#onRegisterTicketControllers (a TicketController must be registered before any
    // forceChunk call, or forceChunk throws IllegalArgumentException).
    //
    // The validation callback is not optional here. NeoForge skips controllers whose callback is null
    // (ForcedChunkManager#reinstatePersistentChunks filters on callback() != null) and reinstates their saved
    // tickets untouched, so an explosion entity that died with the server down would hold its chunk loaded
    // for the rest of the world's life.
    public static final TicketController CHUNK_TICKET = new TicketController(
            ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID, "explosion"),
            WFChunkValidation::validateTickets);

    ServerLevel serverLevel;
    ChunkPos chunkPos;

    public EntityExplosionChunkLoading(EntityType<?> pEntityType, Level pLevel) {
        super(pEntityType, pLevel);
        if (pLevel instanceof ServerLevel) {
            serverLevel = (ServerLevel) pLevel;
        }
    }

    public void init(ServerLevel level) {
        serverLevel = level;
    }

    public void loadChunk() {
        this.loadChunk(this.chunkPosition());
    }

    public void loadChunk(ChunkPos chunkPos) {
        this.loadChunk(chunkPos.x, chunkPos.z);
    }

    public void loadChunk(int x, int z) {
        if (this.chunkPos == null) {
            chunkPos = new ChunkPos(x, z);
            CHUNK_TICKET.forceChunk(serverLevel, this, x, z, true, true);
        }
    }

    public void clearChunkLoader() {
        if (!level().isClientSide && serverLevel != null && chunkPos != null) {
            // ticking must match the value loadChunk forced with. The flag picks which of two separate maps
            // the ticket lives in (ForcedChunkManager.TicketTracker#getTickets), so releasing with the wrong
            // one looks in the empty map, finds nothing, and leaves the chunk forced.
            CHUNK_TICKET.forceChunk(serverLevel, this, chunkPos.x, chunkPos.z, false, true);
            chunkPos = null;
        }
    }
}
