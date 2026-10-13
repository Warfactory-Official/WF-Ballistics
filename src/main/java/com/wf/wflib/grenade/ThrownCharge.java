package com.wf.wflib.grenade;

import com.wf.wflib.api.ChargeSpec;
import com.wf.wflib.entity.OBBEntity;
import com.wf.wflib.util.OBB;
import com.wf.wflib.warhead.WarheadCarrier;
import com.wf.wflib.warhead.WarheadRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;
import java.util.UUID;

/**
 * Hand-thrown charge. Physics both sides (client snaps to each server update). Fuse, contact handling, attachment
 * and reactions server-authoritative. Attached => position = host frame x {@link #LOCAL} every tick, both sides.
 */
public class ThrownCharge extends Projectile implements WarheadCarrier, IEntityWithComplexSpawn {

    /** m/s^2 9.81 => blocks/tick^2. */
    public static final double GRAVITY = 9.81 / 400.0;
    static final double DRAG = 0.995;
    static final double RADIUS = 0.06;
    /** Tangential share kept per contact. */
    static final double CONTACT_FRICTION = 0.75;
    /** Tangential share kept per grounded tick. */
    static final double ROLL_FRICTION = 0.82;
    /** Normal speed below which a floor contact settles instead of bouncing. */
    static final double SETTLE = 0.06;
    /** Owner can be struck after this many ticks. */
    static final int OWNER_GRACE = 8;
    /** Spent shell lingers this long after its reaction. */
    static final int SPENT_LIFE = 600;

    public enum Phase {
        LIVE, REACTING, SPENT;

        static final Phase[] VALUES = values();
    }

    static final EntityDataAccessor<ItemStack> ITEM =
            SynchedEntityData.defineId(ThrownCharge.class, EntityDataSerializers.ITEM_STACK);
    static final EntityDataAccessor<Byte> PHASE =
            SynchedEntityData.defineId(ThrownCharge.class, EntityDataSerializers.BYTE);
    /** Host entity id; -1 free. */
    static final EntityDataAccessor<Integer> HOST =
            SynchedEntityData.defineId(ThrownCharge.class, EntityDataSerializers.INT);
    /** Host OBB index; -1 = host entity frame (yaw). */
    static final EntityDataAccessor<Integer> HOST_PART =
            SynchedEntityData.defineId(ThrownCharge.class, EntityDataSerializers.INT);
    static final EntityDataAccessor<Vector3f> LOCAL =
            SynchedEntityData.defineId(ThrownCharge.class, EntityDataSerializers.VECTOR3);
    /** Unit outward direction of the reaction (surface normal; bore axis for a barrel). */
    static final EntityDataAccessor<Vector3f> VENT =
            SynchedEntityData.defineId(ThrownCharge.class, EntityDataSerializers.VECTOR3);
    static final EntityDataAccessor<Boolean> STUCK =
            SynchedEntityData.defineId(ThrownCharge.class, EntityDataSerializers.BOOLEAN);

    private ChargeSpec spec = new ChargeSpec(WarheadRegistry.rl("inert"), 80, 0.3f, false, false, 0, 0, 0.8f);
    private int fuse;
    private int reactAge;
    /** Reaction is cosmetic: no heat events, no fire spread. */
    private boolean quiet;
    @Nullable
    private BlockPos stuckBlock;
    private boolean grounded;
    /** Client: ticks since the phase last changed. */
    private int phaseAge;
    @Nullable
    private UUID hostUuid;
    /** Client-only debris (a released spoon): ticks to live; 0 = a real charge. */
    private int debrisLife;
    private static int debrisIds = -1_000_000;
    /** Client: drawn orientation, this and last tick. */
    public final Quaternionf spin = new Quaternionf();
    public final Quaternionf spinO = new Quaternionf();

    public ThrownCharge(EntityType<? extends ThrownCharge> type, Level level) {
        super(type, level);
    }

    /** Free charge in flight at {@code at}; not yet added to the level. */
    public static ThrownCharge create(Level level, Vec3 at, Vec3 velocity, ItemStack look, ChargeSpec spec, int fuse,
                                      @Nullable Entity owner) {
        ThrownCharge c = new ThrownCharge(Grenades.CHARGE.get(), level);
        c.setOwner(owner);
        c.init(look, spec, fuse);
        c.setPos(at);
        c.setDeltaMovement(velocity);
        return c;
    }

    /** Thrown from a hand: fuse already burnt down to {@code fuse}. */
    public static ThrownCharge launch(LivingEntity thrower, ItemStack look, ChargeSpec spec, int fuse, Vec3 velocity) {
        ThrownCharge c = new ThrownCharge(Grenades.CHARGE.get(), thrower.level());
        c.setOwner(thrower);
        c.init(look, spec, fuse);
        Vec3 eye = thrower.getEyePosition();
        Vec3 start = eye.add(thrower.getLookAngle().scale(0.3)).add(0, -0.1, 0);
        // Never spawn inside the wall the thrower is hugging.
        BlockHitResult wall = thrower.level().clip(new ClipContext(eye, start, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, thrower));
        if (wall.getType() != HitResult.Type.MISS) {
            start = eye.lerp(wall.getLocation(), 0.5);
        }
        c.setPos(start);
        c.setDeltaMovement(velocity);
        thrower.level().addFreshEntity(c);
        return c;
    }

    /**
     * Client: inert look-only body (a spoon flying off) bounced by the same physics, gone after {@code life} ticks.
     * Caller adds it to the client level; id negative => never collides with a server id.
     */
    public static ThrownCharge debris(Level level, Vec3 at, Vec3 velocity, ItemStack look, int life) {
        ThrownCharge c = create(level, at, velocity, look,
                new ChargeSpec(WarheadRegistry.rl("inert"), Integer.MAX_VALUE, 0.35f, false, false, 0, 0, 0), 0,
                null);
        c.debrisLife = life;
        c.setId(debrisIds--);
        return c;
    }

    /**
     * Motion a released charge inherits from its holder: {@code getKnownMovement} (server player delta movement =
     * knockback only, walking is client-side); y only airborne, as vanilla {@code Projectile.shootFromRotation}.
     */
    public static Vec3 inherited(Entity holder) {
        Vec3 v = holder.getKnownMovement();
        return new Vec3(v.x, holder.onGround() ? 0 : v.y, v.z);
    }

    /** Fuse ran out in the hand: detonates at {@code at} this tick. */
    public static ThrownCharge cookOff(LivingEntity holder, ItemStack look, ChargeSpec spec, Vec3 at) {
        ThrownCharge c = new ThrownCharge(Grenades.CHARGE.get(), holder.level());
        c.setOwner(holder);
        c.init(look, spec, 0);
        c.setPos(at);
        c.setDeltaMovement(inherited(holder));
        holder.level().addFreshEntity(c);
        c.detonate();
        return c;
    }

    /**
     * Starts the warhead's reaction directly (no flight), e.g. a charge dropped into a gun barrel.
     *
     * @param quiet cosmetic only: no heat events, no fire spread
     */
    public static ThrownCharge react(ServerLevel level, Vec3 at, ItemStack look, ChargeSpec spec, Vec3 vent,
                                     @Nullable Entity owner, @Nullable Entity host, boolean quiet) {
        ThrownCharge c = new ThrownCharge(Grenades.CHARGE.get(), level);
        c.setOwner(owner);
        c.init(look, spec, 0);
        c.quiet = quiet;
        c.setPos(at);
        c.entityData.set(VENT, vent.normalize().toVector3f());
        level.addFreshEntity(c);
        if (host != null) {
            c.attach(host, -1, at);
        }
        c.detonate();
        return c;
    }

    private void init(ItemStack look, ChargeSpec spec, int fuse) {
        this.spec = spec;
        this.fuse = fuse;
        entityData.set(ITEM, look.copyWithCount(1));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(ITEM, ItemStack.EMPTY);
        builder.define(PHASE, (byte) 0);
        builder.define(HOST, -1);
        builder.define(HOST_PART, -1);
        builder.define(LOCAL, new Vector3f());
        builder.define(VENT, new Vector3f(0, 1, 0));
        builder.define(STUCK, false);
    }

    public ChargeSpec spec() {
        return spec;
    }

    public ItemStack look() {
        return entityData.get(ITEM);
    }

    public Phase phase() {
        return Phase.VALUES[entityData.get(PHASE)];
    }

    public int fuse() {
        return fuse;
    }

    /** Ticks into the current phase: server = reaction age, client = since the phase arrived. */
    public int phaseAge() {
        return level().isClientSide ? phaseAge : reactAge;
    }

    public Vec3 vent() {
        return new Vec3(entityData.get(VENT));
    }

    public boolean quiet() {
        return quiet;
    }

    /** Stuck to a block or entity, or riding one. */
    public boolean attached() {
        return entityData.get(STUCK) || entityData.get(HOST) >= 0;
    }

    @Nullable
    public Entity host() {
        int id = entityData.get(HOST);
        return id < 0 ? null : level().getEntity(id);
    }

    public boolean grounded() {
        return grounded;
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (PHASE.equals(key)) {
            phaseAge = 0;
        }
    }

    @Override
    public void tick() {
        super.tick();
        phaseAge++;
        if (!level().isClientSide && hostUuid != null && entityData.get(HOST) < 0) {
            Entity found = ((ServerLevel) level()).getEntity(hostUuid);
            if (found != null) {
                entityData.set(HOST, found.getId());
            } else if (tickCount > 40) {
                detach();
            }
        }
        if (entityData.get(HOST) >= 0) {
            followHost();
        } else if (!entityData.get(STUCK)) {
            step();
        }
        if (level().isClientSide) {
            if (debrisLife > 0 && tickCount > debrisLife) {
                discard();
                return;
            }
            com.wf.wflib.grenade.client.ChargeClient.tick(this);
            return;
        }
        if (stuckBlock != null && level().getBlockState(stuckBlock).getCollisionShape(level(), stuckBlock).isEmpty()) {
            stuckBlock = null;
            entityData.set(STUCK, false);
        }
        switch (phase()) {
            case LIVE -> {
                if (--fuse <= 0) {
                    detonate();
                }
            }
            case REACTING -> {
                GrenadeWarheads.Reaction r = GrenadeWarheads.reaction(spec.warhead());
                if (r == null || reactAge >= r.ticks()) {
                    entityData.set(PHASE, (byte) Phase.SPENT.ordinal());
                    reactAge = 0;
                } else {
                    r.tick(this, reactAge);
                    reactAge++;
                }
            }
            case SPENT -> {
                if (++reactAge > SPENT_LIFE) {
                    discard();
                }
            }
        }
    }

    /** Runs the warhead; reacting warheads ({@link GrenadeWarheads.Reaction}) keep the shell alive. */
    public void detonate() {
        if (phase() != Phase.LIVE || level().isClientSide) {
            return;
        }
        Vec3 at = position().add(vent().scale(0.1));
        GrenadeWarheads.Reaction r = GrenadeWarheads.reaction(spec.warhead());
        if (r != null) {
            entityData.set(PHASE, (byte) Phase.REACTING.ordinal());
            reactAge = 0;
        }
        WarheadRegistry.get(spec.warhead()).detonate(this, at);
        if (r == null) {
            discard();
        }
    }

    //: physics -----------------------------------------------------------------------------------

    /** Gravity before the move: a floor contact then cancels it the same tick => resting = exactly still. */
    private void step() {
        Vec3 v = getDeltaMovement().scale(isInWater() ? 0.8 : DRAG).add(0, -GRAVITY, 0);
        Vec3 from = position();
        double left = 1.0;
        boolean floor = false;
        for (int i = 0; i < 4 && left > 1.0E-4 && v.lengthSqr() > 1.0E-10; i++) {
            Vec3 to = from.add(v.scale(left));
            Contact c = contact(from, to);
            if (c == null) {
                from = to;
                break;
            }
            from = c.at.add(c.normal.scale(RADIUS));
            left *= 1.0 - c.fraction;
            double vn = v.dot(c.normal);
            if (!level().isClientSide && onContact(c, v)) {
                return;
            }
            Vec3 normal = c.normal.scale(vn);
            Vec3 tangent = v.subtract(normal);
            if (c.normal.y > 0.6 && -vn < SETTLE) {
                floor = true;
                v = tangent;
            } else {
                v = tangent.scale(CONTACT_FRICTION).subtract(normal.scale(spec.bounce()));
                floor |= c.normal.y > 0.6;
            }
            if (!level().isClientSide && c.entity != null && c.normal.y > 0.6 && -vn < SETTLE * 2) {
                // Settled on something that moves: ride it.
                attach(c.entity, c.part, from);
                setDeltaMovement(Vec3.ZERO);
                return;
            }
        }
        grounded = floor;
        if (grounded) {
            v = v.multiply(ROLL_FRICTION, 1, ROLL_FRICTION);
            if (v.horizontalDistanceSqr() < 1.0E-5) {
                v = Vec3.ZERO;
            }
        }
        setDeltaMovement(v);
        setPos(from);
    }

    /** @return true = consumed (stuck or detonated); stop stepping. */
    private boolean onContact(Contact c, Vec3 v) {
        double speed = v.length();
        if (c.entity == null) {
            BlockState state = level().getBlockState(c.block);
            if (speed > 0.08) {
                level().playSound(null, c.at.x, c.at.y, c.at.z, state.getSoundType(level(), c.block, this)
                                .getStepSound(), SoundSource.NEUTRAL, (float) Math.min(1.0, speed * 1.5),
                        1.4f + random.nextFloat() * 0.3f);
                level().playSound(null, c.at.x, c.at.y, c.at.z, Grenades.BOUNCE.get(), SoundSource.NEUTRAL,
                        (float) Math.min(1.0, speed * 2.0), 0.9f + random.nextFloat() * 0.25f);
            }
        } else if (speed > 0.3 && c.entity instanceof LivingEntity living) {
            living.hurt(damageSources().thrown(this, getOwner()), 1.0f);
        }
        if (phase() != Phase.LIVE) {
            return false;
        }
        entityData.set(VENT, c.normal.toVector3f());
        if (spec.impact() && speed > 0.15) {
            setPos(c.at.add(c.normal.scale(RADIUS)));
            detonate();
            return true;
        }
        if (spec.sticky()) {
            setPos(c.at.add(c.normal.scale(RADIUS)));
            setDeltaMovement(Vec3.ZERO);
            if (c.entity != null) {
                attach(c.entity, c.part, position());
            } else {
                stuckBlock = c.block;
                entityData.set(STUCK, true);
            }
            return true;
        }
        return false;
    }

    private record Contact(Vec3 at, Vec3 normal, double fraction, @Nullable Entity entity, int part,
                           BlockPos block) {
    }

    @Nullable
    private Contact contact(Vec3 from, Vec3 to) {
        Contact best = null;
        BlockHitResult block = level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, this));
        double length = to.subtract(from).length();
        if (block.getType() != HitResult.Type.MISS && length > 0) {
            Direction d = block.getDirection();
            best = new Contact(block.getLocation(), new Vec3(d.getStepX(), d.getStepY(), d.getStepZ()),
                    block.getLocation().subtract(from).length() / length, null, -1, block.getBlockPos());
        }
        AABB sweep = new AABB(from, to).inflate(RADIUS + 0.5);
        List<Entity> nearby = level().getEntities(this, sweep, e -> e.isAlive() && canHitEntity(e)
                && !(e instanceof ThrownCharge));
        for (Entity e : nearby) {
            Contact c = e instanceof OBBEntity obb && !obb.getOBBs().isEmpty()
                    ? obbContact(obb, e, from, to) : boxContact(e, from, to);
            if (c != null && (best == null || c.fraction < best.fraction)) {
                best = c;
            }
        }
        return best;
    }

    @Nullable
    private static Contact obbContact(OBBEntity host, Entity e, Vec3 from, Vec3 to) {
        List<OBB> boxes = host.getOBBs();
        Contact best = null;
        for (int i = 0; i < boxes.size(); i++) {
            OBB box = boxes.get(i);
            if (box.contains(from)) {
                continue;
            }
            Vector3f[] axes = box.getAxes();
            double t = box.clipFraction(from.x, from.y, from.z, to.x, to.y, to.z, axes);
            if (t < 0 || best != null && t >= best.fraction) {
                continue;
            }
            Vec3 at = from.add(to.subtract(from).scale(t));
            Vector3f local = box.worldToLocal(at.x, at.y, at.z, axes, new Vector3f());
            Vector3f ext = box.extents();
            int axis = 0;
            double depth = Double.MAX_VALUE;
            for (int k = 0; k < 3; k++) {
                double gap = ext.get(k) - Math.abs(local.get(k));
                if (gap < depth) {
                    depth = gap;
                    axis = k;
                }
            }
            Vector3f n = new Vector3f(axes[axis]).mul(Math.signum(local.get(axis)) == 0 ? 1 : Math.signum(local.get(axis)));
            best = new Contact(at, new Vec3(n), t, e, i, BlockPos.ZERO);
        }
        return best;
    }

    @Nullable
    private static Contact boxContact(Entity e, Vec3 from, Vec3 to) {
        AABB box = e.getBoundingBox();
        if (box.contains(from)) {
            return null;
        }
        var hit = box.clip(from, to);
        if (hit.isEmpty()) {
            return null;
        }
        Vec3 at = hit.get();
        double length = to.subtract(from).length();
        Vec3 c = box.getCenter();
        double dx = (at.x - c.x) / Math.max(1.0E-3, box.getXsize() / 2);
        double dy = (at.y - c.y) / Math.max(1.0E-3, box.getYsize() / 2);
        double dz = (at.z - c.z) / Math.max(1.0E-3, box.getZsize() / 2);
        Vec3 n = Math.abs(dx) >= Math.abs(dy) && Math.abs(dx) >= Math.abs(dz) ? new Vec3(Math.signum(dx), 0, 0)
                : Math.abs(dy) >= Math.abs(dz) ? new Vec3(0, Math.signum(dy), 0) : new Vec3(0, 0, Math.signum(dz));
        return new Contact(at, n, length > 0 ? at.subtract(from).length() / length : 0, e, -1, BlockPos.ZERO);
    }

    @Override
    protected boolean canHitEntity(Entity target) {
        Entity owner = getOwner();
        if (owner != null && tickCount < OWNER_GRACE && (target == owner || owner.isPassengerOfSameVehicle(target))) {
            return false;
        }
        return !target.isSpectator() && target.isPickable() || target instanceof OBBEntity;
    }

    //: attachment --------------------------------------------------------------------------------

    /** Server: pinned by its owner (e.g. inside a moving gun barrel); no physics, owner repositions per tick. */
    public void holdAt(Vec3 at, Vec3 vent) {
        entityData.set(STUCK, true);
        entityData.set(VENT, vent.normalize().toVector3f());
        setDeltaMovement(at.subtract(position()));
        setPos(at);
    }

    void attach(Entity host, int part, Vec3 at) {
        Vector3f local = toLocal(host, part, at);
        if (local == null) {
            return;
        }
        entityData.set(HOST, host.getId());
        entityData.set(HOST_PART, part);
        entityData.set(LOCAL, local);
        hostUuid = host.getUUID();
        setDeltaMovement(Vec3.ZERO);
    }

    private void detach() {
        entityData.set(HOST, -1);
        entityData.set(HOST_PART, -1);
        entityData.set(STUCK, false);
        hostUuid = null;
        stuckBlock = null;
    }

    private void followHost() {
        Entity host = host();
        if (host == null || !host.isAlive()) {
            if (!level().isClientSide) {
                detach();
            }
            return;
        }
        Vec3 at = toWorld(host, entityData.get(HOST_PART), entityData.get(LOCAL));
        if (at == null) {
            if (!level().isClientSide) {
                detach();
            }
            return;
        }
        setDeltaMovement(at.subtract(position()));
        setPos(at);
    }

    @Nullable
    private static Vector3f toLocal(Entity host, int part, Vec3 at) {
        if (part >= 0 && host instanceof OBBEntity obb) {
            List<OBB> boxes = obb.getOBBs();
            if (part >= boxes.size()) {
                return null;
            }
            OBB box = boxes.get(part);
            return box.worldToLocal(at.x, at.y, at.z, box.getAxes(), new Vector3f());
        }
        Vec3 rel = at.subtract(host.position()).yRot(yawOf(host) * Mth.DEG_TO_RAD);
        return rel.toVector3f();
    }

    @Nullable
    private static Vec3 toWorld(Entity host, int part, Vector3f local) {
        if (part >= 0 && host instanceof OBBEntity obb) {
            List<OBB> boxes = obb.getOBBs();
            if (part >= boxes.size()) {
                return null;
            }
            OBB box = boxes.get(part);
            double[] out = new double[3];
            box.localToWorld(local, box.getAxes(), out);
            return new Vec3(out[0], out[1], out[2]);
        }
        return host.position().add(new Vec3(local).yRot(-yawOf(host) * Mth.DEG_TO_RAD));
    }

    private static float yawOf(Entity host) {
        return host instanceof LivingEntity living ? living.yBodyRot : host.getYRot();
    }

    //: carrier -----------------------------------------------------------------------------------

    @Override
    public int getFragmentCount() {
        return spec.fragments();
    }

    @Override
    public Vec3 angle() {
        return vent();
    }

    @Override
    public float blastSize() {
        return spec.size();
    }

    @Nullable
    @Override
    public Entity exploder() {
        Entity owner = getOwner();
        return owner != null ? owner : this;
    }

    @Override
    public boolean breaksBlocks() {
        return false;
    }

    //: persistence -------------------------------------------------------------------------------

    @Override
    public void writeSpawnData(RegistryFriendlyByteBuf buf) {
        spec.write(buf);
    }

    @Override
    public void readSpawnData(RegistryFriendlyByteBuf buf) {
        spec = ChargeSpec.read(buf);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.put("spec", spec.save());
        if (!look().isEmpty()) {
            tag.put("look", look().save(registryAccess()));
        }
        tag.putInt("fuse", fuse);
        tag.putByte("phase", entityData.get(PHASE));
        tag.putInt("reactAge", reactAge);
        tag.putBoolean("quiet", quiet);
        Vector3f vent = entityData.get(VENT);
        tag.putFloat("ventX", vent.x);
        tag.putFloat("ventY", vent.y);
        tag.putFloat("ventZ", vent.z);
        if (stuckBlock != null) {
            tag.put("stuck", NbtUtils.writeBlockPos(stuckBlock));
        }
        if (hostUuid != null) {
            tag.putUUID("host", hostUuid);
            tag.putInt("hostPart", entityData.get(HOST_PART));
            Vector3f local = entityData.get(LOCAL);
            tag.putFloat("localX", local.x);
            tag.putFloat("localY", local.y);
            tag.putFloat("localZ", local.z);
        }
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        spec = ChargeSpec.load(tag.getCompound("spec"));
        entityData.set(ITEM, ItemStack.parseOptional(registryAccess(), tag.getCompound("look")));
        fuse = tag.getInt("fuse");
        entityData.set(PHASE, (byte) Mth.clamp(tag.getByte("phase"), 0, Phase.VALUES.length - 1));
        reactAge = tag.getInt("reactAge");
        quiet = tag.getBoolean("quiet");
        entityData.set(VENT, new Vector3f(tag.getFloat("ventX"), tag.getFloat("ventY"), tag.getFloat("ventZ")));
        NbtUtils.readBlockPos(tag, "stuck").ifPresent(p -> {
            stuckBlock = p;
            entityData.set(STUCK, true);
        });
        if (tag.hasUUID("host")) {
            hostUuid = tag.getUUID("host");
            entityData.set(HOST_PART, tag.getInt("hostPart"));
            entityData.set(LOCAL, new Vector3f(tag.getFloat("localX"), tag.getFloat("localY"), tag.getFloat("localZ")));
        }
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 128 * 128;
    }

    @Override
    public boolean isPickable() {
        return false;
    }
}
