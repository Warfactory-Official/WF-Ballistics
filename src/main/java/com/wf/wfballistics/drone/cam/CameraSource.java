package com.wf.wfballistics.drone.cam;

import com.wf.wfballistics.block.SecurityCameraBlock;
import com.wf.wfballistics.drone.DroneEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Whatever a feed is coming out of. */
public interface CameraSource {

    /** Where the lens is. The feed is rendered from exactly here. */
    Vec3 eye();

    CameraSpec spec();

    /**
     * The heading the mount points in, which is the centre of the arc a limited gimbal can reach and the angle a
     * fresh feed starts at.
     */
    float restYaw();

    /** The angle a fresh feed starts at, in the same convention as {@link CameraSpec#pitchMin()}. */
    float restPitch();

    /** 0..1, drawn on the OSD. A mains-fed camera has nothing to report and says so by reporting full. */
    float battery();

    /** Blocks per second, for the OSD. Zero for anything bolted down. */
    float speed();

    /** Charge this feed's running cost to whatever is paying for it. */
    void bill(int fe, int ticks);

    /** @return the source behind this feed id, or null if there is no longer one. */
    @Nullable
    static CameraSource resolve(ServerLevel level, int feedId) {
        if (feedId == 0) {
            return null;
        }
        return feedId > 0 ? airborne(level, feedId) : fixed(level, feedId);
    }

    @Nullable
    private static CameraSource airborne(ServerLevel level, int feedId) {
        Entity entity = level.getEntity(feedId);
        if (!(entity instanceof DroneEntity drone) || !drone.isAlive()) {
            return null;
        }
        CameraSpec spec = drone.cameraSpec();
        return spec == null ? null : new Airborne(drone, spec);
    }

    @Nullable
    private static CameraSource fixed(ServerLevel level, int feedId) {
        GlobalPos at = StaticCameraFeeds.posFor(feedId);
        if (at == null || !at.dimension().equals(level.dimension())) {
            return null;
        }
        return fixedAt(level, at.pos());
    }

    /**
     * @return the camera at this position, or null if there is not one there right now.
     */
    @Nullable
    static CameraSource fixedAt(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) {
            return null;
        }
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof SecurityCameraBlock)) {
            return null;
        }
        return new Fixed(pos, state.getValue(SecurityCameraBlock.FACING));
    }

    /** A drone's camera. Reads the fields {@code publish} used to read inline. */
    record Airborne(DroneEntity drone, CameraSpec spec) implements CameraSource {

        @Override
        public Vec3 eye() {
            return this.drone.position().add(0.0, this.drone.getBbHeight() * 0.5, 0.0);
        }

        @Override
        public float restYaw() {
            return this.drone.getYRot();
        }

        @Override
        public float restPitch() {
            return 0.0f;
        }

        @Override
        public float battery() {
            return this.drone.battery().percent() / 100.0f;
        }

        @Override
        public float speed() {
            return (float) this.drone.getDeltaMovement().length() * 20.0f;
        }

        @Override
        public void bill(int fe, int ticks) {
            this.drone.battery().drain(fe * ticks / 20.0);
        }
    }

    /** A camera on a wall. */
    record Fixed(BlockPos pos, Direction facing) implements CameraSource {

        /** How far the lens sits in front of the block's centre. */
        private static final double LENS = 0.55;

        @Override
        public Vec3 eye() {
            return Vec3.atCenterOf(this.pos)
                    .add(this.facing.getStepX() * LENS, 0.1, this.facing.getStepZ() * LENS);
        }

        @Override
        public CameraSpec spec() {
            return CameraSpec.SECURITY;
        }

        @Override
        public float restYaw() {
            return this.facing.toYRot();
        }

        @Override
        public float restPitch() {
            // Angled a little down, the way one is actually installed. Positive is down.
            return 10.0f;
        }

        @Override
        public float battery() {
            return 1.0f;
        }

        @Override
        public float speed() {
            return 0.0f;
        }

        @Override
        public void bill(int fe, int ticks) {
        }
    }
}
