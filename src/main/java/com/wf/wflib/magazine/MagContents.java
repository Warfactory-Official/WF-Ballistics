package com.wf.wflib.magazine;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Feed contents, runs bottom -> top; top fires first. Canonical: adjacent runs differ in round.
 * Capacity = {@code perLink * links}; overfill throws, never clamps.
 */
public record MagContents(List<Run> runs, int links) {
    public static final MagContents EMPTY = new MagContents(List.of(), 1);

    public static final Codec<MagContents> CODEC = RecordCodecBuilder.create(i -> i.group(
            Run.CODEC.listOf().fieldOf("runs").forGetter(MagContents::runs),
            Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("links", 1).forGetter(MagContents::links)
    ).apply(i, MagContents::of));

    public static final StreamCodec<RegistryFriendlyByteBuf, MagContents> STREAM_CODEC = StreamCodec.composite(
            Run.STREAM_CODEC.apply(ByteBufCodecs.list()), MagContents::runs, ByteBufCodecs.VAR_INT,
            MagContents::links, MagContents::of);

    public MagContents {
        if (links < 1) {
            throw new IllegalArgumentException("links " + links + " < 1");
        }
        runs = List.copyOf(runs);
        for (int i = 1; i < runs.size(); i++) {
            if (runs.get(i - 1).round().equals(runs.get(i).round())) {
                throw new IllegalArgumentException("adjacent runs of " + runs.get(i).round());
            }
        }
    }

    /** Merges adjacent equal runs. */
    public static MagContents of(List<Run> runs, int links) {
        List<Run> out = new ArrayList<>(runs.size());
        runs.forEach(r -> append(out, r.round(), r.count()));
        return new MagContents(out, links);
    }

    private static void append(List<Run> out, ResourceLocation round, int n) {
        int last = out.size() - 1;
        if (last >= 0 && out.get(last).round().equals(round)) {
            out.set(last, new Run(round, out.get(last).count() + n));
        } else {
            out.add(new Run(round, n));
        }
    }

    public int total() {
        int n = 0;
        for (Run r : runs) {
            n += r.count();
        }
        return n;
    }

    public boolean isEmpty() {
        return runs.isEmpty();
    }

    public int capacity(int perLink) {
        return perLink * links;
    }

    public int free(int perLink) {
        return capacity(perLink) - total();
    }

    /** Round {@code i} from the bottom (0 = fired last). */
    public ResourceLocation at(int i) {
        for (Run r : runs) {
            if (i < r.count()) {
                return r.round();
            }
            i -= r.count();
        }
        throw new IndexOutOfBoundsException(i + " of " + total());
    }

    /** Next round to fire; null when empty. */
    @Nullable
    public ResourceLocation top() {
        return runs.isEmpty() ? null : runs.getLast().round();
    }

    public MagContents pop() {
        if (runs.isEmpty()) {
            throw new IllegalStateException("pop from empty feed");
        }
        List<Run> out = new ArrayList<>(runs);
        Run top = out.removeLast();
        if (top.count() > 1) {
            out.add(new Run(top.round(), top.count() - 1));
        }
        return new MagContents(out, links);
    }

    public MagContents loadTop(ResourceLocation round, int n, int perLink) {
        if (n < 1 || n > free(perLink)) {
            throw new IllegalArgumentException("load " + n + " into " + free(perLink) + " free");
        }
        List<Run> out = new ArrayList<>(runs);
        append(out, round, n);
        return new MagContents(out, links);
    }

    /** {@code n} rounds on top, kinds by {@code preset} at bottom-up positions {@code total()+1 ..}. */
    public MagContents load(LoadPreset preset, int n, int perLink) {
        if (n < 1 || n > free(perLink)) {
            throw new IllegalArgumentException("load " + n + " into " + free(perLink) + " free");
        }
        List<Run> out = new ArrayList<>(runs);
        int base = total();
        for (int p = base + 1; p <= base + n; p++) {
            append(out, preset.roundAt(p), 1);
        }
        return new MagContents(out, links);
    }

    /** Links still holding rounds; spent links fell off the top. */
    public int liveLinks(int perLink) {
        return (total() + perLink - 1) / perLink;
    }

    public boolean canLink(MagContents belt, int perLink, int maxLinks) {
        return liveLinks(perLink) + belt.links <= maxLinks;
    }

    /** {@code belt} inserted at the bottom; links = live + belt's. */
    public MagContents link(MagContents belt, int perLink, int maxLinks) {
        if (!canLink(belt, perLink, maxLinks)) {
            throw new IllegalArgumentException("link " + belt.links + " onto " + liveLinks(perLink) + " live of "
                    + maxLinks);
        }
        List<Run> out = new ArrayList<>(belt.runs);
        runs.forEach(r -> append(out, r.round(), r.count()));
        return new MagContents(out, Math.max(1, liveLinks(perLink) + belt.links));
    }

    /** {@code ceil(total / perLink)} single-link pieces, bottom -> top; the top piece is the partial one. */
    public List<MagContents> split(int perLink) {
        List<MagContents> pieces = new ArrayList<>();
        List<Run> cur = new ArrayList<>();
        int room = perLink;
        for (Run r : runs) {
            int left = r.count();
            while (left > 0) {
                int take = Math.min(left, room);
                append(cur, r.round(), take);
                left -= take;
                room -= take;
                if (room == 0) {
                    pieces.add(new MagContents(cur, 1));
                    cur = new ArrayList<>();
                    room = perLink;
                }
            }
        }
        if (!cur.isEmpty()) {
            pieces.add(new MagContents(cur, 1));
        }
        return pieces;
    }
}
