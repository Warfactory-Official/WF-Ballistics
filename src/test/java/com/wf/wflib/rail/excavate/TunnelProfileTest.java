package com.wf.wflib.rail.excavate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tunnel section is a drawing, and a drawing is the sort of thing people get wrong.
 *
 * <p>Every check here is one a profile author would otherwise discover from the world: a missing wall
 * cell as a flooded tunnel, a floating torch as a dark one. Refusing to load the file and saying which
 * cell is wrong is the whole reason validation happens at load rather than at carve.</p>
 */
class TunnelProfileTest {

    private static final List<String> ARCHED = List.of(
            "  ######  ",
            " #......# ",
            "#........#",
            "#........#",
            "#L......L#",
            "##########");

    @Test
    @DisplayName("a drawn section reports the size of the hole, not the size of the drawing")
    void measuresTheTunnelNotThePicture() {
        TunnelProfile profile = TunnelProfile.parse("arched", ARCHED);
        assertEquals(10, profile.width(), "the drawing is ten across");
        assertEquals(8, profile.boreWidth(), "the tunnel inside it is eight");
        assertEquals(4, profile.boreHeight());
        assertEquals(4, profile.floorRow(), "the lowest row with tunnel in it is the floor");
    }

    @Test
    @DisplayName("the floor row is y=0, so standing in the tunnel puts you at the y you asked for")
    void theFloorIsTheFloor() {
        TunnelProfile profile = TunnelProfile.parse("arched", ARCHED);
        assertEquals(TunnelProfile.Kind.TORCH, profile.at(1, 0), "a torch sits on the floor");
        assertEquals(TunnelProfile.Kind.LINING, profile.at(1, -1), "and the course below it is wall");
        assertEquals(TunnelProfile.Kind.BORE, profile.at(4, 3), "the roof course is still tunnel");
        assertEquals(TunnelProfile.Kind.NONE, profile.at(0, 3), "and its corners are untouched ground");
    }

    @Test
    @DisplayName("a section with a hole in its wall is refused, and the cell is named")
    void refusesAnUnsealedSection() {
        List<String> leaky = List.of(
                "######",
                "#....#",
                "#... #",
                "######");
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> TunnelProfile.parse("leaky", leaky));
        assertTrue(thrown.getMessage().contains("not sealed"), thrown.getMessage());
    }

    @Test
    @DisplayName("a section open at the top is refused too, which is the easiest one to draw by accident")
    void refusesAnOpenRoof() {
        List<String> open = List.of(
                "#....#",
                "#....#",
                "######");
        assertThrows(IllegalArgumentException.class, () -> TunnelProfile.parse("open", open));
    }

    @Test
    @DisplayName("a torch with nothing under it is refused rather than left to pop off")
    void refusesAFloatingTorch() {
        List<String> floating = List.of(
                "######",
                "#L...#",
                "#....#",
                "######");
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> TunnelProfile.parse("floating", floating));
        assertTrue(thrown.getMessage().contains("stand on"), thrown.getMessage());
    }

    @Test
    @DisplayName("headers set the name, the lining and the torch interval")
    void readsHeaders() {
        TunnelProfile profile = TunnelProfile.parse("file", List.of(
                "@name service adit",
                "@lining minecraft:stone_bricks",
                "@torch 12",
                "####",
                "#L.#",
                "####"));
        assertEquals("service adit", profile.name());
        assertEquals("minecraft:stone_bricks", profile.liningOr("minecraft:deepslate_bricks"));
        assertEquals(12, profile.torchSpacing());
    }

    @Test
    @DisplayName("a section that does not name a lining takes the one it is given")
    void liningFallsBack() {
        TunnelProfile profile = TunnelProfile.parse("plain", List.of("####", "#..#", "####"));
        assertEquals("minecraft:deepslate_bricks",
                profile.liningOr("minecraft:deepslate_bricks"));
    }

    @Test
    @DisplayName("the box section is sealed, the size it says, and lit along its floor")
    void boxIsAValidSection() {
        TunnelProfile box = TunnelProfile.box(6, 6, "minecraft:deepslate_bricks", 8);
        assertNull(box.findLeak(), "a generated box must satisfy the same rule a drawn one does");
        assertNull(box.findUnsupportedTorch());
        assertEquals(6, box.boreWidth());
        assertEquals(6, box.boreHeight());
        assertEquals(2, box.torchCells().size(), "one each side, on the floor");
        for (int[] cell : box.torchCells()) {
            assertEquals(0, cell[1], "torches go on the floor course");
        }
    }

    @Test
    @DisplayName("an unlit box has no torch cells at all")
    void unlitBoxHasNoTorches() {
        assertTrue(TunnelProfile.box(6, 6, "minecraft:deepslate_bricks", 0).torchCells().isEmpty());
    }

    @Test
    @DisplayName("every section that ships parses and is sealed")
    void builtInsAreValid() {
        for (String source : TunnelProfiles.builtIn().values()) {
            TunnelProfile profile = TunnelProfile.parse("built-in", List.of(source.split("\n")));
            assertNotNull(profile);
            assertNull(profile.findLeak(), profile.name());
            assertNull(profile.findUnsupportedTorch(), profile.name());
            assertTrue(profile.torchSpacing() > 0, profile.name() + " ships unlit");
        }
    }
}
