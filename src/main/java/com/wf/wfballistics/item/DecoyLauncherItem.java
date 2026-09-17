package com.wf.wfballistics.item;

import com.wf.wfballistics.recon.example.decoy.DecoyRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Example content: scatters a cloud of radar decoys. */
public class DecoyLauncherItem extends Item {

    /** Decoys per pull. Enough to merge at range and resolve into several contacts up close. */
    private static final int COUNT = 12;
    /** How far ahead of the player the cloud is released. */
    private static final double THROW = 8.0;
    /** Blocks of scatter across the cloud. Comfortably inside one resolution cell at a few hundred blocks. */
    private static final double SPREAD = 4.0;
    /** Blocks per tick of drift. Well clear of the doppler notch, so they are not lost as clutter. */
    private static final double DRIFT = 0.08;
    /** Ticks each decoy lasts: long enough for a 20-tick sweep to see it several times. */
    private static final int LIFETIME = 400;
    /** Presented cross-section before the reflector each one carries. */
    private static final float RCS = 0.05f;

    public DecoyLauncherItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (!(level instanceof ServerLevel sl)) {
            return InteractionResultHolder.sidedSuccess(held, level.isClientSide);
        }
        RandomSource random = sl.random;
        Vec3 origin = player.getEyePosition().add(player.getLookAngle().scale(THROW));
        long gameTime = sl.getGameTime();
        DecoyRegistry registry = DecoyRegistry.get(sl);
        for (int i = 0; i < COUNT; i++) {
            double x = origin.x + (random.nextDouble() - 0.5) * SPREAD;
            double y = origin.y + (random.nextDouble() - 0.5) * SPREAD;
            double z = origin.z + (random.nextDouble() - 0.5) * SPREAD;
            registry.release(x, y, z,
                    (random.nextDouble() - 0.5) * 2.0 * DRIFT,
                    (random.nextDouble() - 0.5) * DRIFT,
                    (random.nextDouble() - 0.5) * 2.0 * DRIFT,
                    RCS, gameTime, LIFETIME);
            sl.sendParticles(ParticleTypes.FIREWORK, x, y, z, 3, 0.2, 0.2, 0.2, 0.01);
        }
        sl.playSound(null, player.blockPosition(), SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.PLAYERS,
                0.6f, 1.6f);
        player.displayClientMessage(Component.literal(
                COUNT + " decoys away (" + registry.count() + " live in this dimension)")
                .withStyle(ChatFormatting.AQUA), true);
        player.getCooldowns().addCooldown(this, 40);
        return InteractionResultHolder.sidedSuccess(held, false);
    }
}
