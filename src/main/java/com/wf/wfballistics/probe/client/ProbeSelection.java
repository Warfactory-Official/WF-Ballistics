package com.wf.wfballistics.probe.client;

import com.wf.wfballistics.network.WFNetwork;
import com.wf.wfballistics.probe.ProbeAction;
import com.wf.wfballistics.probe.ProbeActionPacket;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Which action of the current target's list is selected, and the scrolling that moves it. */
public final class ProbeSelection {

    /** Most the drawing may lag the selection by, in rows. */
    private static final float MAX_LAG = 1.5f;

    private static List<ProbeAction> actions = List.of();
    private static BlockPos targetPos = BlockPos.ZERO;
    private static int targetEntity = Integer.MIN_VALUE;
    private static int index;

    /** Rows the drawing still lags the selection by; zero at rest. */
    private static float offset;
    private static long lastMillis;

    private ProbeSelection() {
    }

    /** Called once a frame by the overlay with the list it is about to draw. */
    static void offer(BlockPos pos, int entityId, List<ProbeAction> offered) {
        boolean sameTarget = entityId == targetEntity && (entityId >= 0 || pos.equals(targetPos));
        ProbeAction chosen = sameTarget ? current() : null;

        targetPos = pos.immutable();
        targetEntity = entityId;
        actions = offered;

        index = 0;
        if (chosen != null) {
            for (int i = 0; i < actions.size(); i++) {
                if (actions.get(i).sameAs(chosen)) {
                    index = i;
                    break;
                }
            }
        }
    }

    static void clear() {
        actions = List.of();
        targetEntity = Integer.MIN_VALUE;
        targetPos = BlockPos.ZERO;
        index = 0;
        offset = 0.0f;
    }

    /**
     * Advances the slide, once a frame, from the wall clock rather than from ticks: the panel is drawn every frame
     * and a scroll can land at any point between two of them, so a tick-based curve would animate in visible steps
     * on a fast machine and skip most of itself on a slow one.
     */
    static void advance() {
        long now = Util.getMillis();
        long elapsed = lastMillis == 0L ? 0L : now - lastMillis;
        lastMillis = now;
        if (offset == 0.0f) {
            return;
        }
        int millis = ProbeConfig.ACTION_SLIDE_MILLIS.get();
        if (millis <= 0) {
            offset = 0.0f;
            return;
        }
        if (elapsed <= 0L) {
            return;
        }
        offset *= (float) Math.exp(-elapsed * 4.0 / millis);
        if (Math.abs(offset) < 0.002f) {
            offset = 0.0f;
        }
    }

    /** How far the drawing lags the selection, in rows. Positive means the list is still coming down. */
    public static float offset() {
        return offset;
    }

    public static List<ProbeAction> actions() {
        return actions;
    }

    public static int index() {
        return index;
    }

    @Nullable
    public static ProbeAction current() {
        return index >= 0 && index < actions.size() ? actions.get(index) : null;
    }

    /** Moves the selection, wrapping. Positive scrolls down the list. */
    public static void scroll(int by) {
        if (actions.size() < 2) {
            return;
        }
        index = Math.floorMod(index + by, actions.size());
        offset = Mth.clamp(offset - by, -MAX_LAG, MAX_LAG);
    }

    /** Sends the selected action to the server, if there is one and it is available. */
    public static boolean activate() {
        ProbeAction action = current();
        if (action == null || !action.enabled()) {
            return false;
        }
        WFNetwork.sendToServer(
                new ProbeActionPacket(targetPos, targetEntity, action.id(), action.arg()));
        return true;
    }
}
