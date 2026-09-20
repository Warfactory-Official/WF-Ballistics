package com.wf.wflib.industry;

/** A detected base: a connected group of occupied region cells, with the combined provocation inside it. */
public record IndustryCluster(int centerX, int centerZ,
                              int minX, int minZ, int maxX, int maxZ,
                              int value, int cellCount) {

    public double distanceSqTo(double x, double z) {
        double dx = centerX - x;
        double dz = centerZ - z;
        return dx * dx + dz * dz;
    }

    /**
     * @return the longer horizontal side of the base's bounding box, a rough measure of its extent.
     */
    public int span() {
        return Math.max(maxX - minX, maxZ - minZ);
    }

    @Override
    public String toString() {
        return "base at (" + centerX + ", " + centerZ + ") value " + value
                + " across " + cellCount + " cell(s), span " + span();
    }
}
