package com.wf.wflib.round.client;

import com.wf.wflib.kinetic.KineticPreset;
import com.wf.wflib.kinetic.KineticPresetRegistry;
import com.wf.wflib.round.RoundEnd;
import com.wf.wflib.round.RoundNetwork;
import com.wf.wflib.warhead.WarheadRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientRoundsTest {

    private static final ResourceLocation PRESET = KineticPresetRegistry.rl("test_client_round");
    private static final List<String> EVENTS = new ArrayList<>();

    @BeforeAll
    static void setUp() {
        KineticPresetRegistry.register(KineticPreset.builder(PRESET, null, WarheadRegistry.rl("inert"))
                .speed(4.0).drag(0.0).gravity(0.0).life(40).impactDamage(1.0).caliber(5.56).noChunkLoading().build());
        ClientRounds.addObserver(new RoundObserver() {
            @Override
            public void tick(ClientRounds.Round round) {
                EVENTS.add("tick " + round.key() + " " + round.position());
            }

            @Override
            public void ended(ClientRounds.Round round, Vec3 at, RoundEnd reason) {
                EVENTS.add("end " + round.key() + " " + reason);
            }
        });
    }

    private static RoundNetwork.Spawn spawn(long key, double y, boolean resting) {
        return new RoundNetwork.Spawn(key, PRESET, 0.0, y, 0.0, 0.0, -1.0, 0.0, 40, resting, 7, (int) key);
    }

    /** Spawn and end in separate packets, both before a client tick: one tick to the end point, then the end. */
    @Test
    void anEndBeforeTheFirstTickIsReplayed() {
        EVENTS.clear();
        ClientRounds.apply(new RoundNetwork.RoundPacket(List.of(spawn(101, 10.0, false)), List.of(), List.of()));
        ClientRounds.apply(new RoundNetwork.RoundPacket(List.of(),
                List.of(new RoundNetwork.End(101, 0.0, 9.0, 0.0, RoundEnd.BLOCK)), List.of()));
        assertEquals(List.of(), EVENTS, "ended before any tick");
        ClientRounds.tick(null);
        assertEquals(List.of("tick 101 (0.0, 9.0, 0.0)", "end 101 BLOCK"), EVENTS);
    }

    /** A landing re-sync updates the round in place: tracers holding it see the new state and its end. */
    @Test
    void aResyncKeepsTheRoundObject() {
        EVENTS.clear();
        ClientRounds.apply(new RoundNetwork.RoundPacket(List.of(spawn(102, 10.0, true)), List.of(), List.of()));
        ClientRounds.tick(null);
        ClientRounds.Round held = ClientRounds.byShot(7, 102).get(0);
        ClientRounds.apply(new RoundNetwork.RoundPacket(List.of(spawn(102, 5.0, true)), List.of(), List.of()));
        assertSame(held, ClientRounds.byShot(7, 102).get(0));
        assertEquals(5.0, held.position().y);
        ClientRounds.apply(new RoundNetwork.RoundPacket(List.of(),
                List.of(new RoundNetwork.End(102, 0.0, 5.0, 0.0, RoundEnd.FUSE)), List.of()));
        assertEquals(List.of(), ClientRounds.byShot(7, 102));
        assertEquals("end 102 FUSE", EVENTS.get(EVENTS.size() - 1));
    }

    /** A prediction keeps its own flight under the server key; its server copy is never drawn twice. */
    @Test
    void theServerRoundAdoptsThePrediction() {
        EVENTS.clear();
        ClientRounds.predict(PRESET, 7, 500, new Vec3(1, 2, 3), new Vec3(0, 0, 4), 5);
        ClientRounds.Round predicted = ClientRounds.byShot(7, 500).get(0);
        assertEquals(true, predicted.key() < 0, "local key");
        ClientRounds.apply(new RoundNetwork.RoundPacket(List.of(
                new RoundNetwork.Spawn(9001, PRESET, 0.0, 50.0, 0.0, 0.0, 0.0, 4.0, 40, false, 7, 500)), List.of(), List.of()));
        List<ClientRounds.Round> shot = ClientRounds.byShot(7, 500);
        assertEquals(1, shot.size(), "server copy drawn beside the prediction");
        assertSame(predicted, shot.get(0));
        assertEquals(9001, predicted.key());
        assertEquals(new Vec3(1, 2, 3), predicted.position(), "prediction snapped to the lagging server state");
        ClientRounds.apply(new RoundNetwork.RoundPacket(List.of(),
                List.of(new RoundNetwork.End(9001, 0.0, 9.0, 0.0, RoundEnd.ENTITY)), List.of()));
        ClientRounds.tick(null);
        assertEquals(List.of(), ClientRounds.byShot(7, 500));
        assertEquals("end 9001 ENTITY", EVENTS.get(EVENTS.size() - 1));
    }

    /** Server spread: the adopted prediction takes the server velocity now and eases onto its line (one round). */
    @Test
    void aPredictionOnAnotherLineIsReaimed() {
        EVENTS.clear();
        ClientRounds.predict(PRESET, 7, 700, new Vec3(1, 2, 3), new Vec3(0, 0, 4), 5);
        ClientRounds.Round predicted = ClientRounds.byShot(7, 700).get(0);
        ClientRounds.apply(new RoundNetwork.RoundPacket(List.of(
                new RoundNetwork.Spawn(9003, PRESET, 1.0, 2.0, 3.0, 0.4, 0.0, 4.0, 40, false, 7, 700)), List.of(), List.of()));
        List<ClientRounds.Round> shot = ClientRounds.byShot(7, 700);
        assertEquals(1, shot.size());
        assertSame(predicted, shot.get(0));
        assertEquals(new Vec3(0.4, 0.0, 4.0), predicted.velocity(), "server direction taken");
        assertEquals(ClientRounds.CORRECT_TICKS, predicted.corrTicks);
        ClientRounds.apply(new RoundNetwork.RoundPacket(List.of(),
                List.of(new RoundNetwork.End(9003, 0.0, 9.0, 0.0, RoundEnd.ENTITY)), List.of()));
        ClientRounds.tick(null);
        assertEquals("end 9003 ENTITY", EVENTS.get(EVENTS.size() - 1), "server end kept");
    }

    /** A prediction that already stopped on a block, launched on another line: the server round is drawn after all. */
    @Test
    void anEndedPredictionOnAnotherLineLeavesTheServerRound() {
        EVENTS.clear();
        ClientRounds.predict(PRESET, 7, 800, new Vec3(0, 0, 0), new Vec3(0, 0, 4), 5);
        ClientRounds.Round predicted = ClientRounds.byShot(7, 800).get(0);
        predicted.ended = true;
        ClientRounds.live().remove(predicted);
        ClientRounds.apply(new RoundNetwork.RoundPacket(List.of(
                new RoundNetwork.Spawn(9004, PRESET, 0.0, 0.0, 0.0, 0.4, 0.0, 4.0, 40, true, 7, 800)), List.of(), List.of()));
        List<ClientRounds.Round> shot = ClientRounds.byShot(7, 800);
        assertTrue(shot.size() == 1 && shot.get(0).key() == 9004, "server round swallowed: " + shot);
        ClientRounds.apply(new RoundNetwork.RoundPacket(List.of(),
                List.of(new RoundNetwork.End(9004, 0.0, 9.0, 0.0, RoundEnd.ENTITY)), List.of()));
        ClientRounds.tick(null);
        assertEquals("end 9004 ENTITY", EVENTS.get(EVENTS.size() - 1));
    }

    /** No server round within the window (shot refused): the prediction goes; a late spawn is an ordinary round. */
    @Test
    void anUnadoptedPredictionExpires() {
        EVENTS.clear();
        ClientRounds.predict(PRESET, 7, 600, new Vec3(0, 0, 0), new Vec3(0, 0, 4), 1);
        ClientRounds.tick(null);
        assertEquals(List.of(), ClientRounds.byShot(7, 600));
        assertEquals(1, EVENTS.size());
        assertTrue(EVENTS.get(0).startsWith("end -") && EVENTS.get(0).endsWith(" CLEARED"), EVENTS.get(0));
        ClientRounds.apply(new RoundNetwork.RoundPacket(List.of(
                new RoundNetwork.Spawn(9002, PRESET, 0.0, 50.0, 0.0, 0.0, 0.0, 4.0, 40, true, 7, 600)), List.of(), List.of()));
        assertEquals(9002, ClientRounds.byShot(7, 600).get(0).key());
        ClientRounds.apply(new RoundNetwork.RoundPacket(List.of(),
                List.of(new RoundNetwork.End(9002, 0.0, 9.0, 0.0, RoundEnd.ENTITY)), List.of()));
        ClientRounds.tick(null);
    }
}
