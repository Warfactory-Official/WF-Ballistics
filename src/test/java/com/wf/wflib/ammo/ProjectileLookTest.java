package com.wf.wflib.ammo;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectileLookTest {
    private static ProjectileLook look(String variant) {
        AmmoVariant v = Ammo.variant(ResourceLocation.fromNamespaceAndPath("wflib", variant));
        return ProjectileLook.resolve(Ammo.calibre(v.calibre()), v);
    }

    @Test
    void visibleFromTwentyMillimetresGrenadesAndRockets() {
        assertFalse(look("5.56x45/tracer").visible());
        assertFalse(look("12.7x99/ball").visible());
        assertFalse(look("12ga/slug").visible());
        assertTrue(look("40x46/he").visible());
        assertTrue(look("20x102/ap").visible());
        assertTrue(look("30x173/he").visible());
        assertTrue(look("120mm/he").visible());
        assertTrue(look("rpg7/heat").visible());
    }

    @Test
    void shapesByRole() {
        assertEquals(ProjectileLook.Shape.GRENADE, look("40x46/he").shape());
        assertEquals(ProjectileLook.Shape.SHELL, look("30x173/he").shape());
        assertEquals(ProjectileLook.Shape.DART, look("120mm/apfsds").shape());
        assertEquals(ProjectileLook.Shape.FINNED, look("120mm/heat").shape());
        assertEquals(ProjectileLook.Shape.SHELL, look("120mm/he").shape());
        assertEquals(ProjectileLook.Shape.ROCKET, look("rpg7/heat").shape());
        assertTrue(look("rpg7/heat").flame() && look("rpg7/heat").trail());
        assertFalse(look("40x46/he").trail());
        ProjectileLook dart = look("120mm/apfsds");
        assertEquals(120 * 6.5f, dart.lengthMm(), 1e-3);
        assertEquals(120 * 0.22f, dart.diameterMm(), 1e-3);
    }

    @Test
    void flameOnlyWithAMotor() {
        assertTrue(look("rpg7/heat").flame());
        assertFalse(look("rpg7/frag").flame(), "OG-7V: no sustainer");
        assertFalse(look("40x46/he").flame());
        AmmoVariant v = AmmoVariant.builder(ResourceLocation.fromNamespaceAndPath("t", "y"),
                        ResourceLocation.fromNamespaceAndPath("wflib", "40x46"), "y").flight(76, 0.23f, 0)
                .look(new ProjectileLook.Override(null, null, null, null, null, null, null, null, null, true)).build();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ProjectileLook.resolve(Ammo.calibre(v.calibre()), v));
        assertEquals("look flame without a motor", e.getMessage());
    }

    @Test
    void overrideWins() {
        AmmoVariant v = AmmoVariant.builder(ResourceLocation.fromNamespaceAndPath("t", "x"),
                        ResourceLocation.fromNamespaceAndPath("wflib", "5.56x45"), "x").flight(900, 0.004f, 0)
                .look(ProjectileLook.Override.visible(true)).build();
        ProjectileLook l = ProjectileLook.resolve(Ammo.calibre(v.calibre()), v);
        assertTrue(l.visible());
        assertEquals(ProjectileLook.Shape.BULLET, l.shape());
    }
}
