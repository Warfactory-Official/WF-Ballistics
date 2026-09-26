package com.wf.wflib.rail.align;

import java.util.List;
import java.util.UUID;

/**
 * A route as a client is allowed to know it.
 *
 * <p>The owning faction is resolved to a name and a colour on the server rather than sent as a UUID.
 * That keeps the map layer free of any WarForge dependency, and it means a client is never holding an
 * id it could use to ask about a faction it has no business knowing.</p>
 *
 * <p>The same goes for {@link #mayEdit()}: whether this player can change the route is the server's
 * answer, sent so the client can say so before a refused save rather than after one.</p>
 *
 * @param outlineColour the owning faction's colour, 0xRRGGBB, which is what says whose line this is
 * @param coreColour the surveyor's own colour, 0xRRGGBB, which is what tells two of their lines apart
 * @param ownerName the faction's display name, or empty when the line has no owner
 * @param mayEdit whether the receiving player may change or delete it
 * @param viaOperator whether the receiver is only being shown this because they are an operator. Kept
 *                    so the map can put those in their own group: an admin who is also a player should
 *                    be able to switch every faction's railway off without losing their own.
 * @param revision the version the client is holding, sent back with a save so a stale write is refused
 * @param lastEditorName who last changed it, so a team can see the route moving under them
 */
public record AlignmentView(UUID id, String name, String ownerName, int outlineColour, int coreColour,
                            DesignClass designClass, boolean mayEdit, boolean viaOperator,
                            RouteStatus status, BuildProgress built, int revision, String lastEditorName,
                            List<AlignPoint> points) {

    public AlignmentView {
        points = List.copyOf(points);
        status = status == null ? RouteStatus.DRAFT : status;
        built = built == null ? BuildProgress.NONE : built;
        lastEditorName = lastEditorName == null ? "" : lastEditorName;
    }

    public AlignResult compile() {
        return AlignCompiler.compile(this.points, this.designClass);
    }
}
