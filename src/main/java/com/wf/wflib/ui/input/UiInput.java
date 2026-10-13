package com.wf.wflib.ui.input;

import java.util.Arrays;
import java.util.Objects;

/**
 * Pointer state and immediate-mode hit table of one {@link com.wf.wflib.ui.UiInstance}. Widgets
 * {@link #hit} their interactive boxes while drawing; the next frame's {@link #dispatch} resolves the
 * pointer against that table (one frame stale), topmost = last recorded. Hit identity = (owner, item):
 * the same node drawn once per repeat element stays distinguishable across frames.
 *
 * <p>Host units = the box passed to {@code UiInstance.render}; {@link #pointer} takes them too. Mirrors
 * the canvas transform/clip stack through {@link #push}/{@link #pop}/{@link #pushClip}/{@link #popClip}.
 */
public final class UiInput {
    public static final int PRIMARY = 1;
    public static final int SECONDARY = 2;

    private static final int STRIDE = 14;
    private static final int HX0 = 0, HY0 = 1, HX1 = 2, HY1 = 3;
    private static final int IA = 4, IB = 5, IC = 6, ID = 7, ITX = 8, ITY = 9;
    private static final int LX = 10, LY = 11, LW = 12, LH = 13;

    // host = (a*x + c*y + tx, b*x + d*y + ty)
    private float[] affine = new float[6 * 8];
    private int affineDepth;
    private float[] clips = new float[4 * 8];
    private int clipDepth;

    private float[] front = new float[STRIDE * 16];
    private Interactive[] frontOwners = new Interactive[16];
    private Object[] frontItems = new Object[16];
    private int frontCount;
    private float[] back = new float[STRIDE * 16];
    private Interactive[] backOwners = new Interactive[16];
    private Object[] backItems = new Object[16];
    private int backCount;

    private float px = Float.NaN;
    private float py = Float.NaN;
    private int buttons;
    private int lastButtons;
    private float wheel;

    private Interactive hoverOwner;
    private Object hoverItem;
    private Interactive captureOwner;
    private Object captureItem;
    private final float[] capture = new float[STRIDE];

    /** Pointer for the next frame, host units; NaN = outside. {@code wheel} accumulates until dispatched. */
    public void pointer(float x, float y, int buttons) {
        px = x;
        py = y;
        this.buttons = buttons;
    }

    public void wheel(float amount) {
        wheel += amount;
    }

    public float pointerX() {
        return px;
    }

    public float pointerY() {
        return py;
    }

    /** Releases capture and hover, e.g. when the host stops showing the instance. */
    public void reset() {
        if (captureOwner != null) {
            captureOwner.release(captureItem, Float.NaN, Float.NaN, false);
        }
        captureOwner = null;
        captureItem = null;
        hoverOwner = null;
        hoverItem = null;
        buttons = 0;
        lastButtons = 0;
        wheel = 0;
        px = Float.NaN;
        py = Float.NaN;
    }

    public boolean hovered(Interactive owner, Object item) {
        return hoverOwner == owner && Objects.equals(hoverItem, item) && (captureOwner == null || captured(owner, item));
    }

    public boolean captured(Interactive owner, Object item) {
        return captureOwner == owner && Objects.equals(captureItem, item);
    }

    /** Resolves the pointer against the last recorded table and runs callbacks. Before evaluation. */
    public void dispatch() {
        int top = topmost(px, py);
        hoverOwner = top < 0 ? null : frontOwners[top];
        hoverItem = top < 0 ? null : frontItems[top];
        boolean down = (buttons & PRIMARY) != 0;
        boolean wasDown = (lastButtons & PRIMARY) != 0;
        boolean secondary = (buttons & SECONDARY) != 0 && (lastButtons & SECONDARY) == 0;
        lastButtons = buttons;
        if (secondary && top >= 0) {
            System.arraycopy(front, top * STRIDE, capture, 0, STRIDE);
            frontOwners[top].secondary(frontItems[top], localX(capture, px, py), localY(capture, px, py));
        }
        if (captureOwner != null) {
            int at = find(captureOwner, captureItem);
            if (at >= 0) {
                System.arraycopy(front, at * STRIDE, capture, 0, STRIDE);
            }
            float lx = localX(capture, px, py);
            float ly = localY(capture, px, py);
            Interactive owner = captureOwner;
            Object item = captureItem;
            if (!down) {
                captureOwner = null;
                captureItem = null;
                owner.release(item, lx, ly, top >= 0 && frontOwners[top] == owner && Objects.equals(frontItems[top], item));
            } else {
                owner.drag(item, lx, ly);
            }
        } else if (down && !wasDown && UiKeyboard.focused() != null) {
            // Click anywhere drops text focus; a text field's press takes it back.
            UiKeyboard.blur();
        }
        if (captureOwner == null && down && !wasDown && top >= 0) {
            captureOwner = frontOwners[top];
            captureItem = frontItems[top];
            System.arraycopy(front, top * STRIDE, capture, 0, STRIDE);
            captureOwner.press(captureItem, localX(capture, px, py), localY(capture, px, py));
        }
        if (wheel != 0) {
            float amount = wheel;
            wheel = 0;
            for (int i = frontCount - 1; i >= 0; i--) {
                if (contains(front, i, px, py) && frontOwners[i].wheel(frontItems[i], amount)) {
                    break;
                }
            }
        }
    }

    /** Starts recording this frame's table at host identity. */
    public void begin() {
        backCount = 0;
        affineDepth = 0;
        clipDepth = 0;
        setIdentity(0);
        clips[0] = Float.NEGATIVE_INFINITY;
        clips[1] = Float.NEGATIVE_INFINITY;
        clips[2] = Float.POSITIVE_INFINITY;
        clips[3] = Float.POSITIVE_INFINITY;
    }

    /** Publishes the recorded table to the next {@link #dispatch}. */
    public void end() {
        int stale = frontCount;
        float[] t = front;
        front = back;
        back = t;
        Interactive[] o = frontOwners;
        frontOwners = backOwners;
        backOwners = o;
        Object[] it = frontItems;
        frontItems = backItems;
        backItems = it;
        frontCount = backCount;
        Arrays.fill(backOwners, 0, stale, null);
        Arrays.fill(backItems, 0, stale, null);
        backCount = 0;
    }

    public UiInput push() {
        int at = affineDepth * 6;
        if (at + 12 > affine.length) {
            affine = Arrays.copyOf(affine, affine.length * 2);
        }
        System.arraycopy(affine, at, affine, at + 6, 6);
        affineDepth++;
        return this;
    }

    public UiInput pop() {
        affineDepth--;
        return this;
    }

    public UiInput translate(float x, float y) {
        int at = affineDepth * 6;
        affine[at + 4] += affine[at] * x + affine[at + 2] * y;
        affine[at + 5] += affine[at + 1] * x + affine[at + 3] * y;
        return this;
    }

    public UiInput scale(float s) {
        return scale(s, s);
    }

    public UiInput scale(float sx, float sy) {
        int at = affineDepth * 6;
        affine[at] *= sx;
        affine[at + 1] *= sx;
        affine[at + 2] *= sy;
        affine[at + 3] *= sy;
        return this;
    }

    /** Same sense as {@code Canvas.rotate}. */
    public UiInput rotate(float radians) {
        int at = affineDepth * 6;
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);
        float a = affine[at], b = affine[at + 1], c = affine[at + 2], d = affine[at + 3];
        affine[at] = a * cos + c * sin;
        affine[at + 1] = b * cos + d * sin;
        affine[at + 2] = c * cos - a * sin;
        affine[at + 3] = d * cos - b * sin;
        return this;
    }

    /** Local box; intersected with the enclosing clip as a host AABB. */
    public UiInput pushClip(float x0, float y0, float x1, float y1) {
        int at = clipDepth * 4;
        if (at + 8 > clips.length) {
            clips = Arrays.copyOf(clips, clips.length * 2);
        }
        int m = affineDepth * 6;
        float hx0 = Float.POSITIVE_INFINITY, hy0 = Float.POSITIVE_INFINITY;
        float hx1 = Float.NEGATIVE_INFINITY, hy1 = Float.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 4; corner++) {
            float lx = (corner & 1) == 0 ? x0 : x1;
            float ly = (corner & 2) == 0 ? y0 : y1;
            float hx = affine[m] * lx + affine[m + 2] * ly + affine[m + 4];
            float hy = affine[m + 1] * lx + affine[m + 3] * ly + affine[m + 5];
            hx0 = Math.min(hx0, hx);
            hy0 = Math.min(hy0, hy);
            hx1 = Math.max(hx1, hx);
            hy1 = Math.max(hy1, hy);
        }
        clips[at + 4] = Math.max(clips[at], hx0);
        clips[at + 5] = Math.max(clips[at + 1], hy0);
        clips[at + 6] = Math.min(clips[at + 2], hx1);
        clips[at + 7] = Math.min(clips[at + 3], hy1);
        clipDepth++;
        return this;
    }

    public UiInput popClip() {
        clipDepth--;
        return this;
    }

    /**
     * Records {@code owner}'s box (local units) under the current transform and clip; callbacks receive
     * coordinates relative to its top-left in the same local units.
     */
    public void hit(float x0, float y0, float x1, float y1, Interactive owner, Object item) {
        if (!(x1 > x0) || !(y1 > y0)) {
            return;
        }
        int m = affineDepth * 6;
        float a = affine[m], b = affine[m + 1], c = affine[m + 2], d = affine[m + 3];
        float tx = affine[m + 4], ty = affine[m + 5];
        float det = a * d - b * c;
        if (det == 0) {
            return;
        }
        float hx0 = Float.POSITIVE_INFINITY, hy0 = Float.POSITIVE_INFINITY;
        float hx1 = Float.NEGATIVE_INFINITY, hy1 = Float.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 4; corner++) {
            float lx = (corner & 1) == 0 ? x0 : x1;
            float ly = (corner & 2) == 0 ? y0 : y1;
            float hx = a * lx + c * ly + tx;
            float hy = b * lx + d * ly + ty;
            hx0 = Math.min(hx0, hx);
            hy0 = Math.min(hy0, hy);
            hx1 = Math.max(hx1, hx);
            hy1 = Math.max(hy1, hy);
        }
        int k = clipDepth * 4;
        hx0 = Math.max(hx0, clips[k]);
        hy0 = Math.max(hy0, clips[k + 1]);
        hx1 = Math.min(hx1, clips[k + 2]);
        hy1 = Math.min(hy1, clips[k + 3]);
        if (!(hx1 > hx0) || !(hy1 > hy0)) {
            return;
        }
        if (backCount == backOwners.length) {
            int n = backCount * 2;
            back = Arrays.copyOf(back, n * STRIDE);
            backOwners = Arrays.copyOf(backOwners, n);
            backItems = Arrays.copyOf(backItems, n);
        }
        int at = backCount * STRIDE;
        back[at + HX0] = hx0;
        back[at + HY0] = hy0;
        back[at + HX1] = hx1;
        back[at + HY1] = hy1;
        float inv = 1 / det;
        back[at + IA] = d * inv;
        back[at + IB] = -b * inv;
        back[at + IC] = -c * inv;
        back[at + ID] = a * inv;
        back[at + ITX] = (c * ty - d * tx) * inv;
        back[at + ITY] = (b * tx - a * ty) * inv;
        back[at + LX] = x0;
        back[at + LY] = y0;
        back[at + LW] = x1 - x0;
        back[at + LH] = y1 - y0;
        backOwners[backCount] = owner;
        backItems[backCount] = item;
        backCount++;
    }

    private void setIdentity(int depth) {
        int at = depth * 6;
        affine[at] = 1;
        affine[at + 1] = 0;
        affine[at + 2] = 0;
        affine[at + 3] = 1;
        affine[at + 4] = 0;
        affine[at + 5] = 0;
    }

    private int topmost(float x, float y) {
        for (int i = frontCount - 1; i >= 0; i--) {
            if (contains(front, i, x, y)) {
                return i;
            }
        }
        return -1;
    }

    private int find(Interactive owner, Object item) {
        for (int i = frontCount - 1; i >= 0; i--) {
            if (frontOwners[i] == owner && Objects.equals(frontItems[i], item)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean contains(float[] table, int index, float x, float y) {
        int at = index * STRIDE;
        if (!(x >= table[at + HX0] && x < table[at + HX1] && y >= table[at + HY0] && y < table[at + HY1])) {
            return false;
        }
        float lx = table[at + IA] * x + table[at + IC] * y + table[at + ITX] - table[at + LX];
        float ly = table[at + IB] * x + table[at + ID] * y + table[at + ITY] - table[at + LY];
        return lx >= 0 && lx < table[at + LW] && ly >= 0 && ly < table[at + LH];
    }

    private static float localX(float[] r, float x, float y) {
        return r[IA] * x + r[IC] * y + r[ITX] - r[LX];
    }

    private static float localY(float[] r, float x, float y) {
        return r[IB] * x + r[ID] * y + r[ITY] - r[LY];
    }
}
