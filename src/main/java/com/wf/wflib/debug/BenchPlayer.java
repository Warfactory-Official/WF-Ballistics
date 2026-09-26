package com.wf.wflib.debug;

import com.mojang.authlib.GameProfile;
import com.wf.wflib.drone.cam.CameraChunkStream;
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

/** A player that is standing there for a swarm to come and find. */
public class BenchPlayer extends FakePlayer {

    private static final List<BenchPlayer> PLACED = new ArrayList<>();

    private int hits;
    private float damageOffered;

    private BenchPlayer(ServerLevel level, GameProfile profile) {
        super(level, profile);
    }

    /** Stand a bench player at a position, replacing any of the same name. */
    public static BenchPlayer place(ServerLevel level, String name, Vec3 at) {
        remove(name);
        UUID id = UUID.nameUUIDFromBytes(("WFBenchPlayer:" + name).getBytes(StandardCharsets.UTF_8));
        BenchPlayer player = new BenchPlayer(level, new GameProfile(id, name));
        player.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        player.setHealth(player.getMaxHealth());
        level.addFreshEntity(player);
        PLACED.add(player);
        CameraChunkStream.setCapable(player.getUUID(), true);
        return player;
    }

    /** Counted, not applied. */
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
                CameraChunkStream.forget(player);
                com.wf.wflib.stream.ChunkStreams.drop(player);
                player.discard();
                PLACED.remove(i);
            }
        }
    }

    /** Take every bench player back out of the world. */
    public static int clear() {
        int removed = PLACED.size();
        for (BenchPlayer player : PLACED) {
            CameraChunkStream.forget(player);
                com.wf.wflib.stream.ChunkStreams.drop(player);
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
