package com.wf.wflib.door;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.wf.wflib.WFLib;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Arrays;
import java.util.Locale;

/** {@code /wfdoor}: build a door without placing it by hand, toggle one, and lay the whole roster out in a row. */
@EventBusSubscriber(modid = WFLib.MODID)
public final class DoorCommands {

    private DoorCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("wfdoor")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("build")
                        .then(Commands.argument("type", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        Arrays.stream(DoorType.values()).map(DoorType::id), builder))
                                .executes(ctx -> build(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "type"), 0))))
                .then(Commands.literal("row")
                        .executes(ctx -> row(ctx.getSource(), 12))
                        .then(Commands.argument("spacing", IntegerArgumentType.integer(4, 64))
                                .executes(ctx -> row(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "spacing")))))
                .then(Commands.literal("toggle")
                        .executes(ctx -> toggle(ctx.getSource()))));
    }

    private static int build(CommandSourceStack source, String id, int skin) {
        DoorType type = byId(id);
        if (type == null) {
            source.sendFailure(Component.literal("no door called " + id));
            return 0;
        }
        ServerLevel level = source.getLevel();
        BlockPos pos = BlockPos.containing(source.getPosition());
        Direction facing = source.getEntity() == null
                ? Direction.NORTH
                : source.getEntity().getDirection().getOpposite();
        if (!place(level, pos, facing, type)) {
            source.sendFailure(Component.literal(type.id() + " does not fit here"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("built " + type.id() + " facing " + facing), false);
        return 1;
    }

    private static int row(CommandSourceStack source, int spacing) {
        ServerLevel level = source.getLevel();
        BlockPos origin = BlockPos.containing(source.getPosition());
        int built = 0;
        for (int i = 0; i < DoorType.values().length; i++) {
            DoorType type = DoorType.values()[i];
            if (place(level, origin.offset(i * spacing, 0, 0), Direction.NORTH, type)) {
                built++;
            }
        }
        int total = built;
        source.sendSuccess(() -> Component.literal("built " + total + " of "
                + DoorType.values().length + " doors, " + spacing + " apart along +x"), false);
        return total;
    }

    private static int toggle(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        BlockPos pos = BlockPos.containing(source.getPosition());
        for (BlockPos candidate : BlockPos.betweenClosed(pos.offset(-8, -4, -8), pos.offset(8, 8, 8))) {
            BlockState state = level.getBlockState(candidate);
            if (!(state.getBlock() instanceof DoorBlock door)) {
                continue;
            }
            BlockPos core = DoorFrame.findCore(level, candidate, door);
            if (core != null && level.getBlockEntity(core) instanceof DoorBlockEntity entity
                    && entity.toggle()) {
                source.sendSuccess(() -> Component.literal("toggled " + door.type().id()), false);
                return 1;
            }
        }
        source.sendFailure(Component.literal("no door within reach"));
        return 0;
    }

    private static boolean place(ServerLevel level, BlockPos pos, Direction facing, DoorType type) {
        Block block = ModDoors.DOORS.get(type).get();
        if (!(block instanceof DoorBlock door) || !door.hasRoom(level, pos, facing.getOpposite())) {
            return false;
        }
        BlockPos core = pos.relative(facing.getOpposite(), type.blockOffset());
        DoorFrame.structural(() -> {
            level.setBlock(core, block.defaultBlockState()
                    .setValue(DoorBlock.ROLE, DoorRole.CORE)
                    .setValue(DoorBlock.FACING, facing), Block.UPDATE_ALL);
            DoorFrame.fill(level, core, type.dimensions(), facing, block);
            for (int[] extra : type.extraDimensions()) {
                DoorFrame.fill(level, core, extra, facing, block);
            }
        });
        return true;
    }

    private static DoorType byId(String id) {
        String wanted = id.toLowerCase(Locale.ROOT);
        for (DoorType type : DoorType.values()) {
            if (type.id().equals(wanted)) {
                return type;
            }
        }
        return null;
    }
}
