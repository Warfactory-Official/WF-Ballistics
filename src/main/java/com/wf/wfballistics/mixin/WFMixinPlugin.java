package com.wf.wfballistics.mixin;

import net.minecraftforge.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Gates the mod's mixins that are incompatible with third-party chunk renderers.
 *
 * <p>{@link MixinLevelRenderer} shift-injects into {@code LevelRenderer#renderChunkLayer}, which the
 * Sodium family (Embeddium / Rubidium) fully rewrites. Injecting a shifted {@code @At} into their merged
 * method both fails to apply (a hard crash at mixin-apply time) and would be a no-op anyway, since those
 * renderers bypass vanilla's chunk-layer path. When one of them is installed we simply skip that mixin.
 */
public class WFMixinPlugin implements IMixinConfigPlugin {

    private static boolean isSodiumFamilyLoaded() {
        return isModLoaded("embeddium") || isModLoaded("rubidium") || isModLoaded("sodium");
    }

    private static boolean isModLoaded(String modId) {
        LoadingModList list = LoadingModList.get();
        return list != null && list.getModFileById(modId) != null;
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith("MixinLevelRenderer")) {
            return !isSodiumFamilyLoaded();
        }
        if (mixinClassName.contains(".gt.")) {
            return isModLoaded("gtceu");
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
