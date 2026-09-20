package com.wf.wflib.orbital;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class OrbitalRegistry extends SavedData {

    public static final String NAME = "wflib_orbital";

    private final Map<Long, Satellite> birds = new LinkedHashMap<>();
    private final Map<Long, Map<Long, TrackedObject>> catalogues = new LinkedHashMap<>();
    private long nextId = 1L;

    public static OrbitalRegistry get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(OrbitalRegistry::new, (tag, registries) -> load(tag)), NAME);
    }

    public Satellite add(SatSpec spec, OrbitElements elements) {
        Satellite sat = new Satellite(nextId++, spec, elements);
        birds.put(sat.rawId(), sat);
        setDirty();
        return sat;
    }

    @Nullable
    public Satellite byId(SatId id) {
        return birds.get(id.value());
    }

    @Nullable
    public Satellite byCallsign(String callsign) {
        String want = callsign.toLowerCase(Locale.ROOT);
        for (Satellite sat : birds.values()) {
            if (sat.callsign().toLowerCase(Locale.ROOT).equals(want)) {
                return sat;
            }
        }
        return null;
    }

    public Collection<Satellite> all() {
        return Collections.unmodifiableCollection(birds.values());
    }

    public List<Satellite> onNet(long netId) {
        List<Satellite> out = new ArrayList<>();
        for (Satellite sat : birds.values()) {
            if (sat.netId() == netId) {
                out.add(sat);
            }
        }
        return out;
    }

    public boolean isEmpty() {
        return birds.isEmpty();
    }

    public int size() {
        return birds.size();
    }

    public boolean remove(SatId id) {
        if (birds.remove(id.value()) == null) {
            return false;
        }
        setDirty();
        return true;
    }

    public void observe(long observerNet, Satellite target, long gameTime) {
        Map<Long, TrackedObject> book = catalogues.computeIfAbsent(observerNet, k -> new LinkedHashMap<>());
        long designator = designator(target);
        TrackedObject held = book.remove(designator);
        int seen = held == null ? 1 : held.observations() + 1;
        book.put(designator, new TrackedObject(designator, target.elements(), gameTime, seen,
                target.payload().emitting(), target.parked(), target.parkX(), target.parkZ()));
        Iterator<Map.Entry<Long, TrackedObject>> it = book.entrySet().iterator();
        while (book.size() > OrbitalConfig.CATALOGUE_CAPACITY && it.hasNext()) {
            it.next();
            it.remove();
        }
        setDirty();
    }

    public List<TrackedObject> catalogue(long netId) {
        Map<Long, TrackedObject> book = catalogues.get(netId);
        return book == null ? List.of() : new ArrayList<>(book.values());
    }

    private static long designator(Satellite target) {
        long mixed = target.rawId() * 0x9E3779B97F4A7C15L;
        return (mixed >>> 24) & 0xFFFFFFL;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Satellite sat : birds.values()) {
            list.add(sat.save());
        }
        tag.put("Satellites", list);
        tag.putLong("NextId", nextId);

        ListTag books = new ListTag();
        for (Map.Entry<Long, Map<Long, TrackedObject>> entry : catalogues.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            CompoundTag book = new CompoundTag();
            book.putLong("Net", entry.getKey());
            ListTag objects = new ListTag();
            for (TrackedObject object : entry.getValue().values()) {
                objects.add(object.save());
            }
            book.put("Objects", objects);
            books.add(book);
        }
        tag.put("Catalogues", books);
        return tag;
    }

    private static OrbitalRegistry load(CompoundTag tag) {
        OrbitalRegistry registry = new OrbitalRegistry();
        ListTag list = tag.getList("Satellites", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            Satellite sat = Satellite.load(list.getCompound(i));
            registry.birds.put(sat.rawId(), sat);
        }
        registry.nextId = Math.max(1L, tag.getLong("NextId"));
        ListTag books = tag.getList("Catalogues", Tag.TAG_COMPOUND);
        for (int i = 0; i < books.size(); i++) {
            CompoundTag book = books.getCompound(i);
            Map<Long, TrackedObject> objects = new LinkedHashMap<>();
            ListTag entries = book.getList("Objects", Tag.TAG_COMPOUND);
            for (int j = 0; j < entries.size(); j++) {
                TrackedObject object = TrackedObject.load(entries.getCompound(j));
                objects.put(object.designator(), object);
            }
            registry.catalogues.put(book.getLong("Net"), objects);
        }
        return registry;
    }
}
