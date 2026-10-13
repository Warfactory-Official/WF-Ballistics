package com.wf.wflib.nvg.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.logging.LogUtils;
import com.wf.gemrender.light.IrTarget;
import com.wf.gemrender.render.GlPrograms;
import com.wf.gemrender.water.PassState;
import com.wf.wflib.WFLib;
import com.wf.wflib.nvg.NvgState;
import com.wf.wflib.nvg.NvgWorn;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Image intensifier over the main view, after the hand (gun seen through the tubes), before the GUI:
 * scene + {@link IrTarget} -> flux (tube resolution, mips) -> ABC adapt (1x1) -> tube image (noise, halo,
 * MCP, blemishes, persistence; history ping-pong) -> composite over the naked-eye view.
 */
@EventBusSubscriber(modid = WFLib.MODID, value = Dist.CLIENT)
public final class NvgView {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String VERSION = "#version 330 core\n";
    static final float HALO_LOD = 3.5f;

    private static final PassState STATE = new PassState();
    private static final int[] VIEWPORT = new int[4];
    private static final float[] TUBES = new float[NvgOptics.MAX_TUBES * 4];

    private static boolean created;
    private static boolean failed;
    private static Program flux;
    private static Program adapt;
    private static Program tube;
    private static Program composite;
    private static int fbo;
    private static int vao;
    private static int fluxTex;
    private static final int[] ADAPT = new int[2];
    private static final int[] HISTORY = new int[2];
    private static int black;
    private static int fluxW;
    private static int fluxH;
    private static int screenW;
    private static int screenH;
    private static int ping;
    private static int frame;
    private static long lastNanos;

    private NvgView() {
    }

    private record Program(int id, Map<String, Integer> locations) {
        int loc(String name) {
            return locations.computeIfAbsent(name, n -> glGetUniformLocation(id, n));
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onRenderGui(RenderGuiEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (!NvgClient.viewing()) {
            return;
        }
        ItemStack worn = NvgClient.worn();
        NvgOptics optics = NvgOptics.of(worn.getItem());
        if (optics == null || !ensureCreated()) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        NvgState state = NvgWorn.state(worn);
        float warm = NvgClient.warm(partial);
        RenderTarget main = mc.getMainRenderTarget();
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0.0f : Math.min((now - lastNanos) * 1.0e-9f, 0.25f);
        lastNanos = now;

        STATE.saveDeep();
        glGetIntegerv(GL_VIEWPORT, VIEWPORT);
        try {
            size(main.width, main.height, optics.resolution());
            GlStateManager._disableDepthTest();
            GlStateManager._depthMask(false);
            GlStateManager._disableBlend();
            GlStateManager._disableCull();
            GlStateManager._glBindVertexArray(vao);
            GlStateManager._glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            float gain = optics.gainMax() * state.gain() * state.gain() * warm;

            // 1. flux
            target(fluxTex, fluxW, fluxH);
            use(flux);
            bind(0, main.getColorTextureId());
            bind(1, IrTarget.filled() ? IrTarget.texture() : black);
            List<Float> s = optics.spectral();
            glUniform4f(flux.loc("_nv_spectral"), s.get(0), s.get(1), s.get(2), s.get(3));
            glUniform1f(flux.loc("_nv_irOn"), IrTarget.filled() ? 1.0f : 0.0f);
            glUniform2f(flux.loc("_nv_size"), fluxW, fluxH);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            GlStateManager._bindTexture(fluxTex);
            glGenerateMipmap(GL_TEXTURE_2D);

            // 2. ABC
            int prevAdapt = ADAPT[ping];
            int nextAdapt = ADAPT[1 - ping];
            target(nextAdapt, 1, 1);
            use(adapt);
            bind(0, fluxTex);
            bind(1, prevAdapt);
            glUniform1f(adapt.loc("_nv_lod"), (float) Math.floor(Math.log(Math.max(fluxW, fluxH)) / Math.log(2.0)));
            glUniform1f(adapt.loc("_nv_gain"), gain);
            glUniform1f(adapt.loc("_nv_ease"), 1.0f - (float) Math.exp(-optics.abcRate() * dt));
            glDrawArrays(GL_TRIANGLES, 0, 3);

            // 3. tube image
            int prevHistory = HISTORY[ping];
            int nextHistory = HISTORY[1 - ping];
            target(nextHistory, screenW, screenH);
            use(tube);
            bind(0, fluxTex);
            bind(1, nextAdapt);
            bind(2, prevHistory);
            tubes(tube, optics);
            glUniform1f(tube.loc("_nv_gain"), gain);
            glUniform1f(tube.loc("_nv_gainFrac"), state.gain());
            glUniform1f(tube.loc("_nv_snr"), optics.snr());
            glUniform1f(tube.loc("_nv_ebi"), optics.ebi());
            glUniform1f(tube.loc("_nv_halo"), optics.halo());
            glUniform1f(tube.loc("_nv_haloLod"), HALO_LOD);
            glUniform1f(tube.loc("_nv_autogate"), optics.autogate() ? 1.0f : 0.0f);
            glUniform1f(tube.loc("_nv_distortion"), optics.distortion());
            glUniform1f(tube.loc("_nv_vignette"), optics.vignette());
            glUniform1f(tube.loc("_nv_honeycomb"), optics.honeycomb());
            glUniform1i(tube.loc("_nv_blemishes"), optics.blemishes());
            glUniform1f(tube.loc("_nv_persistence"), optics.persistence());
            glUniform1ui(tube.loc("_nv_frame"), frame++);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            ping = 1 - ping;

            // 4. composite
            main.bindWrite(false);
            GlStateManager._viewport(0, 0, main.width, main.height);
            GlStateManager._enableBlend();
            GlStateManager._blendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);
            use(composite);
            bind(0, nextHistory);
            tubes(composite, optics);
            List<Float> p = optics.phosphor();
            glUniform3f(composite.loc("_nv_phosphor"), p.get(0), p.get(1), p.get(2));
            glUniform1f(composite.loc("_nv_fade"), warm);
            glDrawArrays(GL_TRIANGLES, 0, 3);
        } finally {
            STATE.restore();
            main.bindWrite(false);
            GlStateManager._viewport(VIEWPORT[0], VIEWPORT[1], VIEWPORT[2], VIEWPORT[3]);
        }
    }

    private static void tubes(Program program, NvgOptics optics) {
        List<NvgOptics.Tube> list = optics.tubes();
        for (int i = 0; i < list.size(); i++) {
            NvgOptics.Tube t = list.get(i);
            TUBES[i * 4] = t.x();
            TUBES[i * 4 + 1] = t.y();
            TUBES[i * 4 + 2] = t.r();
        }
        glUniform4fv(program.loc("_nv_tubes"), TUBES);
        glUniform1i(program.loc("_nv_tubeCount"), list.size());
        glUniform2f(program.loc("_nv_screen"), screenW, screenH);
    }

    private static void use(Program program) {
        GlStateManager._glUseProgram(program.id());
    }

    private static void bind(int unit, int texture) {
        GlStateManager._activeTexture(GL_TEXTURE0 + unit);
        GlStateManager._bindTexture(texture);
    }

    private static void target(int texture, int w, int h) {
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
        GlStateManager._viewport(0, 0, w, h);
    }

    /** (Re)allocate on screen or optics resolution change; history and ABC reset. */
    private static void size(int w, int h, float resolution) {
        int fw = Math.max(16, Math.round(w * resolution));
        int fh = Math.max(16, Math.round(h * resolution));
        if (w == screenW && h == screenH && fw == fluxW && fh == fluxH) {
            return;
        }
        screenW = w;
        screenH = h;
        fluxW = fw;
        fluxH = fh;
        allocate(fluxTex, fw, fh, GL_LINEAR_MIPMAP_LINEAR);
        for (int i = 0; i < 2; i++) {
            allocate(HISTORY[i], w, h, GL_NEAREST);
            allocate(ADAPT[i], 1, 1, GL_NEAREST);
        }
        GlStateManager._glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        for (int texture : new int[] {HISTORY[0], HISTORY[1], ADAPT[0], ADAPT[1]}) {
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
            glClearBufferfv(GL_COLOR, 0, new float[] {-1.0f, 0.0f, 0.0f, 0.0f});
        }
    }

    private static void allocate(int texture, int w, int h, int minFilter) {
        GlStateManager._bindTexture(texture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R16F, w, h, 0, GL_RED, GL_HALF_FLOAT, 0L);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, minFilter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, minFilter == GL_NEAREST ? GL_NEAREST : GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }

    private static boolean ensureCreated() {
        if (created || failed) {
            return created;
        }
        try {
            String vert = VERSION + GlPrograms.resource(WFLib.MODID, "shaders/nvg/fullscreen.vert");
            flux = program("nvg flux", vert, "flux", "_nv_scene", "_nv_ir");
            adapt = program("nvg adapt", vert, "adapt", "_nv_flux", "_nv_prev");
            tube = program("nvg tube", vert, "tube", "_nv_flux", "_nv_adapt", "_nv_history");
            composite = program("nvg composite", vert, "composite", "_nv_tube");
            fbo = glGenFramebuffers();
            vao = glGenVertexArrays();
            fluxTex = glGenTextures();
            for (int i = 0; i < 2; i++) {
                ADAPT[i] = glGenTextures();
                HISTORY[i] = glGenTextures();
            }
            black = glGenTextures();
            GlStateManager._bindTexture(black);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, new int[] {0});
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            created = true;
        } catch (Exception e) {
            failed = true;
            LOGGER.error("night vision: shaders unusable, view off", e);
        }
        return created;
    }

    private static Program program(String name, String vert, String frag, String... samplers) throws java.io.IOException {
        int id = GlPrograms.link(name, vert, VERSION + GlPrograms.resource(WFLib.MODID, "shaders/nvg/" + frag + ".frag"));
        GlStateManager._glUseProgram(id);
        for (int unit = 0; unit < samplers.length; unit++) {
            glUniform1i(glGetUniformLocation(id, samplers[unit]), unit);
        }
        return new Program(id, new HashMap<>());
    }
}
