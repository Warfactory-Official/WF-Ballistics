package com.wf.wflib.client.flywheel;

import com.wf.gemrender.particle.ParticleBuffer;
import com.wf.gemrender.particle.ParticleStyle;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Every appearance WFLib asks GemRender's particle instancer for. */
public final class WFParticleStyles {

    /**
     * Flame's horizontal drag with no vertical drag at all: {@code xd *= 0.9; zd *= 0.9;} while {@code yd} is only
     * ever added to.
     */
    private static final float DRAG_09 = ParticleStyle.dragFromPerTickFactor(0.9F);

    /** The exhaust plume's isotropic drag. */
    private static final float DRAG_EXHAUST = ParticleStyle.dragFromPerTickFactor(0.86F);

    /** The explosion puff's much heavier horizontal drag, {@code xd *= 0.65}. */
    private static final float DRAG_065 = ParticleStyle.dragFromPerTickFactor(0.65F);

    /**
     * The torpedo wake's drag, and it is the heaviest here by a long way for the obvious reason: water is eight
     * hundred times the density of air, so whatever sideways velocity the screws hand a bubble is gone within a few
     * ticks and the wake stands still in the water exactly where it was laid.
     */
    private static final float DRAG_WAKE_H = ParticleStyle.dragFromPerTickFactor(0.72F);
    private static final float DRAG_WAKE_V = ParticleStyle.dragFromPerTickFactor(0.94F);

    /** The blast cloud's isotropic {@code v *= 0.91}. */
    private static final float DRAG_091 = ParticleStyle.dragFromPerTickFactor(0.91F);

    /** Spray: droplets in air, so light drag, and lighter still on the axis they are falling along. */
    private static final float DRAG_SPRAY_H = ParticleStyle.dragFromPerTickFactor(0.92F);
    private static final float DRAG_SPRAY_V = ParticleStyle.dragFromPerTickFactor(0.985F);

    /** The base surge: foam pushed out across the surface, which runs out within a few blocks. */
    private static final float DRAG_SURGE = ParticleStyle.dragFromPerTickFactor(0.90F);

    /** Ash's horizontal {@code xd *= 0.95} and its much lighter vertical {@code yd *= 0.99}. */
    private static final float DRAG_095 = ParticleStyle.dragFromPerTickFactor(0.95F);
    private static final float DRAG_099 = ParticleStyle.dragFromPerTickFactor(0.99F);

    /** A block chip's {@code friction = 0.98F}, which vanilla applies on all three axes. */
    private static final float DRAG_098 = ParticleStyle.dragFromPerTickFactor(0.98F);

    /** The smoke plume's {@code friction = 0.95F}. */
    private static final float DRAG_PLUME = ParticleStyle.dragFromPerTickFactor(0.95F);

    private static final Map<Integer, Integer> TRAILS = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> MISTS = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> ASHES = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> CHIPS = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> PLUMES = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> SPRAYS = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> SURGES = new ConcurrentHashMap<>();

    private static volatile int wake = -1;
    private static volatile int flame = -1;
    private static volatile int puff = -1;
    private static volatile int debris = -1;
    private static volatile int cloud = -1;
    private static volatile int blockChunk = -1;

    private WFParticleStyles() {
    }

    /**
     * A missile's exhaust plume, drawn by {@code wflib:instance/exhaust.vert} rather than the stock particle
     * shader, which cannot draw one.
     */
    public static int trail(int rgb) {
        return TRAILS.computeIfAbsent(rgb & 0xFFFFFF, tint -> register(ParticleStyle.builder()
                .drag(DRAG_EXHAUST)
                .gravity(ParticleStyle.gravityFromPerTickDelta(0.004F))
                .size(1.6F, 3.5F)
                .tint(tint)
                .alpha(0.42F, 1.0F)
                .cool(0.12F, 0.22F)
                .fadeIn(0.008F)
                .spin(0.3F)
                .light(0.0F, 15.0F / 16.0F)
                .build(), puff()));
    }

    /** A torpedo's wake, drawn by {@code wflib:instance/wake.vert}. */
    public static int wake() {
        if (wake < 0) {
            synchronized (WFParticleStyles.class) {
                if (wake < 0) {
                    wake = register(ParticleStyle.builder()
                            .drag(DRAG_WAKE_H, DRAG_WAKE_V)
                            .gravity(ParticleStyle.gravityFromPerTickDelta(0.03F))
                            .size(0.28F, 0.72F)
                            .tint(0x9FC4DC)
                            .alpha(0.6F, 1.0F)
                            .cool(0.3F, 0.7F)
                            .fadeIn(0.01F)
                            .spin(0.45F)
                            .light(0.0F, 11.0F / 16.0F)
                            .build(), puff());
                }
            }
        }
        return wake;
    }

    /** Lingering fire. */
    public static int flame() {
        if (flame < 0) {
            synchronized (WFParticleStyles.class) {
                if (flame < 0) {
                    flame = ParticleBuffer.getInstance()
                            .registerStyle(ParticleStyle.builder()
                                    .drag(DRAG_09, 0.0F)
                                    .gravity(ParticleStyle.gravityFromPerTickDelta(0.002F))
                                    .size(1.0F, 0.0F)
                                    .alpha(0.85F, 0.5F)
                                    .cool(0.55F, 0.45F)
                                    .build());
                }
            }
        }
        return flame;
    }

    /**
     * Gas and mist.
     *
     * @param blockLight block light 0-15 where the cloud sits
     * @param skyLight sky light 0-15 where the cloud sits
     */
    public static int mist(int rgb, int blockLight, int skyLight) {
        int block = quantiseLight(blockLight);
        int sky = quantiseLight(skyLight);
        int key = (rgb & 0xFFFFFF) | (block << 24) | (sky << 28);

        return MISTS.computeIfAbsent(key, k -> register(ParticleStyle.builder()
                .drag(DRAG_09, 0.0F)
                .size(0.75F, 1.5F)
                .tint(k & 0xFFFFFF)
                .alpha(0.22F, 0.35F)
                .fadeIn(0.2F)
                .spin(0.4F)
                .light(block / 16.0F, sky / 16.0F)
                .build(), puff()));
    }

    /** Chunks of ground thrown by an explosion, and the one style here that touches the world. */
    public static int debris() {
        if (debris < 0) {
            synchronized (WFParticleStyles.class) {
                if (debris < 0) {
                    debris = ParticleBuffer.getInstance()
                            .registerStyle(ParticleStyle.builder()
                                    .gravity(ParticleStyle.gravityFromPerTickDelta(-0.15F))
                                    .size(1.0F, 0.0F)
                                    .spin(3.5F)
                                    .stopsOnContact()
                                    .build());
                }
            }
        }
        return debris;
    }

    /** The fireball an explosion throws up, and the last of the hand-ticked appearances to move across. */
    public static int cloud() {
        if (cloud < 0) {
            synchronized (WFParticleStyles.class) {
                if (cloud < 0) {
                    cloud = register(ParticleStyle.builder()
                            .drag(DRAG_091)
                            .gravity(ParticleStyle.gravityFromPerTickDelta(0.004F))
                            .size(0.5F, 2.2F)
                            .tint(0xFFB20D)
                            .alpha(0.46F, 1.2F)
                            .cool(0.18F, 0.09F)
                            .build(), -1);
                }
            }
        }
        return cloud;
    }

    /**
     * Settling cinders, and the effect with by far the most to gain from not being ticked: a flake lives a full
     * minute, and the old one spent every tick of it calling {@code move}, which is a collision test.
     */
    public static int ash(int blockLight, int skyLight) {
        int block = quantiseLight(blockLight);
        int sky = quantiseLight(skyLight);

        return ASHES.computeIfAbsent(block << 4 | sky, key -> register(ParticleStyle.builder()
                .drag(DRAG_095, DRAG_099)
                .gravity(ParticleStyle.gravityFromPerTickDelta(-0.01F))
                .size(1.0F, 0.0F)
                .alpha(1.0F, ASH_FADE_FRACTION)
                .spin(2.0F)
                .light((key >> 4) / 16.0F, (key & 0xF) / 16.0F)
                .stopsOnContact()
                .build(), cloud()));
    }

    /** Block chips flung off a surface by a small blast. */
    public static int shrapnel(int blockLight, int skyLight) {
        int block = quantiseLight(blockLight);
        int sky = quantiseLight(skyLight);

        return CHIPS.computeIfAbsent(block << 4 | sky, key -> register(ParticleStyle.builder()
                .drag(DRAG_098)
                .gravity(ParticleStyle.gravityFromPerTickDelta(-0.04F))
                .size(1.0F, 0.0F)
                .tint(0xBFBFBF)
                .alpha(1.0F, 0.0F)
                .light((key >> 4) / 16.0F, (key & 0xF) / 16.0F)
                .stopsOnContact()
                .build(), cloud()));
    }

    /** The heavy smoke a mining charge leaves hanging. */
    public static int plume(int blockLight, int skyLight) {
        int block = quantiseLight(blockLight);
        int sky = quantiseLight(skyLight);

        return PLUMES.computeIfAbsent(block << 4 | sky, key -> register(ParticleStyle.builder()
                .drag(DRAG_PLUME)
                .gravity(ParticleStyle.gravityFromPerTickDelta(0.04F * 0.012F))
                .size(1.0F, -0.6F)
                .tint(0x545454)
                .alpha(1.0F, 1.0F)
                .fadeIn(0.05F)
                .spin(0.8F)
                .light((key >> 4) / 16.0F, (key & 0xF) / 16.0F)
                .build(), cloud()));
    }

    /** A flying copy of one block, thrown by a mining charge: {@code BlockDebrisParticle}'s replacement. */
    public static int blockChunk() {
        if (blockChunk < 0) {
            synchronized (WFParticleStyles.class) {
                if (blockChunk < 0) {
                    blockChunk = register(ParticleStyle.builder()
                            .gravity(ParticleStyle.gravityFromPerTickDelta(-0.052F))
                            .drag(ParticleStyle.dragFromPerTickFactor(0.985F))
                            .size(1.0F, 0.0F)
                            .spin(0.7F * 20.0F)
                            .diesOnContact()
                            .build(), -1);
                }
            }
        }
        return blockChunk;
    }

    /** Downward acceleration on a droplet of spray, in blocks per second squared. */
    public static final float SPRAY_GRAVITY = 18.0F;

    /** The spray a blast throws off a water surface: droplets, so they arc and fall back. */
    public static int foamPlume(int blockLight, int skyLight) {
        int block = quantiseLight(blockLight);
        int sky = quantiseLight(skyLight);

        return SPRAYS.computeIfAbsent(block << 4 | sky, key -> register(ParticleStyle.builder()
                .drag(DRAG_SPRAY_H, DRAG_SPRAY_V)
                .gravity(SPRAY_GRAVITY)
                .size(0.9F, 1.3F)
                .tint(0xC8DCE6)
                .alpha(0.80F, 0.6F)
                .cool(8.0F, 1.6F)
                .fadeIn(0.02F)
                .spin(0.6F)
                .light((key >> 4) / 16.0F, (key & 0xF) / 16.0F)
                .build(), cloud()));
    }

    /**
     * The base surge and the raft it leaves: foam pushed out across the surface, which floats where it stops and
     * takes a long time to break up.
     */
    public static int foamSurge(int blockLight, int skyLight) {
        int block = quantiseLight(blockLight);
        int sky = quantiseLight(skyLight);

        return SURGES.computeIfAbsent(block << 4 | sky, key -> register(ParticleStyle.builder()
                .drag(DRAG_SURGE)
                .size(1.0F, 2.6F)
                .tint(0xB4D2E4)
                .alpha(0.62F, 0.85F)
                .cool(1.6F, 3.2F)
                .fadeIn(0.04F)
                .spin(0.25F)
                .light((key >> 4) / 16.0F, (key & 0xF) / 16.0F)
                .build(), cloud()));
    }

    /** What fraction of an ash flake's life is spent fading out: the old particle's last 40 of 1200 ticks. */
    private static final float ASH_FADE_FRACTION = 40.0F / 1200.0F;

    /** Registers a style, or falls back to {@code substitute} when the buffer has no room left. */
    private static int register(ParticleStyle style, int substitute) {
        try {
            return ParticleBuffer.getInstance()
                    .registerStyle(style);
        } catch (IllegalStateException exhausted) {
            if (substitute < 0) {
                throw exhausted;
            }
            return substitute;
        }
    }

    /** Four steps of light, so one gas colour costs at most four of the buffer's style slots. */
    private static int quantiseLight(int level) {
        return Math.min(15, Math.max(0, level) / 4 * 5);
    }

    /** The hot puffs an explosion throws out. */
    public static int puff() {
        if (puff < 0) {
            synchronized (WFParticleStyles.class) {
                if (puff < 0) {
                    puff = ParticleBuffer.getInstance()
                            .registerStyle(ParticleStyle.builder()
                                    .drag(DRAG_065, DRAG_095)
                                    .gravity(ParticleStyle.gravityFromPerTickDelta(0.004F))
                                    .size(1.25F, 2.2F)
                                    .tint(0xFF8A1E)
                                    .alpha(0.46F, 1.15F)
                                    .cool(0.16F, 0.11F)
                                    .spin(0.5F)
                                    .build());
                }
            }
        }
        return puff;
    }
}
