package com.wf.wflib.rail.align;

import com.wf.wflib.compat.WarforgeCompat;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

/**
 * What a player is allowed to do to a route, and every write that reaches the store.
 *
 * <p>Separate from {@link AlignmentService}, which decides who may <em>see</em> one. Seeing and editing
 * are different permissions and conflating them would let any ally quietly delete a main line.</p>
 */
public final class AlignmentEdits {

    /** What happened to a write, so the client can say something useful rather than nothing. */
    public enum SaveOutcome {
        SAVED,
        /** Someone else saved since the client last read it. Refused rather than allowed to win. */
        CONFLICT,
        /** Not this player's route to change. */
        DENIED,
        /** Deleted while they were editing it. */
        GONE,
        /** Fewer than two points, or otherwise not a route. */
        INVALID,
        /** The faction is at its route cap. */
        FULL
    }

    private AlignmentEdits() {
    }

    /**
     * Whether this player may change or remove this route.
     *
     * <p>Its author, or a member of the owning faction. Deliberately <b>not</b> every ally: an ally may
     * see your railway because they need to plan around it, which is not a reason to let them redraw
     * it. Deliberately not an operator either: an operator sees everything, and being able to silently
     * redraw a faction's railway is a different power from being able to look at it.</p>
     */
    public static boolean mayEdit(ServerPlayer player, Alignment alignment) {
        if (player.getUUID().equals(alignment.author())) {
            return true;
        }
        UUID owner = alignment.ownerFaction();
        return owner != null && owner.equals(WarforgeCompat.factionOfPlayer(player.getUUID()));
    }

    /**
     * Create a route, or replace the geometry of one that exists.
     *
     * <p>Called on a debounce while a route is being drawn, so this is the ordinary path rather than a
     * deliberate act of publishing: a route lives on the server from its second point onwards. Status is
     * not changed here; that is {@link #setStatus} and is always a decision someone made.</p>
     *
     * @param baseRevision the revision the client started from, or 0 when it believes this is new
     */
    public static SaveOutcome save(ServerPlayer player, ServerLevel level, UUID id, String name,
                                   int coreColour, DesignClass designClass, List<AlignPoint> points,
                                   int baseRevision) {
        if (points.size() < 2) {
            return SaveOutcome.INVALID;
        }
        AlignmentStore store = AlignmentStore.of(level);
        Alignment existing = store.get(id);

        if (existing == null) {
            if (baseRevision != 0) {
                tell(player, "That route was deleted while you were editing it.");
                return SaveOutcome.GONE;
            }
            UUID faction = WarforgeCompat.factionOfPlayer(player.getUUID());
            int held = faction != null
                    ? store.countFor(faction)
                    : store.countForAuthor(player.getUUID());
            if (held >= AlignmentStore.MAX_PER_FACTION) {
                tell(player, "You already have " + AlignmentStore.MAX_PER_FACTION
                        + " routes in this world. Delete one before surveying another.");
                return SaveOutcome.FULL;
            }
            store.put(new Alignment(id, name, player.getUUID(), faction, designClass, coreColour, points,
                    RouteStatus.DRAFT, BuildProgress.NONE, Alignment.Revision.NEW).editedBy(player.getUUID()));
            AlignmentService.pushAll(level);
            return SaveOutcome.SAVED;
        }

        if (!mayEdit(player, existing)) {
            tell(player, "That route belongs to someone else.");
            return SaveOutcome.DENIED;
        }
        if (baseRevision != existing.revision().number()) {
            tell(player, "Someone else changed that route while you were editing it, so your change was"
                    + " not saved. Your copy has been kept as a new draft.");
            return SaveOutcome.CONFLICT;
        }

        // Re-aligning can shorten the route, and track cannot sit past the end of it. It can lengthen it
        // too, and a route that has just been extended past its own railhead is no longer built, so the
        // status is re-derived from the coverage rather than carried over: otherwise adding two hundred
        // blocks of unbuilt line to a finished railway leaves it claiming to be finished.
        double length = AlignCompiler.compile(points, designClass).centreline().length();
        BuildProgress built = existing.built().clampTo(length);
        Alignment next = new Alignment(existing.id(), name, existing.author(), existing.ownerFaction(),
                designClass, coreColour, points, afterBuildChange(existing.status(), built, length),
                built, existing.revision());
        store.put(next.editedBy(player.getUUID()));
        AlignmentService.pushAll(level);
        return SaveOutcome.SAVED;
    }

    /**
     * Move a route along its lifecycle.
     *
     * <p>Promoting out of a draft is refused for a route that cannot be built as drawn. A plan the
     * geometry rejects is not a plan, and an ally being shown one is being told something false.</p>
     */
    public static SaveOutcome setStatus(ServerPlayer player, ServerLevel level, UUID id,
                                        RouteStatus status) {
        AlignmentStore store = AlignmentStore.of(level);
        Alignment existing = store.get(id);
        if (existing == null) {
            return SaveOutcome.GONE;
        }
        if (!mayEdit(player, existing)) {
            tell(player, "That route belongs to someone else.");
            return SaveOutcome.DENIED;
        }
        if (status != RouteStatus.DRAFT && status != RouteStatus.ABANDONED
                && !existing.compile().buildable()) {
            tell(player, "That route cannot be built as drawn, so it cannot be marked "
                    + status.lowerName() + ". Fix the red points first.");
            return SaveOutcome.INVALID;
        }
        store.put(existing.withStatus(status).editedBy(player.getUUID()));
        AlignmentService.pushAll(level);
        return SaveOutcome.SAVED;
    }

    /**
     * Record track laid, or track gone, between two chainages.
     *
     * @param laid true for track down, false for track gone
     */
    public static SaveOutcome setBuilt(ServerPlayer player, ServerLevel level, UUID id, double from,
                                       double to, boolean laid) {
        AlignmentStore store = AlignmentStore.of(level);
        Alignment existing = store.get(id);
        if (existing == null) {
            return SaveOutcome.GONE;
        }
        if (!mayEdit(player, existing)) {
            tell(player, "That route belongs to someone else.");
            return SaveOutcome.DENIED;
        }
        store.put(applyBuild(existing, from, to, laid).editedBy(player.getUUID()));
        AlignmentService.pushAll(level);
        return SaveOutcome.SAVED;
    }

    /**
     * Apply a build report to a route and take its status with it.
     *
     * <p>Kept separate from the permission check so a track layer reporting its own work can use it, and
     * so the promotion rule can be tested without a server.</p>
     */
    public static Alignment applyBuild(Alignment alignment, double from, double to, boolean laid) {
        return applyBuild(alignment, from, to, laid, alignment.compile().centreline().length());
    }

    /** As above, for a caller that already knows the route's length and should not pay to compile it. */
    public static Alignment applyBuild(Alignment alignment, double from, double to, boolean laid,
                                       double length) {
        BuildProgress built = (laid ? alignment.built().with(from, to) : alignment.built().without(from, to))
                .clampTo(length);
        return alignment.withBuilt(built).withStatus(afterBuildChange(alignment.status(), built, length));
    }

    /**
     * What a route's status becomes once its track has changed.
     *
     * <p>Building promotes: a draft with rails going down is not a draft, and hiding the plan for track
     * an enemy can already walk along protects nothing. Track gone demotes only as far as planned, never
     * to a draft, because a route that was once built was agreed. Abandoning is a statement someone made
     * and is never overridden by a machine.</p>
     */
    public static RouteStatus afterBuildChange(RouteStatus current, BuildProgress built, double length) {
        if (current == RouteStatus.ABANDONED) {
            return RouteStatus.ABANDONED;
        }
        if (built.isEmpty()) {
            return current.onTheGround() ? RouteStatus.PLANNED : current;
        }
        if (built.covers(length)) {
            return RouteStatus.BUILT;
        }
        return RouteStatus.BUILDING;
    }

    /** @return whether the route was removed. */
    public static boolean delete(ServerPlayer player, ServerLevel level, UUID id) {
        AlignmentStore store = AlignmentStore.of(level);
        Alignment existing = store.get(id);
        if (existing == null) {
            return false;
        }
        if (!mayEdit(player, existing)) {
            tell(player, "That route belongs to someone else.");
            return false;
        }
        store.remove(id);
        AlignmentService.pushAll(level);
        return true;
    }

    private static void tell(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(message));
    }
}
