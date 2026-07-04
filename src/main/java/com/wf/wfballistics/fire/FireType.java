package com.wf.wfballistics.fire;

public enum FireType {

    NORMAL(2.0F),
    PHOSPHORUS(5.0F);

    public final float damage;

    FireType(float damage) {
        this.damage = damage;
    }
}
