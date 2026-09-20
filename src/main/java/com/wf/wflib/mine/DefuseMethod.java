package com.wf.wflib.mine;

/** How a mine is made safe again, if it can be. */
public enum DefuseMethod {

    /** It cannot. Shoot it, or walk around it. */
    NONE,

    /** Crouch up to it and hold still with whatever is in your hand. */
    HAND,

    /**
     * As {@link #HAND}, but the held item has to be in the mine's defusal tool tag (see {@link
     * MineEntity.Builder#defuseTool}).
     */
    TOOL
}
