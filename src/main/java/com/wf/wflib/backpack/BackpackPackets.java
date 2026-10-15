package com.wf.wflib.backpack;

import com.wf.wflib.WFLib;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class BackpackPackets {
    private BackpackPackets() {
    }

    /** C2S: key {@code key.wflib.backpack} / inventory lock button. */
    public record Action(Kind kind) implements CustomPacketPayload {
        public enum Kind {
            TOGGLE_WORN, TOGGLE_LOCK
        }

        public static final Type<Action> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "backpack_action"));
        public static final StreamCodec<ByteBuf, Action> STREAM_CODEC = ByteBufCodecs.idMapper(
                i -> Kind.values()[i], Kind::ordinal).map(Action::new, Action::kind);

        @Override
        public Type<Action> type() {
            return TYPE;
        }

        void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (ctx.player() instanceof ServerPlayer p) {
                    switch (kind) {
                        case TOGGLE_WORN -> Backpacks.toggleWorn(p);
                        case TOGGLE_LOCK -> Backpacks.toggleLock(p);
                    }
                }
            });
        }
    }

    /** S2C: entity's worn backpack (trackers: without contents). */
    public record Worn(int entity, ItemStack stack) implements CustomPacketPayload {
        public static final Type<Worn> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "backpack_worn"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Worn> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Worn::entity, ItemStack.OPTIONAL_STREAM_CODEC, Worn::stack, Worn::new);

        @Override
        public Type<Worn> type() {
            return TYPE;
        }

        void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (ctx.player().level().getEntity(entity) instanceof Player p) {
                    p.setData(Backpacks.WORN, stack);
                }
            });
        }
    }
}
