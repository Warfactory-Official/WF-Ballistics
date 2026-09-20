package com.wf.wflib.drone.cam;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import java.util.Optional;
import java.util.UUID;

/** A camera somebody has bound, in a form that survives the camera not being there. */
public record FeedTarget(Optional<UUID> drone, Optional<GlobalPos> camera) {

    public static final Codec<FeedTarget> CODEC = RecordCodecBuilder.create(i -> i.group(
                    UUIDUtil.CODEC.optionalFieldOf("drone").forGetter(FeedTarget::drone),
                    GlobalPos.CODEC.optionalFieldOf("camera").forGetter(FeedTarget::camera))
            .apply(i, FeedTarget::new));

    public static final StreamCodec<ByteBuf, FeedTarget> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC), FeedTarget::drone,
            ByteBufCodecs.optional(GlobalPos.STREAM_CODEC), FeedTarget::camera,
            FeedTarget::new);

    public static FeedTarget ofDrone(UUID id) {
        return new FeedTarget(Optional.of(id), Optional.empty());
    }

    public static FeedTarget ofCamera(Level level, BlockPos pos) {
        return new FeedTarget(Optional.empty(), Optional.of(GlobalPos.of(level.dimension(), pos.immutable())));
    }

    /** @return true if this names nothing at all, which a hand-edited NBT can produce and nothing else can. */
    public boolean empty() {
        return this.drone.isEmpty() && this.camera.isEmpty();
    }

    /**
     * @return the live feed id for this target in this level, or {@code 0} if there is not one right now.
     *      Zero is {@link CameraNet}'s own word for "no feed", so it passes straight through everything downstream.
     */
    public int resolve(ServerLevel level) {
        if (this.camera.isPresent()) {
            GlobalPos at = this.camera.get();
            if (!at.dimension().equals(level.dimension()) || StaticCameraFeeds.dead(at)) {
                return 0;
            }
            return StaticCameraFeeds.idFor(at);
        }
        if (this.drone.isPresent()) {
            Entity entity = level.getEntity(this.drone.get());
            if (entity != null && entity.isAlive() && CameraNet.specOf(entity) != null) {
                return entity.getId();
            }
        }
        return 0;
    }
}
