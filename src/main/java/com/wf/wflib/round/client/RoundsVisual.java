package com.wf.wflib.round.client;

import com.wf.gemrender.gltf.GemRenderGltfModel;
import com.wf.gemrender.gltf.GltfAnimation;
import com.wf.gemrender.render.GemRenderInstance;
import com.wf.gemrender.render.GemRenderInstanceTypes;
import com.wf.gemrender.render.PoseCache;
import com.wf.wflib.MissileModels;
import com.wf.wflib.ammo.Ammo;
import com.wf.wflib.ammo.ProjectileLook;
import com.wf.wflib.ammo.client.AmmoModels;
import com.wf.wflib.ammo.client.LookLod;
import com.wf.wflib.ammo.client.LookRockets;
import com.wf.wflib.ammo.client.LookStats;
import com.wf.wflib.attitude.MissileAttitudeRegistry;
import com.wf.wflib.client.model.PartRigs;
import com.wf.wflib.round.RocketEntity;
import dev.engine_room.flywheel.api.task.Plan;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visual.EffectVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.task.SimplePlan;
import dev.engine_room.flywheel.lib.visual.AbstractVisual;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * One GemRender instance per modelled round, posed nose-to-velocity every frame: preset glTF (missile attitude), or
 * ammo variant look (nose +Z, rolled at {@code spinRps}, {@link LookLod} model band; rockets included, full-bright
 * flame while burning).
 */
final class RoundsVisual extends AbstractVisual implements EffectVisual<RoundsEffect>, DynamicVisual {

    private static final GltfAnimation[] NO_LAYERS = new GltfAnimation[0];
    private static final float[] NO_TIMES = new float[0];
    /** Rocket keys: disjoint from round keys (>= 0) and from each other (entity ids < 2^32). */
    private static final long ROCKET_KEY = Long.MIN_VALUE;
    private static final long FLAME_KEY = Long.MIN_VALUE + (1L << 32);

    private record Slot(GemRenderInstance instance, GemRenderGltfModel model) {
    }

    private final Long2ObjectMap<Slot> instances = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet seen = new LongOpenHashSet();
    private final Matrix4f pose = new Matrix4f();
    private final Vector3f heading = new Vector3f();
    private final Quaternionf orientation = new Quaternionf();
    private final BlockPos.MutableBlockPos lightPos = new BlockPos.MutableBlockPos();

    RoundsVisual(VisualizationContext ctx, RoundsEffect effect, float partialTick) {
        super(ctx, (Level) effect.level(), partialTick);
    }

    @Override
    public Plan<Context> planFrame() {
        return SimplePlan.of(context -> this.frame(context.partialTick()));
    }

    private void frame(float pt) {
        long t0 = System.nanoTime();
        Vec3i origin = this.renderOrigin();
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        this.seen.clear();
        for (ClientRounds.Round r : ClientRounds.live()) {
            if (RoundRenderers.get(r.preset.id()) != null) {
                continue;
            }
            double x = r.ix(pt), y = r.iy(pt), z = r.iz(pt);
            this.heading.set((float) r.vx, (float) r.vy, (float) r.vz);
            ProjectileLook look = Ammo.lookOrNull(r.preset.id());
            if (look != null) {
                if (look.visible() && this.lookPose(look, r.preset.id(), x, y, z, cam, r.age + pt, origin)) {
                    this.place(r.key, AmmoModels.projectile(r.preset.id()), r.x, r.y, r.z, -1);
                }
                continue;
            }
            if (r.preset.modelId() == null) {
                continue;
            }
            PartRigs.Rig rig = PartRigs.missile(r.preset.modelId());
            if (rig == null) {
                continue;
            }
            if (this.heading.lengthSquared() > 1.0e-8f) {
                this.heading.normalize();
                MissileAttitudeRegistry.get(MissileModels.attitudeId(r.preset.modelId()))
                        .orientation(this.heading, this.orientation);
            }
            this.pose.translation((float) (x - origin.getX()), (float) (y - origin.getY()),
                    (float) (z - origin.getZ())).rotate(this.orientation);
            this.place(r.key, rig.model(), r.x, r.y, r.z, -1);
        }
        for (RocketEntity rocket : LookRockets.live()) {
            ProjectileLook look = Ammo.look(rocket.preset().id());
            double x = Mth.lerp(pt, rocket.xo, rocket.getX()), y = Mth.lerp(pt, rocket.yo, rocket.getY()),
                    z = Mth.lerp(pt, rocket.zo, rocket.getZ());
            Vec3 nose = rocket.getViewVector(pt);
            this.heading.set((float) nose.x, (float) nose.y, (float) nose.z);
            float ticks = rocket.tickCount + pt;
            if (this.lookPose(look, rocket.preset().id(), x, y, z, cam, ticks, origin)) {
                this.place(ROCKET_KEY + rocket.getId(), AmmoModels.projectile(rocket.preset().id()), rocket.getX(),
                        rocket.getY(), rocket.getZ(), -1);
                if (look.flame() && rocket.burning()) {
                    this.pose.scale(1, 1, flicker(ticks, rocket.getId()));
                    this.place(FLAME_KEY + rocket.getId(), AmmoModels.flame(rocket.preset().id()), rocket.getX(),
                            rocket.getY(), rocket.getZ(), LightTexture.FULL_BRIGHT);
                }
            }
        }
        this.instances.long2ObjectEntrySet().removeIf(e -> {
            if (this.seen.contains(e.getLongKey())) {
                return false;
            }
            e.getValue().instance().delete();
            return true;
        });
        LookStats.frames++;
        LookStats.nanos += System.nanoTime() - t0;
        LookStats.placed += this.seen.size();
    }

    /** {@link #pose} for a look; false => outside the model LOD band. {@link #heading} = velocity in. */
    private boolean lookPose(ProjectileLook look, net.minecraft.resources.ResourceLocation id, double x, double y,
                             double z, Vec3 cam, float ticks, Vec3i origin) {
        double d = Math.sqrt(cam.distanceToSqr(x, y, z));
        if (!LookLod.model(look.diameterMm() * 0.001, d)) {
            return false;
        }
        if (this.heading.lengthSquared() > 1.0e-8f) {
            this.heading.normalize();
            this.orientation.rotationTo(0, 0, 1, this.heading.x, this.heading.y, this.heading.z);
        }
        float roll = (float) (look.spinRps() * Mth.TWO_PI * ticks / 20.0 % Mth.TWO_PI);
        this.pose.translation((float) (x - origin.getX()), (float) (y - origin.getY()), (float) (z - origin.getZ()))
                .rotate(this.orientation).rotateZ(roll);
        return true;
    }

    /** Flame length factor 0.75..1.25, two incommensurate sines per rocket. */
    static float flicker(float ticks, int id) {
        return 1 + 0.15f * Mth.sin(ticks * 2.9f + id) + 0.1f * Mth.sin(ticks * 7.3f + id * 1.7f);
    }

    /** Model null (loading) => nothing; model changed => instance rebuilt; {@code light} -1 => world light. */
    private void place(long key, GemRenderGltfModel gltf, double lx, double ly, double lz, int light) {
        if (gltf == null) {
            return;
        }
        Slot slot = this.instances.get(key);
        if (slot != null && slot.model() != gltf) {
            slot.instance().delete();
            slot = null;
        }
        if (slot == null) {
            GemRenderInstance instance = this.instancerProvider().instancer(GemRenderInstanceTypes.SKINNED,
                    gltf.model()).createInstance();
            instance.colorArgb(0xFFFFFFFF);
            slot = new Slot(instance, gltf);
            this.instances.put(key, slot);
        }
        this.seen.add(key);
        PoseCache.Pose posed = PoseCache.getInstance().pose(gltf.layout(), gltf.bounds(), gltf.morphs(),
                NO_LAYERS, NO_TIMES, 0);
        this.lightPos.set(lx, ly, lz);
        GemRenderInstance instance = slot.instance();
        instance.pose.set(this.pose);
        instance.boneBase = posed.boneBase();
        instance.morphBase = posed.morphBase();
        instance.boneSphere.set(posed.sphere());
        instance.light(light >= 0 ? light : LevelRenderer.getLightColor(this.level, this.lightPos));
        instance.setChanged();
    }

    @Override
    protected void _delete() {
        this.instances.values().forEach(s -> s.instance().delete());
        this.instances.clear();
    }
}
