package com.wf.wflib.magazine;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Variant ids; the math only compares them. */
class MagContentsTest {
    static final ResourceLocation BALL = ResourceLocation.parse("wflib:5.56x45/ball");
    static final ResourceLocation TRACER = ResourceLocation.parse("wflib:5.56x45/tracer");
    static final ResourceLocation AP = ResourceLocation.parse("wflib:5.56x45/ap");

    static MagContents mag(int links, Object... kindCount) {
        List<Run> runs = new ArrayList<>();
        for (int i = 0; i < kindCount.length; i += 2) {
            ResourceLocation h = (ResourceLocation) kindCount[i];
            runs.add(new Run(h, (Integer) kindCount[i + 1]));
        }
        return MagContents.of(runs, links);
    }

    /** Fired order, top first. */
    static List<ResourceLocation> drain(MagContents c) {
        List<ResourceLocation> out = new ArrayList<>();
        while (!c.isEmpty()) {
            out.add(c.top());
            c = c.pop();
        }
        return out;
    }

    @Test
    void loadOnTopMergesRuns() {
        MagContents c = MagContents.EMPTY.loadTop(BALL, 10, 30).loadTop(BALL, 5, 30);
        assertEquals(List.of(new Run(BALL, 15)), c.runs());
        c = c.loadTop(AP, 3, 30);
        assertEquals(List.of(new Run(BALL, 15), new Run(AP, 3)), c.runs());
        assertEquals(18, c.total());
        assertEquals(12, c.free(30));
    }

    @Test
    void popOrderIsTopFirst() {
        MagContents c = mag(1, BALL, 2, AP, 1, TRACER, 2);
        assertEquals(List.of(TRACER, TRACER, AP, BALL, BALL), drain(c));
        assertNull(MagContents.EMPTY.top());
        assertThrows(IllegalStateException.class, MagContents.EMPTY::pop);
    }

    @Test
    void popEmptiesRunThenMergesNothing() {
        MagContents c = mag(1, BALL, 1, AP, 1).pop();
        assertEquals(List.of(new Run(BALL, 1)), c.runs());
    }

    @Test
    void overfillRefusedNeverClamped() {
        MagContents c = MagContents.EMPTY.loadTop(BALL, 29, 30);
        assertThrows(IllegalArgumentException.class, () -> c.loadTop(BALL, 2, 30));
        assertThrows(IllegalArgumentException.class,
                () -> c.load(LoadPreset.of(BALL), 2, 30));
        assertEquals(30, c.loadTop(BALL, 1, 30).total());
        assertThrows(IllegalArgumentException.class, () -> c.loadTop(BALL, 0, 30));
    }

    @Test
    void canonicalFormEnforced() {
        assertThrows(IllegalArgumentException.class,
                () -> new MagContents(List.of(new Run(BALL, 1), new Run(BALL, 2)), 1));
        assertThrows(IllegalArgumentException.class, () -> new Run(BALL, 0));
        assertThrows(IllegalArgumentException.class, () -> new MagContents(List.of(), 0));
        assertEquals(mag(1, BALL, 3), MagContents.of(List.of(new Run(BALL, 1), new Run(BALL, 2)), 1));
    }

    @Test
    void valueEquality() {
        assertEquals(mag(1, BALL, 3, AP, 1), mag(1, BALL, 3, AP, 1));
        assertEquals(mag(1, BALL, 3, AP, 1).hashCode(), mag(1, BALL, 3, AP, 1).hashCode());
        assertFalse(mag(1, BALL, 3, AP, 1).equals(mag(1, AP, 1, BALL, 3)));
        assertFalse(mag(1, BALL, 3).equals(mag(2, BALL, 3)));
    }

    @Test
    void presetEveryNthCountsFromBottom() {
        LoadPreset p = new LoadPreset(BALL, new LoadPreset.Mix(TRACER, LoadPreset.Pattern.EVERY_NTH, 3));
        MagContents c = MagContents.EMPTY.load(p, 7, 30);
        // positions 1..7 bottom-up: T B B T B B T
        assertEquals(List.of(new Run(TRACER, 1), new Run(BALL, 2), new Run(TRACER, 1), new Run(BALL, 2),
                new Run(TRACER, 1)), c.runs());
        // continues the pattern above existing rounds: positions 8, 9, 10 => B B T
        MagContents more = c.load(p, 3, 30);
        assertEquals(List.of(BALL, BALL, TRACER), drain(more).subList(0, 3).reversed());
    }

    @Test
    void presetLastN() {
        LoadPreset p = new LoadPreset(BALL, new LoadPreset.Mix(TRACER, LoadPreset.Pattern.LAST_N, 3));
        MagContents c = MagContents.EMPTY.load(p, 30, 30);
        assertEquals(List.of(new Run(TRACER, 3), new Run(BALL, 27)), c.runs());
        List<ResourceLocation> fired = drain(c);
        assertEquals(List.of(TRACER, TRACER, TRACER), fired.subList(27, 30));
    }

    @Test
    void beltLinkInsertsAtBottom() {
        MagContents gun = mag(1, BALL, 60);
        MagContents belt = mag(1, TRACER, 100);
        MagContents linked = gun.link(belt, 100, 3);
        assertEquals(List.of(new Run(TRACER, 100), new Run(BALL, 60)), linked.runs());
        assertEquals(2, linked.links());
        assertEquals(40, linked.free(100));
        assertEquals(BALL, linked.top());
    }

    @Test
    void beltLinkRefusedAtMaxLinks() {
        MagContents full = mag(3, BALL, 300);
        assertFalse(full.canLink(mag(1, BALL, 100), 100, 3));
        assertThrows(IllegalArgumentException.class, () -> full.link(mag(1, BALL, 100), 100, 3));
        // spent links fell off the top: 150 rounds live in 2 links
        MagContents spent = mag(3, BALL, 150);
        assertTrue(spent.canLink(mag(1, AP, 100), 100, 3));
        assertEquals(3, spent.link(mag(1, AP, 100), 100, 3).links());
    }

    @Test
    void linkMergesAtBoundary() {
        assertEquals(List.of(new Run(BALL, 150)), mag(1, BALL, 50).link(mag(1, BALL, 100), 100, 3).runs());
    }

    @Test
    void ejectSplitsIntoCeilPiecesPartialOnTop() {
        MagContents belt = mag(3, AP, 30, BALL, 170, TRACER, 50);
        List<MagContents> pieces = belt.split(100);
        assertEquals(3, pieces.size());
        assertEquals(mag(1, AP, 30, BALL, 70), pieces.get(0));
        assertEquals(mag(1, BALL, 100), pieces.get(1));
        assertEquals(mag(1, TRACER, 50), pieces.get(2));
        assertEquals(List.of(), MagContents.EMPTY.split(100));
        assertEquals(1, mag(3, BALL, 100).split(100).size());
        assertEquals(2, mag(3, BALL, 101).split(100).size());
        assertEquals(mag(1, BALL, 1), mag(3, BALL, 101).split(100).get(1));
    }

    @Test
    void splitKeepsFireOrder() {
        MagContents belt = mag(3, AP, 30, BALL, 170, TRACER, 50);
        List<ResourceLocation> whole = drain(belt);
        List<ResourceLocation> pieces = new ArrayList<>();
        belt.split(100).reversed().forEach(p -> pieces.addAll(drain(p)));
        assertEquals(whole, pieces);
    }
}
