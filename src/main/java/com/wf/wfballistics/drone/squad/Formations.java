package com.wf.wfballistics.drone.squad;

import com.wf.wfballistics.WFBallistics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Registry of squad {@link Formation}s, keyed by id in the same shape as {@code FlightStageRegistry}: add a
 * shape by implementing {@link Formation} and calling {@link #register}.
 */
public final class Formations {

    /**
     * Wedge: alternating left/right of the leader, stepping back one rank every two slots. Four drones make
     * the classic V.
     */
    public static final Formation VEE = new Formation() {
        @Override
        public Vec3 slot(int index, Vec3 leaderPos, Vec3 forward, double spacing) {
            if (index <= 0) {
                return leaderPos;
            }
            int rank = (index + 1) / 2;
            double lateral = (index % 2 == 1 ? -1 : 1) * spacing * rank;
            double trail = -spacing * rank;
            Vec3 right = Formation.right(forward);
            return leaderPos.add(right.scale(lateral)).add(forward.scale(trail));
        }

        @Override
        public String id() {
            return "vee";
        }
    };

    /**
     * Single file behind the leader.
     */
    public static final Formation COLUMN = new Formation() {
        @Override
        public Vec3 slot(int index, Vec3 leaderPos, Vec3 forward, double spacing) {
            return index <= 0 ? leaderPos : leaderPos.add(forward.scale(-spacing * index));
        }

        @Override
        public String id() {
            return "column";
        }
    };

    /**
     * Line abreast: alternating left/right at the leader's rank, useful for a sweep.
     */
    public static final Formation LINE = new Formation() {
        @Override
        public Vec3 slot(int index, Vec3 leaderPos, Vec3 forward, double spacing) {
            if (index <= 0) {
                return leaderPos;
            }
            int rank = (index + 1) / 2;
            double lateral = (index % 2 == 1 ? -1 : 1) * spacing * rank;
            return leaderPos.add(Formation.right(forward).scale(lateral));
        }

        @Override
        public String id() {
            return "line";
        }
    };

    /**
     * A square grid with the leader in the middle of it.
     *
     * <p>The shape has to be built outward from the centre rather than laid out as a block, because a
     * {@link Formation} is only ever asked where one slot goes and never told how many there are. So slots
     * fill by Chebyshev ring: ring {@code r} being the {@code 8r} cells at {@code max(|x|,|z|) == r}, and
     * every ring that closes completes a filled {@code (2r+1)x(2r+1)} square around the leader. Nine drones
     * make the 3x3, twenty-five the 5x5, and the counts in between are that square part-built.
     *
     * <p>Within a ring the slots alternate either side of dead astern rather than walking round from one
     * corner, for the reason {@link #VEE} and {@link #LINE} alternate: a squad that does not fill its outer
     * ring should still sit balanced about the leader's track instead of hanging off one flank.
     */
    public static final Formation GRID = new Formation() {
        @Override
        public Vec3 slot(int index, Vec3 leaderPos, Vec3 forward, double spacing) {
            if (index <= 0) {
                return leaderPos;
            }
            int ring = 1;
            while (index >= (2 * ring + 1) * (2 * ring + 1)) {
                ring++;
            }
            int within = index - (2 * ring - 1) * (2 * ring - 1);
            int perimeter = 8 * ring;
            int step = (within + 1) / 2;
            int arc = within % 2 == 1 ? perimeter - step : step;

            int lateral;
            int along;
            if (arc <= ring) {
                lateral = arc;
                along = -ring;
            } else if (arc <= 3 * ring) {
                lateral = ring;
                along = arc - 2 * ring;
            } else if (arc <= 5 * ring) {
                lateral = 4 * ring - arc;
                along = ring;
            } else if (arc <= 7 * ring) {
                lateral = -ring;
                along = 6 * ring - arc;
            } else {
                lateral = arc - 8 * ring;
                along = -ring;
            }
            return leaderPos.add(Formation.right(forward).scale(spacing * lateral))
                    .add(forward.scale(spacing * along));
        }

        @Override
        public String id() {
            return "grid";
        }
    };

    public static final ResourceLocation DEFAULT = rl(VEE.id());

    private static final Map<ResourceLocation, Formation> BY_ID = new LinkedHashMap<>();

    static {
        register(VEE);
        register(COLUMN);
        register(LINE);
        register(GRID);
    }

    private Formations() {
    }

    public static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID, path);
    }

    public static void register(Formation formation) {
        BY_ID.put(rl(formation.id()), formation);
    }

    public static Formation get(ResourceLocation id) {
        Formation formation = id == null ? null : BY_ID.get(id);
        return formation != null ? formation : BY_ID.get(DEFAULT);
    }

    public static ResourceLocation parse(String id) {
        if (id == null || id.isEmpty()) {
            return DEFAULT;
        }
        ResourceLocation parsed = id.indexOf(':') >= 0 ? ResourceLocation.tryParse(id) : rl(id);
        return parsed != null && BY_ID.containsKey(parsed) ? parsed : DEFAULT;
    }

    public static Set<ResourceLocation> ids() {
        return Collections.unmodifiableSet(BY_ID.keySet());
    }
}
