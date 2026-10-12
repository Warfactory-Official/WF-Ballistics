package com.wf.wflib.build;

import com.wf.wflib.WFLib;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** {@link BlueprintFormat} registry; same shape as {@code Formations}/{@code CoordinationModels}. */
public final class BlueprintFormats {

    private static final Map<ResourceLocation, BlueprintFormat> BY_ID = new LinkedHashMap<>();
    private static final Map<String, BlueprintFormat> BY_EXTENSION = new LinkedHashMap<>();

    static {
        register(LitematicaFormat.INSTANCE);
        register(StructureNbtFormat.INSTANCE);
    }

    private BlueprintFormats() {
    }

    public static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath(WFLib.MODID, path);
    }

    public static void register(BlueprintFormat format) {
        BY_ID.put(rl(format.id()), format);
        BY_EXTENSION.put(format.extension().toLowerCase(Locale.ROOT), format);
    }

    @Nullable
    public static BlueprintFormat get(ResourceLocation id) {
        return id == null ? null : BY_ID.get(id);
    }

    @Nullable
    public static BlueprintFormat forFile(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? null : BY_EXTENSION.get(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    public static Set<ResourceLocation> ids() {
        return Collections.unmodifiableSet(BY_ID.keySet());
    }

    public static Set<String> extensions() {
        return Collections.unmodifiableSet(BY_EXTENSION.keySet());
    }
}
