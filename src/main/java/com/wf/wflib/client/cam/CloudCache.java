package com.wf.wflib.client.cam;

import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.CloudStatus;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code LevelRenderer}'s cloud mesh + its key. Keyed by camera cloud cell (12 blocks) => two cameras in different
 * cells rebuild and re-upload the mesh twice a frame unless each view keeps its own.
 */
public final class CloudCache implements AutoCloseable {

    /** Unclosed caches; render thread. */
    private static final List<CloudCache> LIVE = new ArrayList<>(4);

    public int x = Integer.MIN_VALUE;
    public int y = Integer.MIN_VALUE;
    public int z = Integer.MIN_VALUE;
    public Vec3 color = Vec3.ZERO;
    @Nullable
    public CloudStatus type;
    public boolean generate = true;
    @Nullable
    public VertexBuffer buffer;

    public CloudCache() {
        LIVE.add(this);
    }

    /** Vanilla's {@code generateClouds = true} reaches only the installed mesh. */
    public static void regenerateAll() {
        for (int i = 0; i < LIVE.size(); i++) {
            LIVE.get(i).generate = true;
        }
    }

    @Override
    public void close() {
        LIVE.remove(this);
        if (this.buffer != null) {
            this.buffer.close();
            this.buffer = null;
        }
    }
}
