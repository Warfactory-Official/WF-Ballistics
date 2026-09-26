package com.wf.wflib.drone;

import com.wf.wflib.api.WFEventType;
import com.wf.wflib.drone.ai.coord.CoordinationModels;
import com.wf.wflib.drone.squad.Formation;
import com.wf.wflib.drone.squad.Formations;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** This drone's place in its squad: id, leadership, formation, coordination model, ordered size. */
public final class DroneSquadRole {

    private final DroneEntity drone;
    private long squadId;
    private boolean leader;
    private ResourceLocation formationId = Formations.DEFAULT;
    private double formationSpacing = Formation.DEFAULT_SPACING;
    private ResourceLocation coordinationId = CoordinationModels.DEFAULT;
    /** How many drones the mission ordered. */
    private int squadSize = 1;

    DroneSquadRole(DroneEntity drone) {
        this.drone = drone;
    }

    public long squadId() {
        return this.squadId;
    }

    public boolean leader() {
        return this.leader;
    }

    public void promote() {
        this.leader = true;
        this.drone.recordEvent(WFEventType.PROMOTED, "took over the squad");
    }

    public ResourceLocation formationId() {
        return this.formationId;
    }

    public double formationSpacing() {
        return this.formationSpacing;
    }

    /** How far apart this drone's squad flies. */
    public void setFormationSpacing(double spacing) {
        this.formationSpacing = Formation.clampSpacing(spacing);
    }

    public ResourceLocation coordinationId() {
        return this.coordinationId;
    }

    /** Which architecture this drone's squad holds its formation with. */
    public void setCoordinationId(@Nullable ResourceLocation id) {
        this.coordinationId = id != null ? CoordinationModels.parse(id.toString()) : CoordinationModels.DEFAULT;
    }

    public int squadSize() {
        return this.squadSize;
    }

    /** How many drones this drone should expect to be flying with. */
    public void setSquadSize(int size) {
        this.squadSize = Math.max(1, size);
    }

    public void setSquad(long squadId, boolean leader, ResourceLocation formationId) {
        this.squadId = squadId;
        this.leader = leader;
        this.formationId = formationId != null ? formationId : Formations.DEFAULT;
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Id", this.squadId);
        tag.putBoolean("Leader", this.leader);
        tag.putString("Formation", this.formationId.toString());
        tag.putDouble("Spacing", this.formationSpacing);
        tag.putString("Coordination", this.coordinationId.toString());
        tag.putInt("Size", this.squadSize);
        return tag;
    }

    void load(CompoundTag tag) {
        this.squadId = tag.getLong("Id");
        this.leader = tag.getBoolean("Leader");
        this.formationId = Formations.parse(tag.getString("Formation"));
        this.formationSpacing = tag.contains("Spacing")
                ? Formation.clampSpacing(tag.getDouble("Spacing")) : Formation.DEFAULT_SPACING;
        this.coordinationId = tag.contains("Coordination")
                ? CoordinationModels.parse(tag.getString("Coordination")) : CoordinationModels.DEFAULT;
        this.squadSize = Math.max(1, tag.getInt("Size"));
    }
}
