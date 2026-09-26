package com.wf.wflib.round.client;

import com.wf.wflib.WFLib;
import com.wf.wflib.client.flywheel.FlywheelEffectManager;
import com.wf.wflib.kinetic.KineticPreset;
import com.wf.wflib.kinetic.KineticPresetRegistry;
import com.wf.wflib.round.RoundNetwork;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.Collection;

/** Client copies of the server's rounds: same recurrence, no collision; removed by the server's end batch. */
@EventBusSubscriber(modid = WFLib.MODID, value = Dist.CLIENT)
public final class ClientRounds {

    private static final Long2ObjectMap<Round> LIVE = new Long2ObjectOpenHashMap<>();
    private static ClientLevel effectLevel;

    private ClientRounds() {
    }

    public static final class Round {
        final long key;
        final KineticPreset preset;
        double x, y, z, px, py, pz, vx, vy, vz;
        int life;
        boolean resting;

        Round(long key, KineticPreset preset, RoundNetwork.Spawn s) {
            this.key = key;
            this.preset = preset;
            this.x = this.px = s.x();
            this.y = this.py = s.y();
            this.z = this.pz = s.z();
            this.vx = s.vx();
            this.vy = s.vy();
            this.vz = s.vz();
            this.life = s.life();
            this.resting = s.resting();
        }

        double ix(float pt) {
            return this.px + (this.x - this.px) * pt;
        }

        double iy(float pt) {
            return this.py + (this.y - this.py) * pt;
        }

        double iz(float pt) {
            return this.pz + (this.z - this.pz) * pt;
        }
    }

    public static void accept(RoundNetwork.RoundPacket pkt) {
        for (RoundNetwork.Spawn s : pkt.spawns()) {
            KineticPreset preset = KineticPresetRegistry.get(s.preset());
            if (preset != null) {
                LIVE.put(s.key(), new Round(s.key(), preset, s));
            }
        }
        for (long key : pkt.ends()) {
            Round r = LIVE.remove(key);
            if (r != null) {
                ended(r);
            }
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null && level != effectLevel && FlywheelEffectManager.isAvailable(level)) {
            effectLevel = level;
            FlywheelEffectManager.spawn(new RoundsEffect(level));
        }
    }

    static Collection<Round> live() {
        return LIVE.values();
    }

    static boolean alive(ClientLevel level) {
        return level == effectLevel;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            LIVE.values().forEach(ClientRounds::ended);
            LIVE.clear();
            return;
        }
        if (mc.isPaused()) {
            return;
        }
        LIVE.values().removeIf(r -> {
            if (step(mc.level, r)) {
                ended(r);
                return true;
            }
            RoundRenderer renderer = RoundRenderers.get(r.preset.id());
            if (renderer != null) {
                renderer.tick(r.key, new Vec3(r.x, r.y, r.z), new Vec3(r.vx, r.vy, r.vz), r.resting);
            }
            return false;
        });
    }

    private static void ended(Round r) {
        RoundRenderer renderer = RoundRenderers.get(r.preset.id());
        if (renderer != null) {
            renderer.ended(r.key);
        }
    }

    /** One tick of the server recurrence. @return true once expired */
    private static boolean step(ClientLevel level, Round r) {
        if (--r.life <= 0) {
            return true;
        }
        r.px = r.x;
        r.py = r.y;
        r.pz = r.z;
        if (r.resting) {
            return false;
        }
        boolean wet = !level.getFluidState(BlockPos.containing(r.x, r.y, r.z)).isEmpty();
        r.x += r.vx;
        r.y += r.vy;
        r.z += r.vz;
        double decay = r.preset.decay(Math.sqrt(r.vx * r.vx + r.vy * r.vy + r.vz * r.vz));
        float gravity = r.preset.gravity();
        if (wet) {
            decay = (double) (1.0f - Math.max(r.preset.waterDrag(), 0.0f));
            gravity *= r.preset.waterGravityFactor();
        }
        r.vx *= decay;
        r.vy = r.vy * decay - gravity;
        r.vz *= decay;
        return false;
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        LIVE.values().forEach(ClientRounds::ended);
        LIVE.clear();
        effectLevel = null;
    }
}
