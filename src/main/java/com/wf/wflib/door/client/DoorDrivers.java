package com.wf.wflib.door.client;

import com.wf.gemrender.gltf.NodeRotation;
import com.wf.gemrender.gltf.NodeTable;
import com.wf.gemrender.gltf.PoseDriver;

/** The three things a door part does: slide along an axis, turn about one, and stop existing. */
public final class DoorDrivers {

    private DoorDrivers() {
    }

    public static int translationOffset(int slot) {
        return slot * NodeTable.TRS_STRIDE + NodeTable.TRANSLATION;
    }

    public static int scaleOffset(int slot) {
        return slot * NodeTable.TRS_STRIDE + NodeTable.SCALE;
    }

    /** Moves a bone along an axis by {@code blocks} times the track. */
    public record Slide(int offset, float axisX, float axisY, float axisZ, DoorTrack track,
                        float blocks) implements PoseDriver {

        public static Slide along(NodeTable table, int slot, float x, float y, float z, DoorTrack track,
                                  float blocks) {
            return new Slide(translationOffset(slot), x, y, z, track, blocks);
        }

        @Override
        public void apply(float timeSeconds, float[] scratch) {
            float value = track.at(timeSeconds) * blocks;
            scratch[offset] += axisX * value;
            scratch[offset + 1] += axisY * value;
            scratch[offset + 2] += axisZ * value;
        }

        @Override
        public float cycleSeconds() {
            return track.spanSeconds();
        }

        @Override
        public int offset() {
            return offset;
        }
    }

    /** Turns a bone about an axis by {@code degrees} times the track. */
    public record Turn(int offset, float axisX, float axisY, float axisZ, DoorTrack track,
                       float degrees) implements PoseDriver {

        public static Turn about(NodeTable table, int slot, float x, float y, float z, DoorTrack track,
                                 float degrees) {
            float[] axis = NodeRotation.axis(x, y, z);
            return new Turn(NodeRotation.offsetOf(table, slot), axis[0], axis[1], axis[2], track, degrees);
        }

        @Override
        public void apply(float timeSeconds, float[] scratch) {
            NodeRotation.compose(scratch, offset, axisX, axisY, axisZ,
                    (float) Math.toRadians(track.at(timeSeconds) * degrees));
        }

        @Override
        public float cycleSeconds() {
            return track.spanSeconds();
        }

        @Override
        public int offset() {
            return offset;
        }
    }

    /** Collapses a bone to nothing while the track is at or below {@code threshold}. */
    public record Gate(int offset, DoorTrack track, float threshold) implements PoseDriver {

        public static Gate above(NodeTable table, int slot, DoorTrack track, float threshold) {
            return new Gate(scaleOffset(slot), track, threshold);
        }

        @Override
        public void apply(float timeSeconds, float[] scratch) {
            if (track.at(timeSeconds) <= threshold) {
                scratch[offset] = 0.0f;
                scratch[offset + 1] = 0.0f;
                scratch[offset + 2] = 0.0f;
            }
        }

        @Override
        public float cycleSeconds() {
            return track.spanSeconds();
        }

        @Override
        public int offset() {
            return offset;
        }
    }
}
