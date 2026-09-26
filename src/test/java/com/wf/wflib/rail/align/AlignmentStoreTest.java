package com.wf.wflib.rail.align;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A published line has to survive a restart, and the only part of that we own is the tag.
 *
 * <p>Worth testing here rather than by publishing one in a client and restarting it: a round trip
 * through the tag is the thing that can be wrong, and doing it by hand tests the whole game as well,
 * which is slower and tells you less about which half failed.</p>
 */
class AlignmentStoreTest {

    private static Alignment sample(UUID id, UUID owner) {
        return new Alignment(id, "north main", UUID.randomUUID(), owner, DesignClass.MAIN,
                0x66E0FF, List.of(
                        new AlignPoint(-1024.5, 512.25, 200.0),
                        new AlignPoint(0.0, 512.25, 400.0),
                        new AlignPoint(2048.0, -768.75, 0.0)));
    }

    private static AlignmentStore roundTrip(AlignmentStore store) {
        return AlignmentStore.load(store.save(new CompoundTag(), null));
    }

    @Test
    @DisplayName("a published line comes back with its geometry, colour, owner and class intact")
    void roundTrips() {
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        AlignmentStore store = new AlignmentStore();
        Alignment original = sample(id, owner);
        store.put(original);

        Alignment back = roundTrip(store).get(id);
        assertNotNull(back, "the line survived");
        assertEquals(original.name(), back.name());
        assertEquals(original.author(), back.author());
        assertEquals(owner, back.ownerFaction());
        assertEquals(DesignClass.MAIN, back.designClass());
        assertEquals(0x66E0FF, back.coreColour());
        assertEquals(original.points(), back.points(), "every PI, including its radius");
    }

    @Test
    @DisplayName("a line with no owning faction keeps having none, rather than gaining one")
    void unownedStaysUnowned() {
        UUID id = UUID.randomUUID();
        AlignmentStore store = new AlignmentStore();
        store.put(sample(id, null));

        Alignment back = roundTrip(store).get(id);
        assertNotNull(back);
        assertNull(back.ownerFaction(), "an owner appearing from nowhere would change who can see it");
    }

    @Test
    @DisplayName("several lines all come back, and a removed one does not")
    void manyLinesAndRemoval() {
        AlignmentStore store = new AlignmentStore();
        UUID keep = UUID.randomUUID();
        UUID drop = UUID.randomUUID();
        store.put(sample(keep, UUID.randomUUID()));
        store.put(sample(drop, UUID.randomUUID()));
        assertTrue(store.remove(drop));

        AlignmentStore back = roundTrip(store);
        assertEquals(1, back.all().size());
        assertNotNull(back.get(keep));
        assertNull(back.get(drop));
    }

    @Test
    @DisplayName("a design class removed between versions falls back rather than losing the line")
    void unknownDesignClassSurvives() {
        UUID id = UUID.randomUUID();
        AlignmentStore store = new AlignmentStore();
        store.put(sample(id, UUID.randomUUID()));
        CompoundTag tag = store.save(new CompoundTag(), null);
        tag.getList("alignments", 10).getCompound(0).putString("class", "MONORAIL");

        Alignment back = AlignmentStore.load(tag).get(id);
        assertNotNull(back, "the line is still there");
        assertEquals(DesignClass.BRANCH, back.designClass());
    }

    @Test
    @DisplayName("an empty store round trips to an empty store rather than throwing")
    void emptyStore() {
        assertEquals(0, roundTrip(new AlignmentStore()).all().size());
    }

    @Test
    @DisplayName("status, track laid and the revision all survive a restart")
    void lifecycleRoundTrips() {
        UUID id = UUID.randomUUID();
        UUID editor = UUID.randomUUID();
        AlignmentStore store = new AlignmentStore();
        Alignment route = new Alignment(id, "north main", UUID.randomUUID(), UUID.randomUUID(),
                DesignClass.MAIN, 0x66E0FF,
                List.of(new AlignPoint(0.0, 0.0, 200.0), new AlignPoint(2000.0, 0.0, 200.0)),
                RouteStatus.BUILDING,
                BuildProgress.NONE.with(0.0, 400.0).with(900.0, 1200.0),
                new Alignment.Revision(7, editor, 1234L));
        store.put(route);

        Alignment back = roundTrip(store).get(id);
        assertNotNull(back);
        assertEquals(RouteStatus.BUILDING, back.status());
        assertEquals(2, back.built().spans().size());
        assertEquals(700.0, back.built().builtLength(), 1e-9);
        assertEquals(7, back.revision().number());
        assertEquals(editor, back.revision().editor());
        assertEquals(1234L, back.revision().at());
    }

    @Test
    @DisplayName("a route saved before routes had a status comes back as a plan, not a draft")
    void olderSavesAreCommitments() {
        // Everything in an older save was published, which was a commitment. Reading those back as
        // drafts would take every faction's railway off every ally's map on the first launch.
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", UUID.randomUUID());
        tag.putString("name", "old line");
        tag.putString("class", DesignClass.BRANCH.name());
        Alignment back = AlignmentStore.load(listOf(tag)).all().iterator().next();
        assertEquals(RouteStatus.PLANNED, back.status());
        assertTrue(back.built().isEmpty());
    }

    /** One alignment tag wrapped as the store's save format. */
    private static CompoundTag listOf(CompoundTag alignment) {
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        list.add(alignment);
        CompoundTag root = new CompoundTag();
        root.put("alignments", list);
        return root;
    }

    @Test
    @DisplayName("routes are indexed by the faction that owns them")
    void indexedByFaction() {
        UUID ours = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        AlignmentStore store = new AlignmentStore();
        store.put(sample(UUID.randomUUID(), ours));
        store.put(sample(UUID.randomUUID(), ours));
        store.put(sample(UUID.randomUUID(), theirs));
        store.put(sample(UUID.randomUUID(), null));

        assertEquals(2, store.countFor(ours));
        assertEquals(1, store.countFor(theirs));
        assertEquals(1, store.countFor(null), "a route with no owner is not filed under everyone");
    }
}
