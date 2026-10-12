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

    /** Called the instant the line changes, not on the next answer: a stale right-of-way looks authoritative. */
    public static void clear() {
        current = RightOfWay.EMPTY;
    }
}
