package com.wf.wflib.client.flywheel;

import com.wf.gemrender.particle.ParticleModels;
import com.wf.gemrender.particle.ParticleQuad;
import dev.engine_room.flywheel.api.model.Model;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Sprite-keyed cache, plain map not {@link FlywheelModels}'s {@code RendererReloadCache}: no reload hook => entries leak across resource reloads. */
public final class BlockChipModels {

    private static final Map<TextureAtlasSprite, Model> CHIPS = new ConcurrentHashMap<>();

    private BlockChipModels() {
    }

    public static Model of(BlockState state) {
        TextureAtlasSprite sprite = Minecraft.getInstance()
                .getBlockRenderer()
                .getBlockModelShaper()
                .getParticleIcon(state);

        return CHIPS.computeIfAbsent(sprite, s -> ParticleModels.sprite(
                ParticleQuad.ofUv(s.getU0(), s.getV0(), s.getU1(), s.getV1()),
                InventoryMenu.BLOCK_ATLAS));
    }
}
