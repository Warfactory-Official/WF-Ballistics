package com.wf.wflib.client.cam;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;

/** Presents a framebuffer's colour attachment as a named texture. */
public final class FeedTexture extends AbstractTexture {

    /** Take over a framebuffer's colour attachment. */
    public void adopt(int glId) {
        this.id = glId;
    }

    @Override
    public void load(ResourceManager manager) {
    }

    @Override
    public void close() {
    }

    @Override
    public void releaseId() {
        this.id = -1;
    }
}
