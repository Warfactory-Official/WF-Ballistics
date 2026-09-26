package com.wf.wflib.rail.align;

import com.wf.wflib.compat.WarforgeCompat;
import com.wf.wflib.network.RailAlignmentPacket;
import com.wf.wflib.network.WFNetwork;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who may see which route, and sending it to them.
 *
 * <p><b>The filter is here, on the server, and that is the whole point.</b> A railway is a map of where
 * a faction's material moves and what it is worth attacking; showing it to an enemy would be worse than
 * showing them a base marker. Filtering on the client would not be filtering at all, because a client
 * that has been sent a route has it whatever it chooses to draw, so an enemy route is never put on the
 * wire in the first place.</p>
 *
 * <p>Alliances change without telling us, so each player's visible set is rebuilt on an interval and
 * compared with what they were last sent. That costs a hash over a handful of routes and means a broken
 * alliance takes a route off the map by itself.</p>
 */
public final class AlignmentService {

    /** Ticks between re-evaluating who can see what. Alliances do not change often. */
    public static final int REFRESH_INTERVAL = 60;

    /** Colour used for the outline when a route has no owning faction. */
    private static final int UNOWNED_OUTLINE = 0x9AA0A6;

    /** Permission level that sees every route. The same level {@code /wfrail} is gated on. */
    public static final int OPERATOR_LEVEL = 2;

    /** What each player was last sent, so an unchanged set costs no packet. */
    private static final Map<UUID, Integer> SENT = new HashMap<>();

    private AlignmentService() {
    }

    /** Whether this player may be shown this route. */
    public static boolean visibleTo(ServerPlayer player, Alignment alignment) {
        return maySee(player.getUUID(), WarforgeCompat.factionOfPlayer(player.getUUID()),
                alignment.author(), alignment.ownerFaction(), alignment.status(),
                player.hasPermissions(OPERATOR_LEVEL), WarforgeCompat::maySeeFactionContent);
    }

    /**
     * Whether this player would see this route if they were not an operator.
     *
     * <p>Asked so the map can separate the two. An operator's map is otherwise every faction's railway
     * at once with nothing to say which of them they are entitled to.</p>
     */
    public static boolean visibleWithoutOp(ServerPlayer player, Alignment alignment) {
        return maySee(player.getUUID(), WarforgeCompat.factionOfPlayer(player.getUUID()),
                alignment.author(), alignment.ownerFaction(), alignment.status(), false,
                WarforgeCompat::maySeeFactionContent);
    }

    /**
     * The rule itself, with the faction lookup passed in so it can be tested without a server.
     *
     * <p>In order: an operator sees everything; the author always sees their own, even after leaving the
     * faction that owns it, because otherwise a survey becomes invisible to the person who drew it with
     * no way to get it back; the owning faction sees all of its own routes whatever state they are in,
     * which is what makes this a planning tool rather than a publishing one; and an ally sees only what
     * the faction has committed to, which is anything past {@link RouteStatus#DRAFT}.</p>
     *
     * @param viewerFaction the viewer's own faction, or null
     * @param operator whether the viewer is an operator, who sees everything
     * @param standing answers "may this player be shown something owned by this faction"
     */
    public static boolean maySee(UUID viewer, UUID viewerFaction, UUID author, UUID ownerFaction,
                                 RouteStatus status, boolean operator,
                                 java.util.function.BiPredicate<UUID, UUID> standing) {
        if (viewer == null) {
            return false;
        }
        if (operator) {
            return true;
        }
        if (viewer.equals(author)) {
            return true;
        }
        if (ownerFaction != null && ownerFaction.equals(viewerFaction)) {
            return true;
        }
        RouteStatus state = status == null ? RouteStatus.DRAFT : status;
        return state.sharedWithAllies() && standing.test(viewer, ownerFaction);
    }

    /** The routes this player may see, as they will be drawn. */
    public static List<AlignmentView> viewsFor(ServerPlayer player, ServerLevel level) {
        List<AlignmentView> out = new ArrayList<>();
        for (Alignment alignment : AlignmentStore.of(level).all()) {
            if (!visibleTo(player, alignment)) {
                continue;
            }
            String ownerName = WarforgeCompat.factionName(alignment.ownerFaction());
            out.add(new AlignmentView(alignment.id(), alignment.name(),
                    ownerName == null ? "" : ownerName,
                    WarforgeCompat.factionColour(alignment.ownerFaction(), UNOWNED_OUTLINE),
                    alignment.coreColour(), alignment.designClass(),
                    AlignmentEdits.mayEdit(player, alignment),
                    !visibleWithoutOp(player, alignment),
                    alignment.status(), alignment.built(), alignment.revision().number(),
                    nameOf(level, alignment.revision().editor()), alignment.points()));
        }
        return out;
    }

    /** A player's name for the map's tooltip, or empty when the server has never seen them. */
    private static String nameOf(ServerLevel level, UUID player) {
        if (player == null) {
            return "";
        }
        var profile = level.getServer().getProfileCache();
        if (profile == null) {
            return "";
        }
        return profile.get(player).map(com.mojang.authlib.GameProfile::getName).orElse("");
    }

    /** Send this player their visible set, whether or not it has changed. */
    public static void push(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        List<AlignmentView> views = viewsFor(player, level);
        SENT.put(player.getUUID(), fingerprint(views));
        WFNetwork.sendToPlayer(player, new RailAlignmentPacket(level.dimension(), views));
    }

    /** Send it only if it differs from what they already have. */
    public static void pushIfChanged(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        List<AlignmentView> views = viewsFor(player, level);
        int now = fingerprint(views);
        Integer before = SENT.get(player.getUUID());
        if (before != null && before == now) {
            return;
        }
        SENT.put(player.getUUID(), now);
        WFNetwork.sendToPlayer(player, new RailAlignmentPacket(level.dimension(), views));
    }

    /** After an edit: everyone in the dimension re-evaluates, because an edit can change who may see it. */
    public static void pushAll(ServerLevel level) {
        for (ServerPlayer player : level.players()) {
            pushIfChanged(player);
        }
    }

    public static void tick(ServerLevel level) {
        if (level.getServer().getTickCount() % REFRESH_INTERVAL != 0) {
            return;
        }
        for (ServerPlayer player : level.players()) {
            pushIfChanged(player);
        }
    }

    public static void forget(ServerPlayer player) {
        SENT.remove(player.getUUID());
    }

    /**
     * A cheap summary of what a player can currently see.
     *
     * <p>Covers the colours, the owner, the status and the built runs as well as the geometry, so a
     * faction recolouring its routes or a track layer reporting progress reaches everyone who can see it
     * without an explicit notification.</p>
     */
    private static int fingerprint(List<AlignmentView> views) {
        int hash = 1;
        for (AlignmentView view : views) {
            hash = hash * 31 + view.id().hashCode();
            hash = hash * 31 + view.coreColour();
            hash = hash * 31 + view.outlineColour();
            hash = hash * 31 + view.ownerName().hashCode();
            hash = hash * 31 + view.status().ordinal();
            hash = hash * 31 + view.built().hashCode();
            hash = hash * 31 + view.revision();
            hash = hash * 31 + (view.mayEdit() ? 1 : 0);
            // Losing op changes which group a route is drawn in, not just whether it is drawn.
            hash = hash * 31 + (view.viaOperator() ? 1 : 0);
            hash = hash * 31 + view.points().hashCode();
        }
        return hash;
    }
}
