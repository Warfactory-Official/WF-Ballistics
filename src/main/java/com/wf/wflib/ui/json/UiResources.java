package com.wf.wflib.ui.json;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.wf.wflib.WFLib;
import com.wf.wflib.ui.UiLayout;
import com.wf.wflib.ui.UiLayouts;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.slf4j.Logger;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads {@code assets/<ns>/wf_ui/{palettes,components,layouts}/<path>.json} as {@code <ns>:<path>}.
 * Expansion and compilation wait for the first frame after the loading overlay (widget types and
 * signals are registered by then). Per layout id the highest pack whose file expands and compiles wins;
 * none => the previous good version stays (else the Java registration, if any). Errors go to the log,
 * and to chat in a dev run.
 */
@EventBusSubscriber(modid = WFLib.MODID, value = Dist.CLIENT)
public final class UiResources {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final FileToIdConverter PALETTES = FileToIdConverter.json("wf_ui/palettes");
    private static final FileToIdConverter COMPONENTS = FileToIdConverter.json("wf_ui/components");
    private static final FileToIdConverter LAYOUTS = FileToIdConverter.json("wf_ui/layouts");

    /** Pack order, highest first. */
    private record Source(String file, JsonObject json) {
    }

    private record Loaded(Map<ResourceLocation, List<Source>> palettes, Map<ResourceLocation, List<Source>> components,
                          Map<ResourceLocation, List<Source>> layouts) {
    }

    private static Loaded pending;
    private static final List<String> REPORT = new ArrayList<>();

    private UiResources() {
    }

    @SubscribeEvent
    static void onRegisterListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new SimplePreparableReloadListener<Loaded>() {
            @Override
            protected Loaded prepare(ResourceManager manager, ProfilerFiller profiler) {
                return new Loaded(read(manager, PALETTES), read(manager, COMPONENTS), read(manager, LAYOUTS));
            }

            @Override
            protected void apply(Loaded loaded, ResourceManager manager, ProfilerFiller profiler) {
                pending = loaded;
            }
        });
    }

    @SubscribeEvent
    static void onFrame(RenderFrameEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (pending != null && minecraft.getOverlay() == null) {
            Loaded loaded = pending;
            pending = null;
            publish(loaded);
        }
        if (!REPORT.isEmpty() && minecraft.player != null) {
            if (!FMLEnvironment.production) {
                for (String line : REPORT) {
                    minecraft.player.displayClientMessage(Component.literal("[wf_ui] " + line).withStyle(ChatFormatting.RED),
                            false);
                }
            }
            REPORT.clear();
        }
    }

    private static Map<ResourceLocation, List<Source>> read(ResourceManager manager, FileToIdConverter files) {
        Map<ResourceLocation, List<Source>> out = new HashMap<>();
        for (Map.Entry<ResourceLocation, List<Resource>> e : files.listMatchingResourceStacks(manager).entrySet()) {
            ResourceLocation id = files.fileToId(e.getKey());
            List<Source> sources = new ArrayList<>();
            List<Resource> stack = e.getValue();
            for (int i = stack.size() - 1; i >= 0; i--) {
                Resource resource = stack.get(i);
                String file = resource.sourcePackId() + ":" + e.getKey();
                try (Reader reader = resource.openAsReader()) {
                    JsonElement json = JsonParser.parseReader(reader);
                    if (!json.isJsonObject()) {
                        throw new IllegalStateException("not a JSON object");
                    }
                    sources.add(new Source(file, json.getAsJsonObject()));
                } catch (Exception ex) {
                    error(file + ": " + ex.getMessage());
                }
            }
            if (!sources.isEmpty()) {
                out.put(id, sources);
            }
        }
        return out;
    }

    private static void publish(Loaded loaded) {
        Map<ResourceLocation, JsonObject> palettes = new HashMap<>();
        loaded.palettes.forEach((id, sources) -> palettes.put(id, sources.get(0).json));
        Map<ResourceLocation, UiExpander.Component> components = new HashMap<>();
        loaded.components.forEach((id, sources) -> {
            for (Source source : sources) {
                try {
                    components.put(id, UiExpander.component(id, source.file, source.json));
                    return;
                } catch (UiJsonException e) {
                    error(e.getMessage());
                }
            }
        });
        UiExpander expander = new UiExpander(components, palettes);
        Map<ResourceLocation, UiLayout> layouts = new HashMap<>();
        loaded.layouts.forEach((id, sources) -> {
            for (Source source : sources) {
                try {
                    UiLayout layout = expander.layout(id, source.file, source.json);
                    layout.instantiate();
                    layouts.put(id, layout);
                    return;
                } catch (UiJsonException e) {
                    error(e.getMessage());
                } catch (RuntimeException e) {
                    error(source.file + ": " + e.getMessage());
                }
            }
            UiLayout previous = UiLayouts.findResource(id);
            if (previous != null) {
                layouts.put(id, previous);
                error(id + ": keeping the previous version");
            }
        });
        UiLayouts.setResources(layouts);
        LOGGER.info("[wf_ui] {} layouts, {} components, {} palettes", layouts.size(), components.size(), palettes.size());
    }

    private static void error(String message) {
        LOGGER.error("[wf_ui] {}", message);
        REPORT.add(message);
    }
}
