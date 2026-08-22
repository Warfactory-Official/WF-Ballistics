package com.wf.wfballistics.entity.glyphid.caste;

import com.wf.wfballistics.ModEntities;
import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import com.wf.wfballistics.entity.glyphid.GlyphidStats;
import com.wf.wfballistics.entity.glyphid.GlyphidTasks;
import com.wf.wfballistics.entity.glyphid.GlyphidWaypoint;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The brood mother. Killing her is not the end of the fight, it is the start of the next one.
 *
 * <p>Upstream's death throe is a cloud of pheromone, which in a mod with a pheromone system means "every
 * glyphid in earshot comes here". There is no such system here and there does not need to be one: this
 * colony already has orders, waypoints and a {@link EntityGlyphid#communicate} channel, so the pheromone is
 * expressed as what it actually did — a rally on the corpse — and the brood is spawned outright.
 *
 * <p>Not high priority, deliberately. A rally that cancelled every target would pull the whole swarm off
 * whatever it was biting at the exact moment the defender is winning.
 */
public class EntityGlyphidBrenda extends EntityGlyphid {

    /**
     * Grunts that boil out of her when she dies.
     */
    private static final int BROOD = 12;
    private static final int RALLY_RADIUS = 14;
    private static final int RALLY_MAX_AGE = 600;

    public EntityGlyphidBrenda(EntityType<? extends EntityGlyphidBrenda> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        GlyphidStats.StatBundle stats = GlyphidStats.getStats().getBrenda();
        return EntityGlyphid.createAttributes()
                .add(Attributes.MAX_HEALTH, stats.health())
                .add(Attributes.MOVEMENT_SPEED, stats.movementSpeed())
                .add(Attributes.ATTACK_DAMAGE, stats.damage());
    }

    @Override
    public GlyphidStats.StatBundle getStats() {
        return GlyphidStats.getStats().getBrenda();
    }

    @Override
    public double getGlyphidScale() {
        return 2.0D;
    }

    @Override
    public boolean fireImmune() {
        return true;
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);

        if (level().isClientSide || !(level() instanceof ServerLevel server)) {
            return;
        }
        rally(server);
        hatch(server);
    }

    /**
     * Drop a marker on the corpse and tell the neighbours about it, so the swarm converges on where she fell
     * instead of carrying on past it.
     */
    private void rally(ServerLevel server) {
        GlyphidWaypoint marker = new GlyphidWaypoint(ModEntities.GLYPHID_WAYPOINT.get(), server);
        marker.setWaypointType(GlyphidTasks.TASK_FOLLOW);
        marker.radius = RALLY_RADIUS;
        marker.maxAge = RALLY_MAX_AGE;
        marker.moveTo(getX(), getY(), getZ(), 0.0F, 0.0F);
        server.addFreshEntity(marker);

        communicate(GlyphidTasks.TASK_FOLLOW, marker);
    }

    private void hatch(ServerLevel server) {
        for (int i = 0; i < BROOD; i++) {
            EntityGlyphid grunt = ModEntities.GLYPHID.get().create(server);
            if (grunt == null) {
                return;
            }
            grunt.moveTo(getX(), getY() + 0.5D, getZ(), random.nextFloat() * 360.0F, 0.0F);
            grunt.setSubtype(subtype());
            server.addFreshEntity(grunt);
            // Scattered on spawn, because twelve bugs materialising in one block is a stack that has to shove
            // itself apart -- and glyphids do not shove each other.
            grunt.move(MoverType.SELF, new Vec3(random.nextGaussian(), 0.0, random.nextGaussian()));
        }
    }

    @Override
    public boolean isArmorBroken(float amount) {
        return random.nextInt(100) <= Math.min(Math.pow(amount * 0.12, 2), 100);
    }
}
