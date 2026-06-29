package com.wf.wfballistics.aef.standard;

import com.wf.wfballistics.aef.ExplosionAEF;
import com.wf.wfballistics.damage.DamageClass;
import com.wf.wfballistics.damage.EntityDamageUtil;
import com.wf.wfballistics.damage.WFDamageSources;
import com.wf.wfballistics.fire.AshHandler;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * {@link EntityProcessorCross} variant for weapons rather than terrain-cratering blasts. Two differences:
 *
 * <ul>
 *   <li><b>Flat falloff.</b> Damage is {@code fixedDamage * (1 - distanceScaled)} — a predictable linear
 *       drop from full damage at the centre to zero at the edge, instead of the vanilla quadratic curve.</li>
 *   <li><b>Real damage routing.</b> Living targets are hit through {@link EntityDamageUtil}, so the blast's
 *       {@link DamageClass} and armour-piercing ({@link #setupPiercing}) go through the DT/DR model. Kills
 *       on burning targets are sent to {@link AshHandler} so they crumble to ash.</li>
 * </ul>
 *
 * <p>Self-damage is enabled by default (a weapon's own user is in range of its own shell).
 */
public class EntityProcessorCrossSmooth extends EntityProcessorCross {

    protected final float fixedDamage;
    protected float pierceDT = 0;
    protected float pierceDR = 0;
    protected DamageClass clazz = DamageClass.EXPLOSIVE;

    public EntityProcessorCrossSmooth(double nodeDist, float fixedDamage) {
        super(nodeDist);
        this.fixedDamage = fixedDamage;
        this.setAllowSelfDamage();
    }

    /**
     * @param pierceDT flat reduction of the target's damage threshold
     * @param pierceDR fraction of the target's damage resistance ignored
     */
    public EntityProcessorCrossSmooth setupPiercing(float pierceDT, float pierceDR) {
        this.pierceDT = pierceDT;
        this.pierceDR = pierceDR;
        return this;
    }

    public EntityProcessorCrossSmooth setDamageClass(DamageClass clazz) {
        this.clazz = clazz;
        return this;
    }

    @Override
    public void attackEntity(Entity entity, ExplosionAEF explosion, float amount) {
        if (!entity.isAlive()) return;
        if (explosion.exploder == entity) amount *= 0.5F; // half damage to the shooter

        DamageSource dmg = WFDamageSources.create(explosion.level, clazz, explosion.exploder);

        if (!(entity instanceof LivingEntity living)) {
            entity.hurt(dmg, amount);
        } else {
            EntityDamageUtil.dealDamage(living, dmg, amount, true, pierceDT, pierceDR);
            if (!entity.isAlive()) {
                AshHandler.decideGore(living, dmg);
            }
        }
    }

    @Override
    public float calculateDamage(double distanceScaled, double density, double knockback, float size) {
        return (float) (fixedDamage * (1 - distanceScaled));
    }
}
