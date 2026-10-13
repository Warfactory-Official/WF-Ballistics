package com.wf.wflib.pda.client;

import com.wf.wflib.WFLib;
import com.wf.wflib.client.map.MapMenu;
import com.wf.wflib.client.map.MapPoint;
import com.wf.wflib.client.map.MapShape;
import com.wf.wflib.client.map.MapTool;
import com.wf.wflib.client.map.MapView;
import com.wf.wflib.client.map.WFMaps;
import com.wf.wflib.pda.markers.Marker;
import com.wf.wflib.pda.markers.MarkerEditPacket;
import com.wf.wflib.pda.markers.MarkerSyncPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/** Side markers as map layer {@link #LAYER} on every host; map menu add / remove while a PDA is carried. */
@EventBusSubscriber(modid = WFLib.MODID, value = Dist.CLIENT)
public final class MarkerClient implements MapTool {
    static final String LAYER = "wflib:markers";
    private static final String GROUP = "WF Markers";
    private static final ResourceLocation ICON = ResourceLocation.fromNamespaceAndPath(WFLib.MODID,
            "textures/map/pda_marker.png");
    private static final int COLOUR = 0xE0B860;
    /** Remove picks the nearest marker within this many GUI px of the click. */
    private static final double PICK_PIXELS = 12;

    private static List<Marker> markers = List.of();

    public static void accept(MarkerSyncPacket packet) {
        markers = packet.markers();
        publish();
    }

    public static List<Marker> markers() {
        return markers;
    }

    /** Current dimension only; dimension change (Clone) republishes. */
    private static void publish() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        ResourceKey<Level> here = mc.level.dimension();
        List<MapShape> shapes = new ArrayList<>();
        for (Marker m : markers) {
            if (m.dimension().equals(here.location())) {
                shapes.add(new MapShape.Icon(new MapPoint(m.pos().getX() + 0.5, m.pos().getZ() + 0.5), m.pos().getY(),
                        ICON, 32, 14, 0, COLOUR, 1, MapShape.Look.of(GROUP, 40).title(m.label()).label(m.label(),
                        COLOUR)));
            }
        }
        WFMaps.publish(LAYER, here, shapes);
    }

    @SubscribeEvent
    static void onLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        markers = List.of();
        WFMaps.clear(LAYER);
    }

    @SubscribeEvent
    static void onRespawn(ClientPlayerNetworkEvent.Clone event) {
        publish();
    }

    @Override
    public boolean available() {
        return PdaClient.carried();
    }

    @Override
    public void menu(MapMenu menu, MapView view) {
        menu.item("PDA: add marker here", pos -> {
            ResourceLocation dim = view.dimension().location();
            TextPromptScreen.open(Component.translatable("wflib.pda.marker.name"), Marker.MAX_LABEL,
                    "MARK " + (markers.size() + 1), label -> PacketDistributor.sendToServer(
                            MarkerEditPacket.add(dim, new BlockPos(pos.getX(), pos.getY(), pos.getZ()), label)));
        });
        double tolerance = PICK_PIXELS * view.blocksPerPixel();
        menu.item("PDA: remove marker here", pos -> {
            Marker nearest = null;
            double best = tolerance * tolerance;
            for (Marker m : markers) {
                if (!m.dimension().equals(view.dimension().location())) {
                    continue;
                }
                double dx = m.pos().getX() - pos.getX();
                double dz = m.pos().getZ() - pos.getZ();
                if (dx * dx + dz * dz <= best) {
                    best = dx * dx + dz * dz;
                    nearest = m;
                }
            }
            if (nearest != null) {
                PacketDistributor.sendToServer(MarkerEditPacket.remove(nearest.id()));
            }
        });
    }
}
