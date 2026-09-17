package com.wf.wfballistics.door;

import net.minecraft.util.StringRepresentable;

/** What one block of a door multiblock is. */
public enum DoorRole implements StringRepresentable {
    CORE("core"),
    DUMMY("dummy"),
    OPEN("open");

    private final String name;

    DoorRole(String name) {
        this.name = name;
    }

    /** Whether a block in this role lets things through. */
    public boolean passable() {
        return this == OPEN;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
