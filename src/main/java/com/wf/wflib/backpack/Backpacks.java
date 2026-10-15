package com.wf.wflib.backpack;

import com.wf.wflib.WFLib;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameRules;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.codec.ByteBufCodecs;
import com.mojang.serialization.Codec;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.minecraft.world.entity.EntityType;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.capabilities.EntityCapability;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.ToDoubleFunction;

/**
 * Worn backpack = player attachment {@link #WORN}, no equipment slot; contents exposed as player capability
 * {@link #HANDLER}. Contents on the stack ({@link #CONTENTS}); edited through {@link BackpackContainer}: owner's
 * {@code InventoryMenu} (mixin, slots {@link #FIRST_SLOT}..) or another player's {@link BackpackMenu} (probe action
 * {@link #OPEN}, refused while {@link #LOCKED}). Sync: owner full stack, trackers without contents.
 */
public final class Backpacks {
    public static final int MAX_SLOTS = 64;
    /** {@code InventoryMenu}: 46 vanilla slots, then backpack slots. */
    public static final int FIRST_SLOT = 46;
    /** Open + stay open within this (feet to feet); probe reach is 8. */
    public static final double VIEW_RANGE = 4;
    public static final ResourceLocation OPEN = ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "backpack_open");

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, WFLib.MODID);
    private static final DeferredRegister.DataComponents COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, WFLib.MODID);
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, WFLib.MODID);

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<ItemStack>> WORN = ATTACHMENTS.register(
            "worn_backpack", () -> AttachmentType.builder(() -> ItemStack.EMPTY).serialize(ItemStack.OPTIONAL_CODEC)
                    .copyOnDeath().build());
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ItemContainerContents>> CONTENTS =
            COMPONENTS.registerComponentType("backpack_contents", b -> b.persistent(ItemContainerContents.CODEC)
                    .networkSynchronized(ItemContainerContents.STREAM_CODEC));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> LOCKED =
            COMPONENTS.registerComponentType("backpack_locked", b -> b.persistent(Codec.BOOL)
                    .networkSynchronized(ByteBufCodecs.BOOL));
    /** Worn backpack's contents; null when none worn. */
    public static final EntityCapability<IItemHandler, Void> HANDLER = EntityCapability.createVoid(
            ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "backpack"), IItemHandler.class);
    public static final DeferredHolder<MenuType<?>, MenuType<BackpackMenu>> MENU =
            MENUS.register("backpack", () -> IMenuTypeExtension.create(BackpackMenu::client));

    /** Unit mass of a stack (kg x count); wfweight bridge installs its own. */
    public static volatile ToDoubleFunction<ItemStack> weigher = s -> 0;
    /** Client: inventory panel shown => owner's backpack slots active (creative tabs never). */
    public static volatile BooleanSupplier panelShown = () -> false;

    private static final Map<Player, BackpackContainer> CONTAINERS = new WeakHashMap<>();

    private Backpacks() {
    }

    public static void register(IEventBus modBus) {
        ATTACHMENTS.register(modBus);
        COMPONENTS.register(modBus);
        MENUS.register(modBus);
        modBus.addListener(Backpacks::onPayloads);
        modBus.addListener((RegisterCapabilitiesEvent e) -> e.registerEntity(HANDLER, EntityType.PLAYER,
                (player, ctx) -> worn(player).isEmpty() ? null : new BackpackHandler(container(player))));
        NeoForge.EVENT_BUS.addListener(Backpacks::onDrops);
        NeoForge.EVENT_BUS.addListener(Backpacks::onStartTracking);
        NeoForge.EVENT_BUS.addListener(Backpacks::onCommands);
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent e) -> sync(e.getEntity()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerRespawnEvent e) -> sync(e.getEntity()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent e) -> sync(e.getEntity()));
        BackpackProbe.registerServer();
        if (net.neoforged.fml.ModList.get().isLoaded("wfweight")) {
            com.wf.wflib.compat.wfweight.BackpackWeight.register();
        }
    }

    private static void onPayloads(RegisterPayloadHandlersEvent e) {
        var r = e.registrar(WFLib.MODID).versioned("backpack-1");
        r.playToServer(BackpackPackets.Action.TYPE, BackpackPackets.Action.STREAM_CODEC, BackpackPackets.Action::handle);
        r.playToClient(BackpackPackets.Worn.TYPE, BackpackPackets.Worn.STREAM_CODEC, BackpackPackets.Worn::handle);
    }

    public static ItemStack worn(Player player) {
        return player.getData(WORN);
    }

    public static BackpackContainer container(Player player) {
        synchronized (CONTAINERS) {
            return CONTAINERS.computeIfAbsent(player, BackpackContainer::new);
        }
    }

    public static boolean locked(ItemStack backpack) {
        return backpack.getOrDefault(LOCKED.get(), false);
    }

    public static NonNullList<ItemStack> contents(ItemStack backpack) {
        NonNullList<ItemStack> items = NonNullList.withSize(MAX_SLOTS, ItemStack.EMPTY);
        ItemContainerContents c = backpack.get(CONTENTS.get());
        if (c != null) {
            c.copyInto(items);
        }
        return items;
    }

    public static int used(ItemStack backpack) {
        ItemContainerContents c = backpack.get(CONTENTS.get());
        return c == null ? 0 : (int) c.nonEmptyStream().count();
    }

    /** Contents mass, before {@link BackpackSpec#contentWeight}. */
    public static double loadKg(ItemStack backpack) {
        ItemContainerContents c = backpack.get(CONTENTS.get());
        return c == null ? 0 : c.nonEmptyStream().mapToDouble(weigher).sum();
    }

    /** Key {@code key.wflib.backpack}: held backpack -> worn; worn -> inventory (full => refused). */
    static void toggleWorn(ServerPlayer player) {
        ItemStack worn = worn(player);
        if (worn.isEmpty()) {
            ItemStack held = player.getMainHandItem();
            if (!(held.getItem() instanceof BackpackItem)) {
                player.displayClientMessage(Component.translatable("wflib.backpack.none_held"), true);
                return;
            }
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            setWorn(player, held);
            return;
        }
        if (!player.getInventory().add(worn.copy())) {
            player.displayClientMessage(Component.translatable("wflib.backpack.no_room"), true);
            return;
        }
        setWorn(player, ItemStack.EMPTY);
    }

    static void toggleLock(ServerPlayer player) {
        ItemStack worn = worn(player);
        if (!worn.isEmpty()) {
            worn.set(LOCKED.get(), !locked(worn));
            sync(player);
        }
    }

    public static void setWorn(ServerPlayer player, ItemStack stack) {
        player.setData(WORN, stack);
        player.containerMenu.broadcastChanges();
        sync(player);
    }

    /** Owner: full stack; trackers: contents stripped. */
    public static void sync(Player player) {
        if (!(player instanceof ServerPlayer sp)) {
            return;
        }
        ItemStack worn = worn(sp);
        PacketDistributor.sendToPlayer(sp, new BackpackPackets.Worn(sp.getId(), worn.copy()));
        PacketDistributor.sendToPlayersTrackingEntity(sp, new BackpackPackets.Worn(sp.getId(), shown(worn)));
    }

    private static ItemStack shown(ItemStack worn) {
        ItemStack s = worn.copy();
        s.remove(CONTENTS.get());
        return s;
    }

    /** Op: {@code /wflib backpack wear|lock <players>} = the key / lock button for them. */
    private static void onCommands(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("wflib").then(Commands.literal("backpack")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("wear").then(Commands.argument("players", EntityArgument.players())
                        .executes(c -> each(c, Backpacks::toggleWorn))))
                .then(Commands.literal("lock").then(Commands.argument("players", EntityArgument.players())
                        .executes(c -> each(c, Backpacks::toggleLock))))));
    }

    private static int each(CommandContext<CommandSourceStack> c, java.util.function.Consumer<ServerPlayer> action)
            throws CommandSyntaxException {
        var players = EntityArgument.getPlayers(c, "players");
        players.forEach(action);
        return players.size();
    }

    private static void onStartTracking(PlayerEvent.StartTracking e) {
        if (e.getTarget() instanceof ServerPlayer target && e.getEntity() instanceof ServerPlayer viewer) {
            CustomPacketPayload p = new BackpackPackets.Worn(target.getId(), shown(worn(target)));
            PacketDistributor.sendToPlayer(viewer, p);
        }
    }

    private static void onDrops(LivingDropsEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer player)
                || player.level().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)) {
            return;
        }
        ItemStack worn = worn(player);
        if (!worn.isEmpty()) {
            e.getDrops().add(new net.minecraft.world.entity.item.ItemEntity(player.level(), player.getX(),
                    player.getEyeY() - 0.3, player.getZ(), worn.copy()));
            player.setData(WORN, ItemStack.EMPTY);
        }
    }

    /** Probe action: {@code viewer} opens {@code target}'s worn backpack. */
    static boolean open(ServerPlayer viewer, Entity target) {
        if (!(target instanceof ServerPlayer owner) || owner == viewer || owner.distanceTo(viewer) > VIEW_RANGE) {
            return false;
        }
        ItemStack worn = worn(owner);
        if (worn.isEmpty() || !(worn.getItem() instanceof BackpackItem item)) {
            return false;
        }
        if (locked(worn)) {
            viewer.displayClientMessage(Component.translatable("wflib.backpack.locked"), true);
            return false;
        }
        int slots = item.spec().slots();
        viewer.openMenu(new SimpleMenuProvider((id, inv, p) -> new BackpackMenu(id, inv, owner, worn, slots),
                Component.translatable("wflib.backpack.of", owner.getDisplayName(), worn.getHoverName())), buf -> {
            buf.writeVarInt(owner.getId());
            buf.writeVarInt(slots);
        });
        return true;
    }
}
