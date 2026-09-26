package com.wf.wflib.rail.align;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who may be shown a route.
 *
 * <p>Worth testing away from a server because it is a disclosure rule, not a display preference: a
 * railway is a map of where a faction's material moves, and getting this wrong hands that to an enemy
 * with no visible symptom on either side.</p>
 */
class AlignmentVisibilityTest {

    private static final UUID AUTHOR = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID ALLY = UUID.randomUUID();
    private static final UUID ENEMY = UUID.randomUUID();
    private static final UUID OWNER_FACTION = UUID.randomUUID();
    private static final UUID OTHER_FACTION = UUID.randomUUID();

    /** Stands in for WarForge: these players are allied with the owning faction, nobody else is. */
    private static BiPredicate<UUID, UUID> standing(Set<UUID> allied) {
        return (viewer, faction) -> faction != null && faction.equals(OWNER_FACTION) && allied.contains(viewer);
    }

    /** The common case: a committed route, seen by someone outside the owning faction. */
    private static boolean outsiderSees(UUID viewer, UUID ownerFaction, RouteStatus status,
                                        BiPredicate<UUID, UUID> standing) {
        return AlignmentService.maySee(viewer, OTHER_FACTION, AUTHOR, ownerFaction, status, false, standing);
    }

    @Test
    @DisplayName("an enemy is not shown the route")
    void enemiesSeeNothing() {
        assertFalse(outsiderSees(ENEMY, OWNER_FACTION, RouteStatus.PLANNED, standing(Set.of(ALLY))));
    }

    @Test
    @DisplayName("an operator sees everything, including a route no faction would show them")
    void operatorsSeeEverything() {
        assertTrue(AlignmentService.maySee(ENEMY, OTHER_FACTION, AUTHOR, OWNER_FACTION,
                RouteStatus.PLANNED, true, standing(Set.of())));
        assertTrue(AlignmentService.maySee(ENEMY, null, AUTHOR, null, RouteStatus.DRAFT, true,
                (v, f) -> false), "an unowned draft too, which is otherwise its author's alone");
    }

    @Test
    @DisplayName("being an operator is the only reason an enemy sees it, which is worth knowing")
    void operatorVisibilityIsDistinguishable() {
        // The map groups these separately, so an admin can switch every faction's railway off without
        // losing their own. That needs the two answers to be asked separately.
        assertTrue(AlignmentService.maySee(ENEMY, OTHER_FACTION, AUTHOR, OWNER_FACTION,
                RouteStatus.PLANNED, true, standing(Set.of())));
        assertFalse(outsiderSees(ENEMY, OWNER_FACTION, RouteStatus.PLANNED, standing(Set.of())));
    }

    @Test
    @DisplayName("an operator who is also an ally is not marked as seeing it only as an operator")
    void operatorWhoWouldSeeItAnyway() {
        assertTrue(outsiderSees(ALLY, OWNER_FACTION, RouteStatus.PLANNED, standing(Set.of(ALLY))),
                "the ally rule already covers them, so the map keeps it in the ordinary group");
    }

    @Test
    @DisplayName("an ally is shown a committed route")
    void alliesSeeIt() {
        assertTrue(outsiderSees(ALLY, OWNER_FACTION, RouteStatus.PLANNED, standing(Set.of(ALLY))));
    }

    @Test
    @DisplayName("an ally is not shown a draft, because a faction has not committed to one")
    void alliesDoNotSeeDrafts() {
        assertFalse(outsiderSees(ALLY, OWNER_FACTION, RouteStatus.DRAFT, standing(Set.of(ALLY))));
        for (RouteStatus status : RouteStatus.values()) {
            assertTrue(outsiderSees(ALLY, OWNER_FACTION, status, standing(Set.of(ALLY)))
                            == (status != RouteStatus.DRAFT),
                    status + " is shared with allies exactly when it is not a draft");
        }
    }

    @Test
    @DisplayName("the faction that owns a route sees all of it, drafts included")
    void ownFactionSeesDrafts() {
        // This is what makes it a planning tool: a route is drawn by one person and finished by another.
        for (RouteStatus status : RouteStatus.values()) {
            assertTrue(AlignmentService.maySee(MEMBER, OWNER_FACTION, AUTHOR, OWNER_FACTION, status,
                            false, standing(Set.of())),
                    "a member of the owning faction sees its " + status.lowerName() + " routes");
        }
    }

    @Test
    @DisplayName("the author keeps sight of their own route after leaving the faction that owns it")
    void authorAlwaysSeesTheirOwn() {
        // Not in the faction and not allied with it, and still sees it: otherwise a survey can become
        // invisible to the person who drew it, with no way to get it back.
        assertTrue(AlignmentService.maySee(AUTHOR, OTHER_FACTION, AUTHOR, OWNER_FACTION,
                RouteStatus.DRAFT, false, standing(Set.of(ALLY))));
    }

    @Test
    @DisplayName("an unowned route is nobody's but its author's")
    void unownedIsPrivate() {
        assertTrue(AlignmentService.maySee(AUTHOR, null, AUTHOR, null, RouteStatus.PLANNED, false,
                standing(Set.of(ALLY, ENEMY))));
        assertFalse(AlignmentService.maySee(ENEMY, null, AUTHOR, null, RouteStatus.PLANNED, false,
                        (v, f) -> f != null),
                "no owner means no faction to be allied with");
    }

    @Test
    @DisplayName("a route whose alliance has been broken stops being visible")
    void allianceLossHidesIt() {
        assertTrue(outsiderSees(ALLY, OWNER_FACTION, RouteStatus.BUILT, standing(Set.of(ALLY))));
        assertFalse(outsiderSees(ALLY, OWNER_FACTION, RouteStatus.BUILT, standing(Set.of())),
                "the set is re-evaluated, so a broken alliance takes the route off the map");
    }

    @Test
    @DisplayName("a viewer with no identity is shown nothing")
    void nullViewerSeesNothing() {
        assertFalse(AlignmentService.maySee(null, OWNER_FACTION, AUTHOR, OWNER_FACTION,
                RouteStatus.BUILT, false, (v, f) -> true));
    }

    @Test
    @DisplayName("a null status is treated as a draft rather than as a commitment")
    void unknownStatusIsClosed() {
        assertFalse(outsiderSees(ALLY, OWNER_FACTION, null, standing(Set.of(ALLY))),
                "failing open here would disclose a route on nothing more than a decode going wrong");
    }
}
