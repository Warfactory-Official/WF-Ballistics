package com.wf.wflib.round.effect;

import com.wf.wflib.api.Threat;
import com.wf.wflib.damage.WFDamage;
import com.wf.wflib.warhead.WarheadCarrier;
import com.wf.wflib.warhead.WarheadRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * | id | params | triggers |
 * |---|---|---|
 * | {@code ignite} | {@code seconds} (5) | HIT: target burns; others: fire on the open side |
 * | {@code damage} | {@code type}, {@code amount} | HIT (i-frames ignored) |
 * | {@code explode} | {@code warhead}, {@code size} (warhead's), {@code breaksBlocks} (true) | all |
 * | {@code thermal} | {@code pen} mm (0), {@code wear} x (1) | HIT: threat pen +, armour plate/insert wear x |
 * {@code type}, {@code warhead}: existence checked at server start ({@link ImpactEffects#check}).
 */
final class BuiltinEffects {

    private BuiltinEffects() {
    }

    static void register() {
        ImpactEffects.registerFactory(ImpactEffects.IGNITE, (on, p) -> {
            float seconds = (float) p.num("seconds", 5.0, 0.05, 3600.0);
            return ctx -> ignite(ctx, seconds);
        });
        ImpactEffects.registerFactory(ImpactEffects.DAMAGE, (on, p) -> {
            hitOnly(on);
            ResourceKey<DamageType> type = ResourceKey.create(Registries.DAMAGE_TYPE, p.reqRl("type"));
            float amount = (float) p.reqNum("amount", 1.0e-3, 1.0e6);
            return new ImpactEffect() {
                @Override
                public void apply(ImpactContext ctx) {
                    damage(ctx, type, amount);
                }

                @Override
                public void check(RegistryAccess registries) {
                    if (registries.registryOrThrow(Registries.DAMAGE_TYPE).getHolder(type).isEmpty()) {
                        throw new IllegalStateException("damage: type " + type.location() + " not registered");
                    }
                }
            };
        });
        ImpactEffects.registerFactory(ImpactEffects.EXPLODE, (on, p) -> {
            ResourceLocation warhead = p.reqRl("warhead");
            float size = (float) p.num("size", 0.0, 0.0, 1.0e4);
            boolean breaks = p.bool("breaksBlocks", true);
            return new ImpactEffect() {
                @Override
                public void apply(ImpactContext ctx) {
                    explode(ctx, warhead, size, breaks);
                }

                @Override
                public void check(RegistryAccess registries) {
                    if (!WarheadRegistry.exists(warhead)) {
                        throw new IllegalStateException("explode: warhead " + warhead + " not registered");
                    }
                }
            };
        });
        ImpactEffects.registerFactory(ImpactEffects.THERMAL, (on, p) -> {
            hitOnly(on);
            float pen = (float) p.num("pen", 0.0, 0.0, 1.0e5);
            float wear = (float) p.num("wear", 1.0, 1.0, 1.0e3);
            return new ImpactEffect() {
                @Override
                public void apply(ImpactContext ctx) {
                }

                @Override
                public Threat threat(ImpactContext ctx, Threat t) {
                    return new Threat(t.kind(), t.caliberMm(), t.penetrationMm() + pen, t.wearFactor() * wear);
                }
            };
        });
    }

    private static void hitOnly(ImpactTrigger on) {
        if (on != ImpactTrigger.HIT) {
            throw new IllegalArgumentException("needs on: hit, got " + on.key);
        }
    }

    private static void ignite(ImpactContext ctx, float seconds) {
        if (ctx.trigger() == ImpactTrigger.HIT) {
            ctx.entity().igniteForSeconds(seconds);
            return;
        }
        BlockPos pos = ctx.openSide();
        if (BaseFireBlock.canBePlacedAt(ctx.level(), pos, Direction.getNearest(
                ctx.velocity().x, ctx.velocity().y, ctx.velocity().z))) {
            ctx.level().setBlockAndUpdate(pos, BaseFireBlock.getState(ctx.level(), pos));
        }
    }

    private static void damage(ImpactContext ctx, ResourceKey<DamageType> type, float amount) {
        Entity target = ctx.entity();
        DamageSource source = new DamageSource(ctx.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolderOrThrow(type), ctx.projectile(), ctx.shooter());
        if (target instanceof LivingEntity living) {
            WFDamage.hurtIgnoringIFrames(living, source, amount);
        } else {
            target.hurt(source, amount);
        }
    }

    private static void explode(ImpactContext ctx, ResourceLocation warhead, float size, boolean breaks) {
        Vec3 v = ctx.velocity();
        Vec3 angle = v.lengthSqr() < 1.0e-8 ? new Vec3(0.0, -1.0, 0.0) : v.normalize();
        WarheadRegistry.get(warhead).detonate(new Carrier(ctx.level(), angle, ctx.shooter(), ctx.faction(), size,
                breaks, ctx.preset().fragmentCount(), ctx.preset().blastHalfAngleDeg()), ctx.at());
    }

    private record Carrier(Level level, Vec3 angle, @Nullable Entity exploder, @Nullable UUID igniterFactionId,
                           float blastSize, boolean breaksBlocks, int getFragmentCount, float blastHalfAngleDeg)
            implements WarheadCarrier {
    }
}
