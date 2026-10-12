package com.wf.wflib.ammo;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmmoCatalogueTest {
    private static ResourceLocation rl(String p) {
        return ResourceLocation.fromNamespaceAndPath("wflib", p);
    }

    private static Map<ResourceLocation, List<AmmoVariant>> byCalibre() {
        return Ammo.variants().stream().collect(Collectors.groupingBy(AmmoVariant::calibre));
    }

    @Test
    void specCalibresWithTheirVariants() {
        Map<ResourceLocation, List<AmmoVariant>> by = byCalibre();
        for (String c : List.of("9x19", "11.43x23", "5.56x45", "5.45x39", "7.62x39", "7.62x51", "7.62x54r",
                "8.6x70", "12.7x99", "12.7x108")) {
            assertNotNull(Ammo.calibre(rl(c)), c);
            assertEquals(rl(c + "/ball"), by.get(rl(c)).getFirst().id(), c + " default = ball");
        }
        for (String c : List.of("5.56x45", "5.45x39", "7.62x39", "7.62x51", "7.62x54r", "12.7x99", "12.7x108")) {
            AmmoVariant t = Ammo.variant(rl(c + "/tracer"));
            assertNotEquals(AmmoVariant.NONE, t.tracer(), c);
            assertTrue(Ammo.variant(rl(c + "/ap")).blockPen() > Ammo.variant(rl(c + "/ball")).blockPen(), c);
        }
        for (String c : List.of("9x19", "5.45x39", "7.62x39")) {
            assertTrue(Ammo.variant(rl(c + "/subsonic")).speed() < 343, c);
        }
        assertEquals(9, Ammo.variant(rl("12ga/buck")).pellets());
        assertNotNull(Ammo.variant(rl("12ga/slug")));
        assertNotNull(Ammo.variant(rl("40x46/he")).explosive());
        for (String c : List.of("20x102", "25x137", "30x173")) {
            assertNotNull(Ammo.variant(rl(c + "/he")).explosive(), c);
            assertEquals(CalibreKind.AUTOCANNON, Ammo.calibre(rl(c)).kind());
        }
        for (String c : List.of("105mm", "120mm", "125mm")) {
            assertNull(Ammo.variant(rl(c + "/apfsds")).explosive(), c);
            assertEquals("shaped_charge", Ammo.variant(rl(c + "/heat")).explosive().warhead().getPath(), c);
            assertNotNull(Ammo.variant(rl(c + "/he")).explosive(), c);
        }
    }

    private static void assertNull(Object o, String m) {
        assertEquals(null, o, m);
    }

    @Test
    void everyCalibreHasAVariantAndAnItemModel() {
        Map<ResourceLocation, List<AmmoVariant>> by = byCalibre();
        for (Calibre c : Ammo.calibres()) {
            assertFalse(by.getOrDefault(c.id(), List.of()).isEmpty(), c.id().toString());
            Path json = Path.of("src/main/resources/assets", c.itemId().getNamespace(), "models/item",
                    c.itemId().getPath() + ".json");
            assertTrue(Files.isRegularFile(json), json.toString());
        }
    }

    @Test
    void variantIdsAreCalibreSlashRole() {
        for (AmmoVariant v : Ammo.variants()) {
            assertTrue(v.id().getPath().startsWith(v.calibre().getPath() + "/"), v.id().toString());
            assertTrue(v.projectileMassKg() > 0 && v.speed() > 0, v.id().toString());
        }
    }

    @Test
    void itemIdSanitised() {
        assertEquals(rl("cartridge_5_56x45"), Ammo.calibre(rl("5.56x45")).itemId());
        assertEquals(rl("cartridge_12ga"), Ammo.calibre(rl("12ga")).itemId());
    }

    @Test
    void duplicateIdNamesBothSources() {
        Calibre c = Ammo.calibre(rl("5.56x45"));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> Ammo.register(c, "packA"));
        assertTrue(e.getMessage().contains("packA") && e.getMessage().contains("wflib"), e.getMessage());
        AmmoVariant v = Ammo.variant(rl("5.56x45/ball"));
        e = assertThrows(IllegalStateException.class, () -> Ammo.register(v, "packB"));
        assertTrue(e.getMessage().contains("packB") && e.getMessage().contains("wflib"), e.getMessage());
        AmmoVariant clash = AmmoVariant.builder(rl("9x19"), rl("9x19"), "x").flight(300, 0.008f, 0).build();
        e = assertThrows(IllegalStateException.class, () -> Ammo.register(clash, "packC"));
        assertTrue(e.getMessage().contains("packC"), e.getMessage());
    }

    @Test
    void ballPenAnchorsAndEnergyScaling() {
        assertEquals(10f, Ammo.variant(rl("5.56x45/ball")).blockPen());
        assertEquals(16f, Ammo.variant(rl("7.62x51/ball")).blockPen());
        assertEquals(40f, Ammo.variant(rl("12.7x99/ball")).blockPen());
        assertTrue(Ammo.variant(rl("9x19/subsonic")).blockPen() < Ammo.variant(rl("9x19/ball")).blockPen());
    }

    @Test
    void quadDragIsConverterFormula() {
        // convert_mineassault.quad_drag(5.70, 4.0, 0.30) = 0.001172214
        assertEquals(0.001172214, AmmoCatalogue.quadDrag(5.70f, 4.0f, 0.30f), 1e-8);
    }
}
