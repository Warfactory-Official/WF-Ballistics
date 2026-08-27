package com.wf.wfballistics.debug;

import com.mojang.authlib.GameProfile;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * A player that is standing there for a swarm to come and find.
 *
 * <p>A headless bench has nobody logged in, and for glyphids that is not a small difference. Everything about
 * what a swarm does when it arrives runs off a player being present: {@code findTargetCandidate} looks for one
 * before it looks for prey, an assigned squad objective names one, the sim tier keeps a glyphid as a real
 * entity because one is nearby, and the chunks around one tick. A benchmark with no player in it measures a
 * swarm marching at a coordinate — which is worth measuring, and is not the same scenario as a base assault.
 *
 * <p>{@link EntityDebugDummy} is not a substitute. A dummy is prey, found by a different search at a quarter
 * of the range, and it is not what a squad is pointed at. The distinction is the thing under test, so the
 * stand-in has to be an actual {@code ServerPlayer}.
 *
 * <p>Added to the level with {@code addFreshEntity}, which is the path that lands it in {@code level.players()}
 * and in the chunk map's player list — so it draws chunk tickets and is tracked exactly as a logged-in player
 * is. It is not in the server's player list, so {@code /list} still reports nobody: the server does not think
 * anyone has joined, and only the level does.
 *
 * <p>Two consequences worth knowing before reading numbers off a run that uses one. Every glyphid in range
 * becomes a tracked entity with movement packets built for it, so a timing arm with a bench player in it is
 * not comparable with one without — that cost is real, and a real player would pay it too. And with a player
 * present, natural mob spawning near the arena is live unless the run turns it off.
 */
public class BenchPlayer extends FakePlayer {

    private static final List<BenchPlayer> PLACED = new ArrayList<>();

    private int hits;
    private float damageOffered;

    private BenchPlayer(ServerLevel level, GameProfile profile) {
        super(level, profile);
    }

    /**
     * Stand a bench player at a position, replacing any of the same name.
     *
     * <p>The uuid is derived from the name so a rerun reuses it rather than leaving a fresh set of stats and
     * advancement files behind on every arena build.
     */
    public static BenchPlayer place(ServerLevel level, String name, Vec3 at) {
        remove(name);
        UUID id = UUID.nameUUIDFromBytes(("WFBenchPlayer:" + name).getBytes(StandardCharsets.UTF_8));
        BenchPlayer player = new BenchPlayer(level, new GameProfile(id, name));
        player.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        player.setHealth(player.getMaxHealth());
        level.addFreshEntity(player);
        PLACED.add(player);
        return player;
    }

    /**
     * Counted, not applied.
     *
     * <p>A defender who dies ends the scenario a few seconds in and takes the swarm's target with them, which
     * measures how fast two glyphids kill a man rather than what three hundred of them do to a base. Damage
     * offered is banked instead, so the arm keeps a steady state and still says how hard the swarm hit.
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        hits++;
        damageOffered += amount;
        return true;
    }

    public int hits() {
        return hits;
    }

    public float damageOffered() {
        return damageOffered;
    }

    public void resetCounters() {
        hits = 0;
        damageOffered = 0.0F;
    }

    public static List<BenchPlayer> placed() {
        return PLACED;
    }

    private static void remove(String name) {
        for (int i = PLACED.size() - 1; i >= 0; i--) {
            BenchPlayer player = PLACED.get(i);
            if (player.getGameProfile().getName().equals(name)) {
                player.discard();
                PLACED.remove(i);
            }
        }
    }

    /** Take every bench player back out of the world. */
    public static int clear() {
        int removed = PLACED.size();
        for (BenchPlayer player : PLACED) {
            player.discard();
        }
        PLACED.clear();
        return removed;
    }

    // --- commands ---

    public static int add(CommandSourceStack source, String name, Vec3 at) {
        BenchPlayer player = place(source.getLevel(), name, at);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Bench player %s standing at (%.1f, %.1f, %.1f).",
                player.getGameProfile().getName(), at.x, at.y, at.z)), false);
        return 1;
    }

    public static int clear(CommandSourceStack source) {
        int removed = clear();
        source.sendSuccess(() -> Component.literal("Removed " + removed + " bench players."), false);
        return removed;
    }

    public static int report(CommandSourceStack source) {
        if (PLACED.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No bench players."), false);
            return 0;
        }
        for (BenchPlayer player : PLACED) {
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                    "  %s at (%.1f, %.1f, %.1f): %d hits, %.1f damage offered",
                    player.getGameProfile().getName(), player.getX(), player.getY(), player.getZ(),
                    player.hits(), player.damageOffered())), false);
        }
        return PLACED.size();
    }
}
