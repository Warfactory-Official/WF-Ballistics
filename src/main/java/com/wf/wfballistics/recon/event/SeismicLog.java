package com.wf.wfballistics.recon.event;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A hub's record of what it has heard go off. */
public final class SeismicLog {

    /** Events a hub keeps. Enough to cover an evening's worth of activity without becoming a data structure. */
    public static final int DEFAULT_CAPACITY = 100;
    /** Ceiling on any capacity, wherever it came from. */
    public static final int MAX_CAPACITY = 4096;

    /** Insertion-ordered, so eviction is "drop the front" and reading newest-first is one reversal. */
    private final Map<Long, SeismicFix> entries = new LinkedHashMap<>();
    private int capacity;

    public SeismicLog() {
        this(DEFAULT_CAPACITY);
    }

    public SeismicLog(int capacity) {
        this.capacity = clampCapacity(capacity);
    }

    public int capacity() {
        return capacity;
    }

    /** Resize, trimming immediately if the new bound is smaller. */
    public void setCapacity(int capacity) {
        this.capacity = clampCapacity(capacity);
        trim();
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * File one fix.
     *
     * @return true if this was an event the log had not heard before, so a caller can announce a new bang
     *      without announcing the second read of the same one.
     */
    public boolean record(SeismicFix fix) {
        SeismicFix held = entries.get(fix.eventId());
        if (held == null) {
            entries.put(fix.eventId(), fix);
            trim();
            return true;
        }
        if (better(fix, held)) {
            entries.put(fix.eventId(), fix);
        }
        return false;
    }

    /**
     * @return every event held, newest first: the order a readout wants and the reverse of the order stored.
     */
    public List<SeismicFix> recent() {
        List<SeismicFix> out = new ArrayList<>(entries.values());
        java.util.Collections.reverse(out);
        return out;
    }

    /**
     * @return the {@code count} most recent events, newest first.
     */
    public List<SeismicFix> recent(int count) {
        List<SeismicFix> all = recent();
        return all.size() <= count ? all : all.subList(0, count);
    }

    /**
     * @return the highest event id held, so a puller can ask a network only for what it has not seen. Zero on
     *      an empty log, which is below every real id: those start at one.
     */
    public long lastEventId() {
        long last = 0L;
        for (Long id : entries.keySet()) {
            last = Math.max(last, id);
        }
        return last;
    }

    public void clear() {
        entries.clear();
    }

    public ListTag save() {
        ListTag list = new ListTag();
        for (SeismicFix fix : entries.values()) {
            list.add(fix.save());
        }
        return list;
    }

    /** Replace the contents from a saved list. */
    public void load(ListTag list) {
        entries.clear();
        for (int i = 0; i < list.size(); i++) {
            Tag tag = list.get(i);
            if (tag instanceof CompoundTag compound) {
                SeismicFix fix = SeismicFix.load(compound);
                entries.put(fix.eventId(), fix);
            }
        }
        trim();
    }

    /**
     * @return true if the newcomer is a better answer about the same event. More stations beats fewer
     *      outright (a second bearing is worth more than any amount of luck on one), and a tie goes to the
     *      tighter fix.
     */
    private static boolean better(SeismicFix fresh, SeismicFix held) {
        if (fresh.stations() != held.stations()) {
            return fresh.stations() > held.stations();
        }
        return fresh.positionError() < held.positionError();
    }

    private void trim() {
        Iterator<Map.Entry<Long, SeismicFix>> it = entries.entrySet().iterator();
        while (entries.size() > capacity && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    private static int clampCapacity(int requested) {
        return Math.max(1, Math.min(MAX_CAPACITY, requested));
    }
}
