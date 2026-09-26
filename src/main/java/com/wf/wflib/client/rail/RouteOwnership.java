package com.wf.wflib.client.rail;

import com.wf.wflib.network.RailRightOfWayPacket;
import com.wf.wflib.rail.align.RightOfWay;

/** The last right-of-way answer this client received. */
public final class RouteOwnership {

    private static volatile RightOfWay current = RightOfWay.EMPTY;

    private RouteOwnership() {
    }

    public static RightOfWay current() {
        return current;
    }

    public static void accept(RailRightOfWayPacket packet) {
        current = packet.rightOfWay();
    }

    /**
     * Throw the answer away.
     *
     * <p>Called the moment the line changes, not when the next answer arrives: a right of way drawn
     * against a line that has since moved is worse than none, because it looks authoritative.</p>
     */
    public static void clear() {
        current = RightOfWay.EMPTY;
    }
}
