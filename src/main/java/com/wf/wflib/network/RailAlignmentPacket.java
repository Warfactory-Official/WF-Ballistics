package com.wf.wflib.network;

import com.wf.wflib.WFLib;
import com.wf.wflib.rail.align.AlignPoint;
import com.wf.wflib.rail.align.Alignment;
import com.wf.wflib.rail.align.AlignmentView;
import com.wf.wflib.rail.align.BuildProgress;
import com.wf.wflib.rail.align.DesignClass;
import com.wf.wflib.rail.align.RouteStatus;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * Every line a player is allowed to see in one dimension, as a complete replacement.
 *
 * <p>A replacement rather than a delta because the set is small and because a delta protocol would have
 * to express "you may no longer see this", which is the case that matters most and the easiest to get
 * wrong.</p>
 */
public record RailAlignmentPacket(ResourceKey<Level> dimension, List<AlignmentView> alignments)
        implements CustomPacketPayload {

    public static final Type<RailAlignmentPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "rail_alignments"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RailAlignmentPacket> STREAM_CODEC =
            StreamCodec.of(RailAlignmentPacket::encode, RailAlignmentPacket::decode);

    /** Wire bound on how many routes one dimension may send. */
    private static final int MAX_ALIGNMENTS = 256;

    @Override
    public Type<RailAlignmentPacket> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, RailAlignmentPacket pkt) {
        buf.writeResourceKey(pkt.dimension);
        List<AlignmentView> views = pkt.alignments;
        buf.writeVarInt(Math.min(views.size(), MAX_ALIGNMENTS));
        for (int i = 0; i < views.size() && i < MAX_ALIGNMENTS; i++) {
            AlignmentView view = views.get(i);
            buf.writeUUID(view.id());
            buf.writeUtf(view.name(), Alignment.MAX_NAME);
            buf.writeUtf(view.ownerName(), Alignment.MAX_NAME);
            buf.writeUtf(view.lastEditorName(), Alignment.MAX_NAME);
            buf.writeInt(view.outlineColour());
            buf.writeInt(view.coreColour());
            buf.writeVarInt(view.designClass().ordinal());
            buf.writeVarInt(view.status().ordinal());
            buf.writeVarInt(Math.max(0, view.revision()));
            buf.writeBoolean(view.mayEdit());
            buf.writeBoolean(view.viaOperator());
            List<BuildProgress.Span> built = view.built().spans();
            buf.writeVarInt(Math.min(built.size(), BuildProgress.MAX_SPANS));
            for (int b = 0; b < built.size() && b < BuildProgress.MAX_SPANS; b++) {
                buf.writeFloat((float) built.get(b).from());
                buf.writeFloat((float) built.get(b).to());
            }
            List<AlignPoint> points = view.points();
            buf.writeVarInt(Math.min(points.size(), Alignment.MAX_POINTS));
            for (int p = 0; p < points.size() && p < Alignment.MAX_POINTS; p++) {
                AlignPoint point = points.get(p);
                buf.writeDouble(point.x());
                buf.writeDouble(point.z());
                buf.writeFloat((float) point.radius());
            }
        }
    }

    private static RailAlignmentPacket decode(RegistryFriendlyByteBuf buf) {
        ResourceKey<Level> dimension = buf.readResourceKey(Registries.DIMENSION);
        int count = Math.min(buf.readVarInt(), MAX_ALIGNMENTS);
        List<AlignmentView> views = new ArrayList<>(count);
        DesignClass[] classes = DesignClass.values();
        for (int i = 0; i < count; i++) {
            java.util.UUID id = buf.readUUID();
            String name = buf.readUtf(Alignment.MAX_NAME);
            String ownerName = buf.readUtf(Alignment.MAX_NAME);
            String lastEditor = buf.readUtf(Alignment.MAX_NAME);
            int outline = buf.readInt();
            int core = buf.readInt();
            int ordinal = buf.readVarInt();
            RouteStatus status = RouteStatus.byOrdinal(buf.readVarInt());
            int revision = buf.readVarInt();
            boolean mayEdit = buf.readBoolean();
            boolean viaOperator = buf.readBoolean();
            int spans = Math.min(buf.readVarInt(), BuildProgress.MAX_SPANS);
            List<BuildProgress.Span> built = new ArrayList<>(spans);
            for (int b = 0; b < spans; b++) {
                built.add(new BuildProgress.Span(buf.readFloat(), buf.readFloat()));
            }
            int points = Math.min(buf.readVarInt(), Alignment.MAX_POINTS);
            List<AlignPoint> list = new ArrayList<>(points);
            for (int p = 0; p < points; p++) {
                list.add(new AlignPoint(buf.readDouble(), buf.readDouble(), buf.readFloat()));
            }
            // An ordinal from a differently-built server must not throw on the render thread.
            DesignClass designClass = ordinal >= 0 && ordinal < classes.length
                    ? classes[ordinal] : DesignClass.BRANCH;
            views.add(new AlignmentView(id, name, ownerName, outline, core, designClass, mayEdit,
                    viaOperator, status, new BuildProgress(built), revision, lastEditor, list));
        }
        return new RailAlignmentPacket(dimension, views);
    }
}
