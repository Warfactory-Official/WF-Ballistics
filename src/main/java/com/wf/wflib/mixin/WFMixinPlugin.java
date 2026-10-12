package com.wf.wflib.mixin;

import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Gates mixins incompatible with third-party chunk renderers, and those targeting optional mods. */
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
        if (mixinClassName.endsWith("MixinLevelRendererFeed") || mixinClassName.endsWith("MixinViewArea")) {
            return !isSodiumFamilyLoaded();
        }
        if (mixinClassName.contains(".gt.")) {
            return isModLoaded("gtceu");
        }
        if (mixinClassName.contains(".xaero.worldmap.")) {
            return isModLoaded("xaeroworldmap");
        }
        if (mixinClassName.contains(".xaero.minimap.")) {
            return isModLoaded("xaerominimap");
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
