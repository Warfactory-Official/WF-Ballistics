package com.wf.wflib.rail.plan;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The named places a railway is dispatched from and to.
 *
 * <p>A station here is a name and a block position, and nothing else. That is not a placeholder for
 * something richer: the whole of what a station has to be for a railway to work is <b>somewhere a train
 * can be sent, that holds the materials</b>, and both of those are already answered elsewhere - the
 * first by {@link RailGraph}, which finds the junction nearest the position, and the second by
 * {@link com.wf.wflib.rail.supply.Depot}, which is any inventory near it. Keeping the station itself
 * this thin is what lets the construction machine that eventually owns one be a multiblock somebody
 * else wrote: it registers a name and a position and everything below it already works.</p>
 *
 * <p>Per dimension, like the routes, and saved, unlike the machines: a station outlives the train that
 * was dispatched from it, which is the difference between a railway and a demonstration.</p>
 */
public final class Stations extends SavedData {

    private static final String NAME = "wflib_rail_stations";

    /** Longest name accepted. Long enough for "Moor Junction Lower", short enough to read in chat. */
    public static final int MAX_NAME = 32;

    /** Most stations one dimension may hold. A continental network uses a few dozen. */
    public static final int MAX = 256;

    /** A named place on the network. */
    public record Station(String name, BlockPos at) {
    }

    /** Keyed by the name folded down, so "westport" and "Westport" are the same station. */
    private final Map<String, Station> stations = new LinkedHashMap<>();

    public static Stations of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(Stations::new, (tag, registries) -> load(tag)), NAME);
    }

    public static String key(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    public Station get(String name) {
        return this.stations.get(key(name));
    }

    public List<Station> all() {
        return List.copyOf(this.stations.values());
    }

    public int size() {
        return this.stations.size();
    }

    /** @return false when the dimension is already full of stations. */
    public boolean put(String name, BlockPos at) {
        String folded = key(name);
        if (folded.isEmpty() || (!this.stations.containsKey(folded) && this.stations.size() >= MAX)) {
            return false;
        }
        String trimmed = name.trim();
        this.stations.put(folded,
                new Station(trimmed.substring(0, Math.min(trimmed.length(), MAX_NAME)), at.immutable()));
        setDirty();
        return true;
    }

    public boolean remove(String name) {
        if (this.stations.remove(key(name)) == null) {
            return false;
        }
        setDirty();
        return true;
    }

    /** @return the station nearest a point within reach, or null. What a player standing on one means. */
    public Station nearest(BlockPos at, int reach) {
        Station best = null;
        double nearest = (double) reach * reach;
        for (Station station : this.stations.values()) {
            double away = station.at().distSqr(at);
            if (away <= nearest) {
                nearest = away;
                best = station;
            }
        }
        return best;
    }

    // -- saving -----------------------------------------------------------------------------------

    private static Stations load(CompoundTag tag) {
        Stations stations = new Stations();
        ListTag list = tag.getList("stations", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag one = list.getCompound(i);
            String name = one.getString("name");
            if (!name.isEmpty()) {
                stations.stations.put(key(name), new Station(name,
                        new BlockPos(one.getInt("x"), one.getInt("y"), one.getInt("z"))));
            }
        }
        return stations;
    }

    @Override
    public CompoundTag save(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Station station : this.stations.values()) {
            CompoundTag one = new CompoundTag();
            one.putString("name", station.name());
            one.putInt("x", station.at().getX());
            one.putInt("y", station.at().getY());
            one.putInt("z", station.at().getZ());
            list.add(one);
        }
        tag.put("stations", list);
        return tag;
    }

    /** Names, for a command's tab completion. */
    public List<String> names() {
        List<String> out = new ArrayList<>();
        for (Station station : this.stations.values()) {
            out.add(station.name());
        }
        return out;
    }
}
