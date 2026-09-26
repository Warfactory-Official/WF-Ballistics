package com.wf.wflib.rail.align;

import com.wf.wflib.client.journeymap.rail.MapTransform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The transform the whole map editor sits on.
 *
 * <p>The first test is the important one: it is a real observation from a running client rather than
 * a derivation, and it is what proved that JourneyMap reports {@code blockSize} in framebuffer pixels
 * while reporting {@code displayBounds} in GUI units. Anything that assumes one space throughout
 * passes every symmetry test and still puts clicks in the wrong place.</p>
 */
class MapTransformTest {

    /**
     * Three readings taken from a running client on JourneyMap 6.0.6 at GUI scale 2, each checked
     * against the block {@code ClickEvent.getLocation()} reported for the same click.
     *
     * <p>They are the reason this class does not use {@code UIState.blockBounds} or
     * {@code displayBounds}. The first agreed with a naive origin taken from blockBounds; the other two
     * did not, by 24.5 and 394 blocks, because blockBounds lags a zoom and displayBounds overhangs the
     * window by an amount whose unit could not be pinned down. The window centre can be.</p>
     */
    private static MapTransform reading(double centreBlock, double windowFb, double blockSize) {
        return new MapTransform(centreBlock, centreBlock, windowFb / 2.0, windowFb / 2.0,
                blockSize, 2.0, 0.0, 0.0);
    }

    @Test
    @DisplayName("window 1530, zoom 512: a click at 700 is block -824, as JourneyMap reported")
    void readingOne() {
        assertEquals(-824.0, reading(-759.0, 1530.0, 1.0).toBlockX(700.0), 0.5);
    }

    @Test
    @DisplayName("window 846, zoom 2048: a click at 398 is block 82, where blockBounds said 57.5")
    void readingTwo() {
        assertEquals(82.0, reading(88.0, 846.0, 4.0).toBlockX(398.0), 0.5);
    }

    @Test
    @DisplayName("window 854, zoom 512: a click at 361 is block 21, where blockBounds said -373")
    void readingThree() {
        assertEquals(21.0, reading(88.0, 854.0, 1.0).toBlockX(361.0), 1.0);
    }

    @Test
    @DisplayName("the centre of the window is the block JourneyMap says is under it")
    void centreIsTheCentre() {
        MapTransform t = reading(88.0, 854.0, 4.0);
        assertEquals(88.0, t.toBlockX(427.0), 1.0e-9);
        assertEquals(88.0, t.toBlockZ(427.0), 1.0e-9);
    }

    @Test
    @DisplayName("a point drawn where it was clicked lands back under the cursor")
    void drawingRoundTripsThroughTheGuiScale() {
        MapTransform t = reading(88.0, 846.0, 4.0);
        assertEquals(398.0, t.toScreenX(t.toBlockX(398.0)) * t.guiScale(), 1.0e-6);
        assertEquals(554.0, t.toScreenY(t.toBlockZ(554.0)) * t.guiScale(), 1.0e-6);
    }

    @Test
    @DisplayName("a drag moves what is drawn with the ground, because the centre already includes it")
    void dragMovesTheDrawing() {
        // Same click, and a centre 40 blocks further east because the map has been dragged.
        MapTransform still = reading(88.0, 854.0, 4.0);
        MapTransform dragged = reading(128.0, 854.0, 4.0);
        double block = still.toBlockX(300.0);
        assertEquals(block + 40.0, dragged.toBlockX(300.0), 1.0e-9,
                "the same pixel is 40 blocks further east once the view has moved");
        // A fixed piece of ground moves the other way on screen, which is what "pinned to land" means.
        assertEquals(still.toScreenX(block) - 40.0 * 4.0 / 2.0, dragged.toScreenX(block), 1.0e-9);
    }

    @Test
    @DisplayName("input and output stay consistent at every zoom and GUI scale")
    void roundTripsEverywhere() {
        for (double blockSize : new double[]{2.0 / 512.0, 0.5, 1.0, 32.0}) {
            for (double guiScale : new double[]{1.0, 2.0, 3.0, 4.0}) {
                MapTransform t = new MapTransform(-1234.0, 5678.0, 17.0, -9.0, blockSize, guiScale, 0.0, 0.0);
                for (double mouse : new double[]{0.0, 137.0, 1530.0}) {
                    assertEquals(mouse, t.toScreenX(t.toBlockX(mouse)) * guiScale, 1.0e-6,
                            "x at blockSize " + blockSize + " guiScale " + guiScale);
                    assertEquals(mouse, t.toScreenY(t.toBlockZ(mouse)) * guiScale, 1.0e-6,
                            "y at blockSize " + blockSize + " guiScale " + guiScale);
                }
            }
        }
    }

    @Test
    @DisplayName("a drawn pixel covers 512 blocks at the fullscreen zoom floor, so no width may be in blocks")
    void zoomFloorIsCoarse() {
        // UIState.blockSize = zoom / 512, fullscreen zoom bottoms out at 2, and GUI scale 2 doubles it.
        MapTransform t = new MapTransform(0, 0, 0, 0, 2.0 / 512.0, 2.0, 0.0, 0.0);
        assertEquals(512.0, t.blocksPerPixel(), 1.0e-9);
        assertTrue(5.0 / t.blocksPerPixel() < 0.02, "a 5-block-wide line is invisible here");
    }

    @Test
    @DisplayName("the sample step never asks for more segments than there are pixels to draw them in")
    void sampleStepFollowsZoom() {
        assertEquals(1024.0, new MapTransform(0, 0, 0, 0, 2.0 / 512.0, 2.0, 0.0, 0.0).sampleStep(), 1.0e-9);
        assertEquals(1.0, new MapTransform(0, 0, 0, 0, 32.0, 2.0, 0.0, 0.0).sampleStep(), 1.0e-9,
                "zoomed right in, one block per sample is the floor");
    }
}
