package com.wf.wflib.audio;

import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoundSpecTest {
    private static final ResourceLocation BOOM = ResourceLocation.fromNamespaceAndPath("wflib", "boom");

    @Test
    void gainIsFlatToRefThenFallsToZeroAtRange() {
        SoundSpec s = SoundSpec.of(BOOM, 0.8F, 200);
        assertEquals(0.8F, s.gain(0), 0);
        assertEquals(0.8F, s.gain(30), 1e-6);
        assertEquals(0, s.gain(200), 0);
        assertEquals(0, s.gain(10_000), 0);
        float prev = s.gain(30);
        for (int d = 31; d < 200; d++) {
            float g = s.gain(d);
            assertTrue(g < prev && g > 0, "monotone at " + d);
            prev = g;
        }
    }

    @Test
    void farLayerFadesInBelowFrom() {
        SoundSpec s = SoundSpec.of(BOOM, 1, 900).withRange(100, 900);
        assertEquals(0, s.gain(50), 0);
        assertEquals(0.5F, s.gain(75), 1e-6);
        assertEquals(1, s.gain(100), 1e-6);
        assertEquals(50, s.audibleFrom(), 0);
    }

    @Test
    void jsonStringOrObject() {
        SoundSpec bare = SoundSpec.fromJson(JsonParser.parseString("\"wflib:boom\""));
        assertEquals(SoundSpec.of(BOOM), bare);
        SoundSpec full = SoundSpec.fromJson(JsonParser.parseString(
                "{\"event\":\"wflib:boom\",\"volume\":0.5,\"range\":300,\"from\":40,\"pitch_random\":0.1}"));
        assertEquals(new SoundSpec(BOOM, 0.5F, 1, 0.1F, 300, 0, 40), full);
    }

    @Test
    void rangeMustExceedFrom() {
        assertThrows(IllegalArgumentException.class, () -> SoundSpec.of(BOOM).withRange(20, 20));
    }

    @Test
    void curveInterpolatesAndClamps() {
        Curve c = Curve.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE,
                JsonParser.parseString("[[0.2, 0], [0.6, 1], [1, 0.5]]")).getOrThrow();
        assertEquals(0, c.at(-1), 0);
        assertEquals(0.5F, c.at(0.4F), 1e-6);
        assertEquals(0.75F, c.at(0.8F), 1e-6);
        assertEquals(0.5F, c.at(3), 0);
        Curve k = Curve.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, JsonParser.parseString("0.7"))
                .getOrThrow();
        assertEquals(0.7F, k.at(42), 0);
    }

    @Test
    void engineSoundDefaults() {
        EngineSound e = EngineSound.fromJson(JsonParser.parseString(
                "{\"start\":\"wflib:boom\",\"loops\":[{\"sound\":{\"event\":\"wflib:boom\",\"range\":120}}]}"));
        assertEquals(EngineSound.Input.RPM, e.loops().get(0).input());
        assertEquals(1, e.loops().get(0).volume().at(0.3F), 0);
        assertEquals(120, e.reach(), 0);
    }
}
