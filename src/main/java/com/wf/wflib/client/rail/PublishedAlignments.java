package com.wf.wflib.client.rail;

import com.wf.wflib.network.RailAlignmentPacket;
import com.wf.wflib.network.RailSaveResultPacket;
import com.wf.wflib.rail.align.AlignmentView;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The routes this client has been told about.
 *
 * <p>Everything here arrived from the server, which has already decided what this player may see, so
 * there is no filtering to do on arrival. What is not in this list was never sent.</p>
 */
public final class PublishedAlignments {

    /** Told when the set changes, so the map layer and the editor can both react. */
    public interface Listener {
        void onAlignments(ResourceKey<Level> dimension, List<AlignmentView> alignments);
    }

    /** Told what the server did with a save. */
    public interface SaveListener {
        void onSaveResult(RailSaveResultPacket result);
    }

    private static volatile List<AlignmentView> current = List.of();
    private static volatile ResourceKey<Level> dimension;
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final List<SaveListener> SAVE_LISTENERS = new CopyOnWriteArrayList<>();

    private PublishedAlignments() {
    }

    public static List<AlignmentView> current() {
        return current;
    }

    public static ResourceKey<Level> dimension() {
        return dimension;
    }

    /** @return the route with this id, or null when this client has not been sent it. */
    public static AlignmentView byId(UUID id) {
        if (id == null) {
            return null;
        }
        for (AlignmentView view : current) {
            if (id.equals(view.id())) {
                return view;
            }
        }
        return null;
    }

    /** Register a listener, replaying whatever has already arrived so a late one is not empty. */
    public static void addListener(Listener next) {
        if (next == null || LISTENERS.contains(next)) {
            return;
        }
        LISTENERS.add(next);
        if (dimension != null) {
            next.onAlignments(dimension, current);
        }
    }

    public static void addSaveListener(SaveListener next) {
        if (next != null && !SAVE_LISTENERS.contains(next)) {
            SAVE_LISTENERS.add(next);
        }
    }

    public static void accept(RailAlignmentPacket packet) {
        current = packet.alignments();
        dimension = packet.dimension();
        for (Listener listener : LISTENERS) {
            listener.onAlignments(dimension, current);
        }
    }

    public static void acceptResult(RailSaveResultPacket packet) {
        for (SaveListener listener : SAVE_LISTENERS) {
            listener.onSaveResult(packet);
        }
    }

    /** Leaving a world must not leave last session's routes on the map. */
    public static void clear() {
        current = List.of();
        if (dimension != null) {
            for (Listener listener : LISTENERS) {
                listener.onAlignments(dimension, current);
            }
        }
        dimension = null;
    }
}
