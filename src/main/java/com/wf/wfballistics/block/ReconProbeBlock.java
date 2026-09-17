package com.wf.wfballistics.block;

import com.mojang.serialization.MapCodec;
import com.wf.wfballistics.block.entity.ReconProbeBlockEntity;
import com.wf.wfballistics.recon.ReconNet;
import com.wf.wfballistics.recon.ReconNetwork;
import com.wf.wfballistics.recon.SensorHandle;
import com.wf.wfballistics.recon.env.Atmosphere;
import com.wf.wfballistics.recon.env.ReconWeather;
import com.wf.wfballistics.recon.grid.GridNode;
import com.wf.wfballistics.recon.propagate.RadarPropagator;
import com.wf.wfballistics.recon.propagate.SeismicPropagator;
import com.wf.wfballistics.recon.propagate.SonarPropagator;
import com.wf.wfballistics.recon.propagate.ThermalPropagator;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/** One of the probes: see {@link ProbeKind} for the table. */
public class ReconProbeBlock extends BaseEntityBlock {

    public static final MapCodec<ReconProbeBlock> CODEC = simpleCodec(props -> new ReconProbeBlock(props, ProbeKind.RADAR));
    /** Drives the model swap. A grid you can read by walking it beats a grid you read by opening screens. */
    public static final BooleanProperty ONLINE = BooleanProperty.create("online");

    private final ProbeKind kind;

    public ReconProbeBlock(Properties props, ProbeKind kind) {
        super(props);
        this.kind = kind;
        this.registerDefaultState(this.stateDefinition.any().setValue(ONLINE, false));
    }

    public ProbeKind kind() {
        return this.kind;
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(ONLINE);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ReconProbeBlockEntity(pos, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    /**
     * Comparator output is calibration progress, so a warming probe can drive a light without anybody having to
     * poll it.
     */
    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof ReconProbeBlockEntity probe)) {
            return 0;
        }
        if (probe.online()) {
            return 15;
        }
        int target = Math.max(1, probe.kind().calibrationTicks());
        return Math.min(14, probe.calibration() * 14 / target);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                 BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return createTickerHelper(type, ModBlockEntities.RECON_PROBE.get(),
                (lvl, pos, st, be) -> be.serverTick());
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (level.isClientSide || !(level.getBlockEntity(pos) instanceof ReconProbeBlockEntity probe)) {
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        ProbeKind kind = probe.kind();
        player.displayClientMessage(Component.literal(String.format(Locale.ROOT,
                        "%s %s [%s]  net %s%s", kind.label(), kind.sensing() ? "probe" : "station",
                        probe.status(), Long.toHexString(probe.netId()),
                        probe.boundNet() == null ? "" : " (bound)"))
                .withStyle(ChatFormatting.AQUA), false);
        player.displayClientMessage(Component.literal(String.format(Locale.ROOT,
                "  %.0f blk %s, %.0f blk link, %d FE/t (%d stored)",
                kind.baseRange(), kind.sensing() ? "range" : "sample", kind.linkRange(), probe.draw(kind),
                probe.energy().getEnergyStored())), false);
        if (!kind.sensing() && level instanceof ServerLevel sl) {
            this.reportWeather(sl, pos, probe, player);
        }
        if (kind.underwater() && level instanceof ServerLevel sl) {
            this.reportSonar(sl, pos, probe, player);
        }

        GridNode node = probe.node();
        if (node == null || !node.online()) {
            player.displayClientMessage(Component.literal(
                            "  no route to a hub - out of link range, or no clearance to the next node")
                    .withStyle(ChatFormatting.RED), false);
            return InteractionResult.SUCCESS;
        }
        player.displayClientMessage(Component.literal(String.format(Locale.ROOT,
                "  %d hop(s) from the hub, error x%.2f, quality capped at %s",
                node.hops(), Math.pow(1.15, node.hops()),
                com.wf.wfballistics.recon.track.TrackQuality.capFor(node.hops()))), false);
        if (node.downstream() > 0) {
            player.displayClientMessage(Component.literal(String.format(Locale.ROOT,
                            "  %d node(s) route through this one - breaking it takes them all down",
                            node.downstream()))
                    .withStyle(ChatFormatting.GOLD), false);
        }
        return InteractionResult.SUCCESS;
    }

    /** Which side of the layer this set listens from, and whether it is shouting. */
    private void reportSonar(ServerLevel level, BlockPos pos, ReconProbeBlockEntity probe, Player player) {
        if (!probe.wet()) {
            player.displayClientMessage(Component.literal(
                            "  dry - a hydrophone has to stand in water, or directly under it")
                    .withStyle(ChatFormatting.RED), false);
            return;
        }
        double depth = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
                pos.getX(), pos.getZ()) - (pos.getY() + 0.5);
        player.displayClientMessage(Component.literal(String.format(Locale.ROOT,
                        "  %s, %.0f blk down (%s the layer at %.0f)",
                        probe.pinging()
                                ? "PINGING - ranges anything with a hull, and is heard twice as far"
                                : "passive - bearing only, and says nothing",
                        depth, depth > SonarPropagator.LAYER_DEPTH ? "below" : "above",
                        SonarPropagator.LAYER_DEPTH))
                .withStyle(probe.pinging() ? ChatFormatting.GOLD : ChatFormatting.AQUA), false);
    }

    /** What a met station is for, in four lines. */
    private void reportWeather(ServerLevel level, BlockPos pos, ReconProbeBlockEntity probe, Player player) {
        Atmosphere raw = ReconWeather.sample(level);
        Holder<Biome> biome = level.getBiome(pos);
        double baseTemp = biome.value().getModifiedClimateSettings().temperature();
        double downfall = biome.value().getModifiedClimateSettings().downfall();
        double ambient = raw.ambientC(baseTemp, downfall);

        player.displayClientMessage(Component.literal(String.format(Locale.ROOT,
                "  ambient %.1f C  (base %.2f, humidity %.2f, %s, sun %+.2f)",
                ambient, baseTemp, downfall, ReconWeather.daypart(level, raw), raw.solar())), false);
        player.displayClientMessage(Component.literal("  " + ReconWeather.forecast(level, raw))
                .withStyle(ChatFormatting.GRAY), false);

        Atmosphere here = raw.withCoverage(1.0);
        player.displayClientMessage(Component.literal(String.format(Locale.ROOT,
                        "  corrected:   radar x%.2f  thermal x%.2f  seismic x%.2f  sonar x%.2f",
                        here.correct(RadarPropagator.environment(here, downfall)),
                        here.correct(ThermalPropagator.environment(here, ambient)),
                        here.correct(SeismicPropagator.environment(here)),
                        here.correct(SonarPropagator.environment(here))))
                .withStyle(ChatFormatting.GREEN), false);
        player.displayClientMessage(Component.literal(String.format(Locale.ROOT,
                        "  uncorrected: radar x%.2f  thermal x%.2f  seismic x%.2f  sonar x%.2f",
                        raw.correct(RadarPropagator.environment(raw, downfall)),
                        raw.correct(ThermalPropagator.environment(raw, ambient)),
                        raw.correct(SeismicPropagator.environment(raw)),
                        raw.correct(SonarPropagator.environment(raw))))
                .withStyle(ChatFormatting.DARK_GRAY), false);

        ReconNetwork net = ReconNet.existing(level, probe.netId());
        if (net == null) {
            return;
        }
        int covered = 0;
        for (SensorHandle handle : net.sensorHandles()) {
            BlockPos at = handle.pos();
            if (net.coverageAt(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5) > 0.0) {
                covered++;
            }
        }
        player.displayClientMessage(Component.literal(String.format(Locale.ROOT,
                "  %d of %d sensor(s) on this net are covered, by %d station(s)",
                covered, net.sensorCount(), net.stationCount())), false);
    }
}
