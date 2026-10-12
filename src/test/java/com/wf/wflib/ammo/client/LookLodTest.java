package com.wf.wflib.ammo.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LookLodTest {
    /** 30x173 HE shell, 0.12 x 0.03 blocks: at the switch the sprite is the model's length and diameter. */
    @Test
    void spriteContinuesTheModelAtTheSwitch() {
        double len = 0.12, dia = 0.03, dSwitch = dia / (LookLod.PIXEL * LookLod.MIN_WIDTH);
        assertTrue(LookLod.model(dia, dSwitch * 0.999));
        assertTrue(LookLod.sprite(dia, dSwitch * 1.001));
        assertEquals(len / 2, LookLod.halfLength(len, dSwitch), 1e-9);
        assertEquals(dia / 2, LookLod.halfWidth(dia, dSwitch), 1e-9);
    }

    @Test
    void floorsFarOut() {
        assertEquals(500 * LookLod.PIXEL, LookLod.halfLength(0.12, 500), 1e-9);
        assertEquals(500 * LookLod.PIXEL * 0.75, LookLod.halfWidth(0.03, 500), 1e-9);
        assertTrue(LookLod.sprite(0.03, LookLod.SPRITE_MAX));
        assertFalse(LookLod.sprite(0.03, LookLod.SPRITE_MAX + 1));
    }
}
