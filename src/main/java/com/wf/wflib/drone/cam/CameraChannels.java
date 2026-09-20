package com.wf.wflib.drone.cam;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** An ordered set of bound cameras and which one is on screen. */
public record CameraChannels(List<FeedTarget> targets, int selected) {

    /** Decode limit for the wire. Above any sane configured cap; not the cap itself. */
    public static final int HARD_MAX = 32;

    public static final CameraChannels EMPTY = new CameraChannels(List.of(), 0);

    public static final Codec<CameraChannels> CODEC = RecordCodecBuilder.create(i -> i.group(
                    FeedTarget.CODEC.listOf().fieldOf("channels").forGetter(CameraChannels::targets),
                    Codec.INT.optionalFieldOf("selected", 0).forGetter(CameraChannels::selected))
            .apply(i, CameraChannels::new));

    public static final StreamCodec<ByteBuf, CameraChannels> STREAM_CODEC = StreamCodec.composite(
            FeedTarget.STREAM_CODEC.apply(ByteBufCodecs.list(HARD_MAX)), CameraChannels::targets,
            ByteBufCodecs.VAR_INT, CameraChannels::selected,
            CameraChannels::new);

    public CameraChannels {
        targets = List.copyOf(targets);
        selected = targets.isEmpty() ? 0 : Math.floorMod(selected, targets.size());
    }

    public int size() {
        return this.targets.size();
    }

    public boolean isEmpty() {
        return this.targets.isEmpty();
    }

    public boolean has(FeedTarget target) {
        return this.targets.contains(target);
    }

    public boolean full(int cap) {
        return this.targets.size() >= Math.min(cap, HARD_MAX);
    }

    @Nullable
    public FeedTarget current() {
        return this.targets.isEmpty() ? null : this.targets.get(this.selected);
    }

    /**
     * @return this panel with {@code target} bound, selected. Unchanged if it is already bound or there is no
     *      room: both of which the caller distinguishes with {@link #has} and {@link #full} so it can say which.
     */
    public CameraChannels with(FeedTarget target, int cap) {
        if (target.empty() || has(target) || full(cap)) {
            return this;
        }
        List<FeedTarget> next = new ArrayList<>(this.targets);
        next.add(target);
        return new CameraChannels(next, next.size() - 1);
    }

    public CameraChannels without(int index) {
        if (index < 0 || index >= this.targets.size()) {
            return this;
        }
        List<FeedTarget> next = new ArrayList<>(this.targets);
        next.remove(index);
        return new CameraChannels(next, Math.min(this.selected, Math.max(0, next.size() - 1)));
    }

    public CameraChannels select(int index) {
        return this.targets.isEmpty() ? this : new CameraChannels(this.targets, index);
    }

    public CameraChannels cycle(int by) {
        return this.targets.isEmpty() ? this : new CameraChannels(this.targets, this.selected + by);
    }

    /** @return the feed id of the selected channel, or {@code 0} if there is nothing on it right now. */
    public int selectedFeed(ServerLevel level) {
        FeedTarget target = current();
        return target == null ? 0 : target.resolve(level);
    }

    /**
     * @return a feed id per channel, in order, {@code 0} where a camera is currently unreachable. This is
     *      what gets synced for display: the client needs to label a strip of channels and mark the dead ones,
     *      and it has no business knowing where the cameras are.
     */
    public int[] resolveAll(ServerLevel level) {
        int[] out = new int[this.targets.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = this.targets.get(i).resolve(level);
        }
        return out;
    }
}
