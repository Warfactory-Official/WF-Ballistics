package com.wf.wflib.grenade;

import com.wf.wflib.recon.env.Interference;
import com.wf.wflib.recon.Band;
import com.wf.wflib.WFLib;
import com.wf.wflib.aef.ExplosionAEF;
import com.wf.wflib.aef.standard.EntityProcessorCross;
import com.wf.wflib.aef.standard.PlayerProcessorStandard;
import com.wf.wflib.api.PreciseHitbox;
import com.wf.wflib.api.ThermalAttackEvent;
import com.wf.wflib.audio.AudioCategory;
import com.wf.wflib.audio.SoundSpec;
import com.wf.wflib.audio.WfAudio;
import com.wf.wflib.damage.DamageClass;
import com.wf.wflib.damage.WFDamageSources;
import com.wf.wflib.entity.OBBEntity;
import com.wf.wflib.entity.mist.GasCloud;
import com.wf.wflib.fire.FireType;
import com.wf.wflib.fire.FireUtil;
import com.wf.wflib.fire.WFFire;
import com.wf.wflib.fluid.WFFluids;
import com.wf.wflib.fx.ExplosionCreator;
import com.wf.wflib.network.WFNetwork;
import com.wf.wflib.util.OBB;
import com.wf.wflib.warhead.WarheadCarrier;
import com.wf.wflib.warhead.WarheadRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Hand-charge warheads. Smoke and thermite are {@link Reaction}s: the shell keeps working after the fuse. */
public final class GrenadeWarheads {

    public static final ResourceLocation FRAG = WarheadRegistry.rl("frag_grenade");
    public static final ResourceLocation FLASHBANG = WarheadRegistry.rl("flashbang");
    public static final ResourceLocation THERMITE = WarheadRegistry.rl("thermite");
    /** {@code wflib:smoke/<dye>}. */
    public static final String SMOKE_PREFIX = "smoke/";

    //: frag (M67-like) ---------------------------------------------------------------------------
    public static final int FRAG_RAYS = 1500;
    public static final double FRAG_RANGE = 20.0;
    public static final float FRAG_PER_HIT = 7.2f;
    /** Velocity decay length, blocks. */
    public static final double FRAG_DECAY = 8.0;
    public static final float FRAG_BLAST = 2.5f;

    //: flashbang ---------------------------------------------------------------------------------
    /** Distance at which a face-on flash is half strength. */
    static final double FLASH_HALF = 7.0;
    static final double DEAF_HALF = 9.0;
    static final double FLASH_REACH = 48.0;
    /** Share of the flash that reaches an eye looking directly away (scatter off the surroundings). */
    static final float FLASH_AWAY = 0.12f;
    static final double CONCUSSION = 2.0;
    static final float CONCUSSION_PEAK = 4.0f;
    static final int MOB_DAZE = 140;

    //: thermite ----------------------------------------------------------------------------------
    public static final int THERMITE_BURN = 160;
    /** Reach of the burn on bodies (ignite + damage), blocks. */
    static final double THERMITE_CONTACT = 1.2;
    static final float THERMITE_BODY = 3.0f;
    /** Reach of {@link ThermalAttackEvent}, blocks from the target's shape. */
    public static final double THERMITE_REACH = 2.5;
    /** Heat per second at contact. */
    public static final float THERMITE_HEAT = 30.0f;
    /** Recon: thermal contact at the burn (vs motor 2.25, body 0.15); targets within the glare radius x factor. */
    public static final float THERMITE_SIGNATURE = 20.0f;
    public static final double THERMITE_GLARE = 8.0;
    public static final float THERMITE_GLARE_FACTOR = 0.02f;

    //: smoke -------------------------------------------------------------------------------------
    public static final int SMOKE_BURN = 1200;
    /** Cloud stays after the canister is spent. */
    static final int SMOKE_LINGER = 300;
    /** {age, radius}: cloud growth while the canister burns. */
    static final int[][] SMOKE_STAGES = {{0, 3}, {30, 5}, {80, 7}};

    private static final Map<ResourceLocation, Reaction> REACTIONS = new HashMap<>();
    private static final Map<ResourceLocation, Integer> SMOKE_TINT = new HashMap<>();

    /** Continues after detonation; server tick on the {@link ThrownCharge}. */
    public interface Reaction {
        int ticks();

        void tick(ThrownCharge charge, int age);
    }

    private GrenadeWarheads() {
    }

    /** Called from {@link WarheadRegistry}'s static init. */
    public static void bootstrap() {
        WarheadRegistry.register(FRAG, GrenadeWarheads::frag);
        WarheadRegistry.register(FLASHBANG, GrenadeWarheads::flashbang);
        WarheadRegistry.register(THERMITE, GrenadeWarheads::thermite);
        REACTIONS.put(THERMITE, new Reaction() {
            @Override
            public int ticks() {
                return THERMITE_BURN;
            }

            @Override
            public void tick(ThrownCharge charge, int age) {
                thermiteTick(charge, age);
            }
        });
        for (DyeColor dye : DyeColor.values()) {
            ResourceLocation id = smoke(dye);
            int tint = dye.getTextureDiffuseColor() & 0xFFFFFF;
            SMOKE_TINT.put(id, tint);
            WarheadRegistry.register(id, (source, pos) -> smoke(source, pos, id));
            REACTIONS.put(id, new Reaction() {
                @Override
                public int ticks() {
                    return SMOKE_BURN;
                }

                @Override
                public void tick(ThrownCharge charge, int age) {
                    smokeTick(charge, age, tint);
                }
            });
        }
        WarheadRegistry.declarePeakDamage(FRAG, Math.round(FRAG_PER_HIT * 40));
        WarheadRegistry.declarePeakDamage(FLASHBANG, Math.round(CONCUSSION_PEAK));
        WarheadRegistry.declarePeakDamage(THERMITE, Math.round(THERMITE_BODY));
    }

    public static ResourceLocation smoke(DyeColor dye) {
        return WarheadRegistry.rl(SMOKE_PREFIX + dye.getName());
    }

    /** RGB of a smoke warhead; -1 = not smoke. */
    public static int smokeTint(ResourceLocation warhead) {
        return SMOKE_TINT.getOrDefault(warhead, -1);
    }

    @Nullable
    public static Reaction reaction(ResourceLocation warhead) {
        return REACTIONS.get(warhead);
    }

    //: frag --------------------------------------------------------------------------------------

    private static void frag(WarheadCarrier source, Vec3 pos) {
        Level level = source.level();
        if (level.isClientSide) {
            return;
        }
        Entity direct = source instanceof Entity e ? e : null;
        int rays = source.getFragmentCount() > 0 ? source.getFragmentCount() : FRAG_RAYS;
        FragRays.burst(level, pos, rays, FRAG_RANGE, FRAG_PER_HIT, FRAG_DECAY, source.exploder(), direct,
                level.random);
        float size = source.blastSize() > 0 ? source.blastSize() : FRAG_BLAST;
        new ExplosionAEF(level, pos.x, pos.y, pos.z, size, source.exploder())
                .setEntityProcessor(new EntityProcessorCross())
                .setPlayerProcessor(new PlayerProcessorStandard())
                .igniterFaction(source.igniterFactionId())
                .explode();
        ExplosionCreator.composeEffectSmall(level, pos.x, pos.y, pos.z);
    }

    //: flashbang ---------------------------------------------------------------------------------

    private static void flashbang(WarheadCarrier source, Vec3 pos) {
        if (!(source.level() instanceof ServerLevel level)) {
            return;
        }
        for (ServerPlayer p : level.players()) {
            Vec3 eye = p.getEyePosition();
            double d = eye.distanceTo(pos);
            if (d > FLASH_REACH * 2) {
                continue;
            }
            boolean seen = clear(level, pos, eye);
            float flash = seen ? flashFor(d, p.getViewVector(1f).dot(pos.subtract(eye).normalize())) : 0;
            float deaf = deafFor(d, seen);
            WFNetwork.sendToPlayer(p, new FlashPacket(pos.x, pos.y, pos.z, flash, deaf));
        }
        Entity direct = source instanceof Entity e ? e : null;
        for (Entity e : level.getEntities(direct, new AABB(pos, pos).inflate(FLASH_REACH / 2),
                x -> x instanceof LivingEntity && x.isAlive())) {
            double d = e.getEyePosition().distanceTo(pos);
            if (d < CONCUSSION) {
                e.hurt(WFDamageSources.create(level, DamageClass.EXPLOSIVE, source.exploder(), direct),
                        (float) (CONCUSSION_PEAK * (1 - d / CONCUSSION)));
            }
            if (e instanceof Mob mob && clear(level, pos, e.getEyePosition())) {
                float s = flashFor(d, 1);
                if (s > 0.1f) {
                    int t = Math.round(MOB_DAZE * s);
                    mob.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, t));
                    mob.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, t, 2));
                    Dazed.daze(mob, t);
                }
            }
        }
        WfAudio.at(level, pos, AudioCategory.EXPLOSIONS, List.of(
                new SoundSpec(Grenades.FLASHBANG.getId(), 1, 1, 0.05f, 160, 0),
                new SoundSpec(Grenades.FLASHBANG_FAR.getId(), 1, 1, 0.05f, 480, 0, 60)));
    }

    /** @param facing cos of the angle between view and the flash */
    public static float flashFor(double distance, double facing) {
        double near = 1.0 / (1.0 + (distance / FLASH_HALF) * (distance / FLASH_HALF));
        double look = FLASH_AWAY + (1 - FLASH_AWAY) * smoothstep(-0.3, 0.85, facing);
        if (distance < 2) {
            look = Math.max(look, 0.8);
        }
        return (float) Mth.clamp(near * look * 1.15, 0, 1);
    }

    public static float deafFor(double distance, boolean seen) {
        double near = 1.0 / (1.0 + (distance / DEAF_HALF) * (distance / DEAF_HALF));
        return (float) Mth.clamp(near * (seen ? 1.1 : 0.45), 0, 1);
    }

    private static double smoothstep(double a, double b, double x) {
        double t = Mth.clamp((x - a) / (b - a), 0, 1);
        return t * t * (3 - 2 * t);
    }

    private static boolean clear(Level level, Vec3 from, Vec3 to) {
        return level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE,
                CollisionContext.empty())).getType() == HitResult.Type.MISS;
    }

    //: smoke -------------------------------------------------------------------------------------

    private static void smoke(WarheadCarrier source, Vec3 pos, ResourceLocation id) {
        if (source.level().isClientSide || source instanceof ThrownCharge) {
            return;
        }
        ThrownCharge.react((ServerLevel) source.level(), pos, net.minecraft.world.item.ItemStack.EMPTY,
                new com.wf.wflib.api.ChargeSpec(id, 0, 0.2f, false, false, 0, 0, 0), new Vec3(0, 1, 0),
                source.exploder(), null, false);
    }

    private static void smokeTick(ThrownCharge charge, int age, int tint) {
        for (int[] stage : SMOKE_STAGES) {
            if (age == stage[0]) {
                GasCloud.spawn(charge.level(), WFFluids.SMOKE.get(), charge.position().add(0, 0.5, 0), stage[1],
                        GasCloud.DEFAULT_MAX_CELLS, SMOKE_BURN - age + SMOKE_LINGER, tint);
            }
        }
    }

    //: thermite ----------------------------------------------------------------------------------

    private static void thermite(WarheadCarrier source, Vec3 pos) {
        if (source.level().isClientSide || source instanceof ThrownCharge) {
            return;
        }
        ThrownCharge.react((ServerLevel) source.level(), pos, net.minecraft.world.item.ItemStack.EMPTY,
                new com.wf.wflib.api.ChargeSpec(THERMITE, 0, 0.1f, false, false, 0, 0, 0), new Vec3(0, 1, 0),
                source.exploder(), null, false);
    }

    private static void thermiteTick(ThrownCharge charge, int age) {
        ServerLevel level = (ServerLevel) charge.level();
        Vec3 pos = charge.position();
        Entity attacker = charge.exploder();
        if (age == 0) {
            WfAudio.at(level, pos, AudioCategory.EXPLOSIONS, List.of(
                    new SoundSpec(Grenades.THERMITE_IGNITE.getId(), 1, 1, 0.08f, 96, 0)));
            if (!charge.quiet()) {
                FireUtil.spawn(level, pos, 2, 0.6f, THERMITE_BURN + 100, FireType.THERMITE);
            }
        }
        if (charge.quiet()) {
            return;
        }
        if (age % 10 == 0) {
            for (Entity e : level.getEntities(charge, new AABB(pos, pos).inflate(THERMITE_CONTACT),
                    x -> x instanceof LivingEntity && x.isAlive())) {
                double d = e.getBoundingBox().distanceToSqr(pos);
                if (d <= THERMITE_CONTACT * THERMITE_CONTACT || e == charge.host()) {
                    e.hurt(WFDamageSources.create(level, DamageClass.FIRE, attacker, charge), THERMITE_BODY);
                    WFFire.ignite((LivingEntity) e, FireType.THERMITE, FireType.THERMITE.burnTicks);
                }
            }
        }
        if (age % 20 == 0) {
            Interference.put(level, charge.getUUID(), new Interference.Area(Band.THERMAL, Interference.Scope.TARGET,
                    pos, THERMITE_GLARE, THERMITE_GLARE_FACTOR, level.getGameTime() + 21, THERMITE_SIGNATURE));
            Entity host = charge.host();
            for (Entity e : level.getEntities(charge, new AABB(pos, pos).inflate(THERMITE_REACH + 8),
                    x -> x instanceof PreciseHitbox && x.isAlive())) {
                double d = distance(e, pos);
                boolean contact = e == host || d < 0.25;
                if (!contact && d > THERMITE_REACH) {
                    continue;
                }
                float heat = contact ? THERMITE_HEAT : (float) (THERMITE_HEAT * (1 - d / THERMITE_REACH));
                NeoForge.EVENT_BUS.post(new ThermalAttackEvent(e, pos, heat, contact, d, attacker, charge));
            }
        }
    }

    /** Charge to nearest point of the target's shape. */
    static double distance(Entity e, Vec3 pos) {
        if (e instanceof OBBEntity obb && !obb.getOBBs().isEmpty()) {
            double best = Double.MAX_VALUE;
            Vector3f p = pos.toVector3f();
            for (OBB box : obb.getOBBs()) {
                Vector3f q = OBB.getClosestPointOBB(p, box);
                best = Math.min(best, Math.sqrt(q.distanceSquared(p)));
            }
            return best;
        }
        return Math.sqrt(e.getBoundingBox().distanceToSqr(pos));
    }
}
