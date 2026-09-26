package com.wf.wflib.rail.align;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every route in one dimension, on the server, indexed by the faction that owns it.
 *
 * <p>Owned by the faction rather than by the player who drew it, which is what makes this a planning tool
 * for a team: every member sees the faction's routes and may change them, and a surveyor leaving does not
 * take the network with them. It is kept here rather than inside WarForge's own faction save because rail
 * data would then only exist in a pack that has WarForge, and because it would put wflib's types in
 * WarForge's serialisation. The faction is an index, and a route whose faction has gone falls back to
 * being its author's alone.</p>
 *
 * <p>Per dimension, because a route's coordinates mean something else in the Nether.</p>
 */
public final class AlignmentStore extends SavedData {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String NAME = "wflib_rail_alignments";

    /**
     * Most routes one faction may hold in one dimension.
     *
     * <p>A route is created by drawing two points on a map, which is cheap enough that a bored player
     * could fill the save file with them. A faction planning a continental network uses a few dozen.</p>
     */
    public static final int MAX_PER_FACTION = 64;

    private final Map<UUID, Alignment> alignments = new LinkedHashMap<>();

    public static AlignmentStore of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(AlignmentStore::new, (tag, registries) -> load(tag)), NAME);
    }

    public Collection<Alignment> all() {
        return List.copyOf(this.alignments.values());
    }

    public Alignment get(UUID id) {
        return this.alignments.get(id);
    }

    /** Every route belonging to one faction. An unowned faction id matches the routes with no owner. */
    public List<Alignment> byFaction(UUID faction) {
        List<Alignment> out = new ArrayList<>();
        for (Alignment alignment : this.alignments.values()) {
            if (faction == null ? alignment.ownerFaction() == null : faction.equals(alignment.ownerFaction())) {
                out.add(alignment);
            }
        }
        return out;
    }

    /** How many routes this faction already holds, for the cap. */
    public int countFor(UUID faction) {
        return byFaction(faction).size();
    }

    /**
     * How many routes one player has drawn that no faction owns.
     *
     * <p>The cap for a player with no faction. Counting the unowned routes as one bucket would make
     * every factionless player on the server share sixty-four between them, which in a pack without
     * WarForge is every player there is.</p>
     */
    public int countForAuthor(UUID author) {
        int count = 0;
        for (Alignment alignment : this.alignments.values()) {
            if (alignment.ownerFaction() == null && author != null && author.equals(alignment.author())) {
                count++;
            }
        }
        return count;
    }

    public void put(Alignment alignment) {
        this.alignments.put(alignment.id(), alignment);
        setDirty();
    }

    public boolean remove(UUID id) {
        boolean removed = this.alignments.remove(id) != null;
        if (removed) {
            setDirty();
        }
        return removed;
    }

    // -- persistence ------------------------------------------------------------------------------

    static AlignmentStore load(CompoundTag tag) {
        AlignmentStore store = new AlignmentStore();
        ListTag list = tag.getList("alignments", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            Alignment alignment = readAlignment(list.getCompound(i));
            if (alignment != null) {
                store.alignments.put(alignment.id(), alignment);
            }
        }
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Alignment alignment : this.alignments.values()) {
            list.add(writeAlignment(alignment));
        }
        tag.put("alignments", list);
        return tag;
    }

    private static CompoundTag writeAlignment(Alignment alignment) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", alignment.id());
        tag.putString("name", alignment.name());
        if (alignment.author() != null) {
            tag.putUUID("author", alignment.author());
        }
        if (alignment.ownerFaction() != null) {
            tag.putUUID("owner", alignment.ownerFaction());
        }
        tag.putString("class", alignment.designClass().name());
        tag.putInt("colour", alignment.coreColour());
        tag.putString("status", alignment.status().name());

        tag.putInt("rev", alignment.revision().number());
        if (alignment.revision().editor() != null) {
            tag.putUUID("revBy", alignment.revision().editor());
        }
        tag.putLong("revAt", alignment.revision().at());

        ListTag points = new ListTag();
        for (AlignPoint point : alignment.points()) {
            CompoundTag p = new CompoundTag();
            p.putDouble("x", point.x());
            p.putDouble("z", point.z());
            p.putDouble("r", point.radius());
            points.add(p);
        }
        tag.put("points", points);

        ListTag built = new ListTag();
        for (BuildProgress.Span span : alignment.built().spans()) {
            CompoundTag s = new CompoundTag();
            s.putDouble("a", span.from());
            s.putDouble("b", span.to());
            built.add(s);
        }
        tag.put("built", built);
        return tag;
    }

    private static Alignment readAlignment(CompoundTag tag) {
        if (!tag.hasUUID("id")) {
            return null;
        }
        DesignClass designClass;
        try {
            designClass = DesignClass.valueOf(tag.getString("class"));
        } catch (IllegalArgumentException e) {
            // A class removed or renamed between versions must not take the whole line with it.
            LOGGER.warn("[wflib] alignment {} has unknown design class '{}', defaulting to branch",
                    tag.getUUID("id"), tag.getString("class"));
            designClass = DesignClass.BRANCH;
        }
        List<AlignPoint> points = new ArrayList<>();
        ListTag list = tag.getList("points", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size() && i < Alignment.MAX_POINTS; i++) {
            CompoundTag p = list.getCompound(i);
            points.add(new AlignPoint(p.getDouble("x"), p.getDouble("z"), p.getDouble("r")));
        }

        List<BuildProgress.Span> built = new ArrayList<>();
        ListTag spans = tag.getList("built", Tag.TAG_COMPOUND);
        for (int i = 0; i < spans.size(); i++) {
            CompoundTag s = spans.getCompound(i);
            built.add(new BuildProgress.Span(s.getDouble("a"), s.getDouble("b")));
        }

        // A save written before routes had a status holds published lines, which were commitments:
        // reading them back as drafts would take them off every ally's map at once.
        RouteStatus status = tag.contains("status")
                ? RouteStatus.byName(tag.getString("status"), RouteStatus.PLANNED)
                : RouteStatus.PLANNED;

        Alignment.Revision revision = new Alignment.Revision(tag.getInt("rev"),
                tag.hasUUID("revBy") ? tag.getUUID("revBy") : null, tag.getLong("revAt"));

        return new Alignment(tag.getUUID("id"), tag.getString("name"),
                tag.hasUUID("author") ? tag.getUUID("author") : null,
                tag.hasUUID("owner") ? tag.getUUID("owner") : null,
                designClass, tag.getInt("colour"), points, status,
                new BuildProgress(built), revision);
    }
}
