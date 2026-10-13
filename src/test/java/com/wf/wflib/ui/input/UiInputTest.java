package com.wf.wflib.ui.input;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiInputTest {
    private static final class Probe implements Interactive {
        final List<String> log = new ArrayList<>();
        boolean consume;

        @Override
        public void press(Object item, float x, float y) {
            log.add("press " + item + " " + Math.round(x) + "," + Math.round(y));
        }

        @Override
        public void drag(Object item, float x, float y) {
            log.add("drag " + item + " " + Math.round(x) + "," + Math.round(y));
        }

        @Override
        public void release(Object item, float x, float y, boolean inside) {
            log.add("release " + item + " " + inside);
        }

        @Override
        public boolean wheel(Object item, float amount) {
            log.add("wheel " + item);
            return consume;
        }
    }

    private static void frame(UiInput input, Runnable draw) {
        input.dispatch();
        input.begin();
        draw.run();
        input.end();
    }

    @Test
    void nestedTransformMapsToLocal() {
        UiInput input = new UiInput();
        Probe probe = new Probe();
        Runnable draw = () -> {
            input.push().translate(100, 50).scale(2);
            input.push().translate(10, 0).rotate((float) Math.toRadians(90));
            input.hit(0, 0, 20, 10, probe, null);
            input.pop();
            input.pop();
        };
        frame(input, draw);
        // local (5, 2): rotate 90 => (-2, 5); +10,0 => (8, 5); *2 +100,50 => (116, 60)
        input.pointer(116, 60, UiInput.PRIMARY);
        frame(input, draw);
        assertEquals(List.of("press null 5,2"), probe.log);
    }

    @Test
    void clipHidesOutside() {
        UiInput input = new UiInput();
        Probe probe = new Probe();
        Runnable draw = () -> {
            input.pushClip(0, 0, 10, 10);
            input.hit(0, 0, 40, 40, probe, null);
            input.popClip();
        };
        frame(input, draw);
        input.pointer(20, 20, UiInput.PRIMARY);
        frame(input, draw);
        assertTrue(probe.log.isEmpty());
        input.pointer(5, 5, 0);
        frame(input, draw);
        input.pointer(5, 5, UiInput.PRIMARY);
        frame(input, draw);
        assertEquals(List.of("press null 5,5"), probe.log);
    }

    @Test
    void topmostWinsAndItemsAreDistinct() {
        UiInput input = new UiInput();
        Probe under = new Probe();
        Probe over = new Probe();
        Runnable draw = () -> {
            input.hit(0, 0, 100, 100, under, null);
            input.hit(0, 0, 50, 10, over, "a");
            input.hit(0, 10, 50, 20, over, "b");
        };
        frame(input, draw);
        input.pointer(5, 15, 0);
        frame(input, draw);
        assertTrue(input.hovered(over, "b"));
        assertFalse(input.hovered(over, "a"));
        input.pointer(5, 15, UiInput.PRIMARY);
        frame(input, draw);
        input.pointer(5, 15, 0);
        frame(input, draw);
        assertEquals(List.of("press b 5,5", "release b true"), over.log);
        assertTrue(under.log.isEmpty());
    }

    @Test
    void captureFollowsPointerOutside() {
        UiInput input = new UiInput();
        Probe probe = new Probe();
        Runnable draw = () -> input.hit(0, 0, 10, 10, probe, "x");
        frame(input, draw);
        input.pointer(5, 5, UiInput.PRIMARY);
        frame(input, draw);
        input.pointer(30, -4, UiInput.PRIMARY);
        frame(input, draw);
        input.pointer(30, -4, 0);
        frame(input, draw);
        assertEquals(List.of("press x 5,5", "drag x 30,-4", "release x false"), probe.log);
    }

    @Test
    void wheelFallsThroughUntilConsumed() {
        UiInput input = new UiInput();
        Probe under = new Probe();
        Probe over = new Probe();
        under.consume = true;
        Runnable draw = () -> {
            input.hit(0, 0, 100, 100, under, "u");
            input.hit(0, 0, 50, 50, over, "o");
        };
        frame(input, draw);
        input.pointer(5, 5, 0);
        input.wheel(1);
        frame(input, draw);
        assertEquals(List.of("wheel o"), over.log);
        assertEquals(List.of("wheel u"), under.log);
    }

    @Test
    void steadyStateAllocatesNothing() {
        UiInput input = new UiInput();
        Interactive sink = new Interactive() {
        };
        String[] items = {"a", "b", "c"};
        Runnable draw = () -> {
            input.push().translate(3, 4).scale(2);
            input.pushClip(0, 0, 500, 500);
            for (int i = 0; i < 200; i++) {
                input.hit(0, i * 10, 100, i * 10 + 9, sink, items[i % 3]);
            }
            input.popClip();
            input.pop();
        };
        for (int i = 0; i < 1000; i++) {
            input.pointer(i % 200, i % 300, i % 7 == 0 ? UiInput.PRIMARY : 0);
            frame(input, draw);
        }
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long before = bean.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < 1000; i++) {
            input.pointer(i % 200, i % 300, i % 7 == 0 ? UiInput.PRIMARY : 0);
            input.wheel(1);
            frame(input, draw);
        }
        assertEquals(0, bean.getCurrentThreadAllocatedBytes() - before);
    }
}
