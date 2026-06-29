package com.wf.wfballistics.fire;

public enum FireType {

    NORMAL(2.0F),
    BALEFIRE(5.0F);

    public final float damage;

    FireType(float damage) {
        this.damage = damage;
    }
}
