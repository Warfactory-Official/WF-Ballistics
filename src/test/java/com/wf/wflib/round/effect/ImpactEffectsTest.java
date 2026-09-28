package com.wf.wflib.round.effect;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wf.wflib.api.Threat;
import com.wf.wflib.api.ThreatKind;
import com.wf.wflib.kinetic.KineticPreset;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImpactEffectsTest {

    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("wflib", "test_preset");

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static KineticPreset.Builder preset() {
        return KineticPreset.builder(ID, null, ResourceLocation.fromNamespaceAndPath("wflib", "inert"));
    }

    @Test
    void unknownIdChanceAndParamsRefused() {
        assertThrows(IllegalArgumentException.class, () -> ImpactEffects.bind(ImpactEffects.rl("nope"),
                ImpactTrigger.HIT, 1.0, new JsonObject()));
        assertThrows(IllegalArgumentException.class, () -> ImpactEffects.bind(ImpactEffects.IGNITE,
                ImpactTrigger.HIT, 0.0, new JsonObject()));
        assertThrows(IllegalArgumentException.class, () -> ImpactEffects.bind(ImpactEffects.IGNITE,
                ImpactTrigger.HIT, 1.5, new JsonObject()));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ImpactEffects.bind(
                ImpactEffects.IGNITE, ImpactTrigger.HIT, 1.0, json("{\"secs\": 3}")));
        assertTrue(e.getMessage().contains("secs"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> ImpactEffects.bind(ImpactEffects.IGNITE,
                ImpactTrigger.HIT, 1.0, json("{\"seconds\": \"3\"}")));
    }

    @Test
    void damageNeedsTypeAmountAndAHit() {
        JsonObject ok = json("{\"type\": \"minecraft:in_fire\", \"amount\": 2}");
        ImpactEffects.bind(ImpactEffects.DAMAGE, ImpactTrigger.HIT, 1.0, ok);
        assertThrows(IllegalArgumentException.class, () -> ImpactEffects.bind(ImpactEffects.DAMAGE,
                ImpactTrigger.BLOCK, 1.0, ok));
        assertThrows(IllegalArgumentException.class, () -> ImpactEffects.bind(ImpactEffects.DAMAGE,
                ImpactTrigger.HIT, 1.0, json("{\"amount\": 2}")));
        assertThrows(IllegalArgumentException.class, () -> ImpactEffects.bind(ImpactEffects.DAMAGE,
                ImpactTrigger.HIT, 1.0, json("{\"type\": \"in_fire\", \"amount\": 2}")));
        assertThrows(IllegalArgumentException.class, () -> ImpactEffects.bind(ImpactEffects.DAMAGE,
                ImpactTrigger.HIT, 1.0, json("{\"type\": \"minecraft:in_fire\", \"amount\": 0}")));
    }

    @Test
    void thermalAddsPenAndMultipliesWear() {
        ImpactEffects.Bound b = ImpactEffects.bind(ImpactEffects.THERMAL, ImpactTrigger.HIT, 1.0,
                json("{\"pen\": 12, \"wear\": 3}"));
        Threat t = b.effect().threat(null, new Threat(ThreatKind.KINETIC, 7.62f, 8.0f));
        assertEquals(new Threat(ThreatKind.SMALL_ARMS, 7.62f, 20.0f, 3.0f), t);
        assertEquals(1.0f, new Threat(ThreatKind.HE, 30.0f, 0.0f).wearFactor());
        assertThrows(IllegalArgumentException.class, () -> ImpactEffects.bind(ImpactEffects.THERMAL,
                ImpactTrigger.END, 1.0, new JsonObject()));
        assertThrows(IllegalArgumentException.class, () -> ImpactEffects.bind(ImpactEffects.THERMAL,
                ImpactTrigger.HIT, 1.0, json("{\"wear\": 0.5}")));
    }

    @Test
    void presetMasksItsTriggers() {
        KineticPreset none = preset().build();
        for (ImpactTrigger t : ImpactTrigger.VALUES) {
            assertFalse(none.hasEffects(t));
        }
        KineticPreset two = preset().effect(ImpactEffects.IGNITE, ImpactTrigger.PIERCE_OUT, 1.0, new JsonObject())
                .effect(ImpactEffects.IGNITE, ImpactTrigger.END, 0.5, new JsonObject()).build();
        assertTrue(two.hasEffects(ImpactTrigger.PIERCE_OUT) && two.hasEffects(ImpactTrigger.END));
        assertFalse(two.hasEffects(ImpactTrigger.HIT) || two.hasEffects(ImpactTrigger.BLOCK)
                || two.hasEffects(ImpactTrigger.PIERCE_IN));
        assertEquals(List.of(ImpactTrigger.PIERCE_OUT, ImpactTrigger.END),
                two.effects().stream().map(ImpactEffects.Bound::on).toList());
    }

    @Test
    void chanceRolledPerTriggerCertainNeverRolled() {
        AtomicInteger hits = new AtomicInteger();
        ResourceLocation id = ImpactEffects.rl("test_count");
        ImpactEffects.register(id, ctx -> hits.incrementAndGet());
        assertThrows(IllegalStateException.class, () -> ImpactEffects.register(id, ctx -> {
        }));
        assertThrows(IllegalArgumentException.class, () -> ImpactEffects.bind(id, ImpactTrigger.HIT, 1.0,
                json("{\"x\": 1}")));
        KineticPreset p = preset().effect(id, ImpactTrigger.HIT, 0.25, new JsonObject())
                .effect(id, ImpactTrigger.HIT, 1.0, new JsonObject()).build();
        RandomSource random = RandomSource.create(42L);
        int fired = 0;
        int n = 20000;
        for (int k = 0; k < n; k++) {
            fired += ImpactEffects.roll(p, ImpactTrigger.HIT, random).size();
        }
        double partial = (fired - n) / (double) n;
        assertTrue(Math.abs(partial - 0.25) < 0.015, "chance 0.25 fired " + partial);
        assertTrue(ImpactEffects.roll(p, ImpactTrigger.END, random).isEmpty());
        RandomSource untouched = RandomSource.create(7L);
        KineticPreset certain = preset().effect(id, ImpactTrigger.HIT, 1.0, new JsonObject()).build();
        ImpactEffects.roll(certain, ImpactTrigger.HIT, untouched);
        assertEquals(RandomSource.create(7L).nextLong(), untouched.nextLong());
    }
}
