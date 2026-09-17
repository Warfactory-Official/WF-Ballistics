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
            CHUNK_TICKET.forceChunk(serverLevel, this, chunkPos.x, chunkPos.z, false, true);
            chunkPos = null;
        }
    }
}
