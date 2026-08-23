package com.wf.wfballistics.drone.ai.coord;

import com.wf.wfballistics.drone.ai.DroneSnapshot;
import com.wf.wfballistics.drone.ai.DronePlan;
import com.wf.wfballistics.drone.ai.SquadView;
import com.wf.wfballistics.drone.ai.state.Tuning;
import com.wf.wfballistics.drone.squad.Formation;
import com.wf.wfballistics.drone.squad.Formations;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * How a squad decides where its members should be: the architecture of the formation, as opposed to its
 * shape.
 *
 * <p>A {@link com.wf.wfballistics.drone.squad.Formation} says "wedge" or "column"; this says what the wedge
 * is measured <em>against</em>, and that turns out to matter far more for how tightly a flight actually
 * holds together. The four stock models are the four classical answers, and they differ in exactly one
 * thing: where the {@link SquadAnchor} comes from.
 *
 * <table border="1">
 *   <caption>The stock models</caption>
 *   <tr><th>Model</th><th>Reference is</th><th>Costs</th></tr>
 *   <tr><td>{@link LeaderFollower}</td><td>the leader's measured state</td>
 *       <td>every error the leader has, amplified by the spacing</td></tr>
 *   <tr><td>{@link VirtualStructure}</td><td>a computed point flying the mission</td>
 *       <td>a reference that can drift from reality if not leashed</td></tr>
 *   <tr><td>{@link Flocking}</td><td>the neighbours, as forces</td>
 *       <td>no defined shape at all</td></tr>
 *   <tr><td>{@link Consensus}</td><td>every pairwise offset at once</td>
 *       <td>n² work, and a shape that can settle rotated</td></tr>
 * </table>
 *
 * <p><b>Why this is an interface and not a flag.</b> These are not four tunings of one controller. They
 * disagree about which drone is even allowed to be a reference, whether the leader flies a station or its
 * own route, and whether the frame persists between ticks: differences that cannot be expressed as
 * constants and were, before this existed, hard-coded assumptions spread across the guidance layer.
 *
 * <p><b>Runs on a worker thread</b>, like the rest of {@code DroneBrain}. Implementations see only value
 * types and must not reach for the level; the one piece of state they are allowed to keep between ticks is
 * the {@link SquadAnchor}, and it is handed in and handed back rather than stored, so there is nothing here
 * for two threads to race on.
 */
public interface CoordinationModel {

    /**
     * Registry path, e.g. {@code "virtual_structure"}.
     */
    String id();

    /**
     * Place the reference frame for this tick, before any drone has been planned.
     *
     * <p>This is the whole of what makes one model different from another. A model that references a real
     * drone reads it here; a model that computes its reference integrates it here, from {@code previous} and
     * the mission, without looking at where anybody actually is.
     *
     * @param previous last tick's anchor for this squad, or null on the first tick of a flight, after a
     *                 squad re-forms around a new leader, or when the squad has been away over unloaded
     *                 terrain. Treat it as "seed yourself"
     */
    SquadAnchor advance(SquadView squad, @Nullable SquadAnchor previous);

    /**
     * Second chance at the anchor once the leader's plan for this tick is known.
     *
     * <p>Only a model whose reference <em>is</em> the leader has anything to do here, and for that model it
     * is worth a lot: a follower matched to the velocity the leader chose a tick ago is a follower
     * permanently one tick of acceleration behind, which shows up worst exactly where it is most visible, in
     * the turns. Every other model ignores the plan, which is rather the point of them.
     */
    default SquadAnchor refresh(SquadView squad, SquadAnchor anchor, DronePlan leaderPlan) {
        return anchor;
    }

    /**
     * @return true if the leader holds a station like everyone else rather than flying its own route
     * directly.
     *
     * <p>False for the models that reference the leader: a drone cannot meaningfully station-keep on
     * itself. True for the ones that do not, and it is a real behaviour difference and not a formality: with
     * the frame computed rather than measured, the leader is just the drone that happens to occupy slot 0,
     * and holding it to that slot means its own tracking error stops being the formation's tracking error.
     * It still runs its handler either way, so the mission, the arrivals and the program all step on
     * unchanged: it is only the flying that is delegated.
     */
    default boolean stationsLeader() {
        return false;
    }

    /**
     * @return the velocity this drone should fly to hold its place in the squad, and what that velocity is
     * accelerating at, for the flight model to lean into ahead of the error. Called for every member the
     * model is responsible for, after {@link #advance} and {@link #refresh} have settled the frame.
     */
    SquadCommand guide(DroneSnapshot self, SquadView squad, SquadAnchor anchor);

    /**
     * @return true when the squad has assembled into whatever shape this model actually holds, so a form-up
     * can end. Asked once per tick, of the leader, by {@code MusterHandler}.
     *
     * <p><b>The model has to answer this, because only the model knows what "in formation" means for it.</b>
     * This used to be one hard-coded test — every member within half a spacing of
     * {@code Formation.slot(index, leader.pos(), ...)} — which is the {@link LeaderFollower} frame written out
     * by hand. For the three slot-flying models that is right, or near enough. For {@link Flocking} it is a
     * question with no answer: a flock has no slots, so it was being asked how far it was from a wedge it was
     * never going to fly, the answer was always "too far", and every flocking launch therefore sat over the
     * pad until {@code Tuning#MUSTER_TIMEOUT} gave up on it. Sixty seconds, every time, with the flight in
     * perfectly good order the whole while.
     *
     * <p>The default is that same slot test, but measured off the frame the squad is flying rather than off
     * the leader standing in for it, and with the tolerance scaled to the ordered spacing exactly as before.
     * A member with no thrust left is not asked: it is on its way out of the sky and cannot reach a station,
     * so holding the flight for it holds the flight for ever.
     *
     * @param anchor the frame this squad is holding, or null if it has not settled one yet
     */
    default boolean formedUp(SquadView squad, @Nullable SquadAnchor anchor) {
        if (anchor == null) {
            return false;
        }
        Formation shape = Formations.get(squad.formationId());
        Vec3 forward = Formation.forward(anchor.yaw());
        double tolerance = Math.max(Tuning.MUSTER_IN_PLACE_FLOOR, squad.spacing() * Tuning.MUSTER_IN_PLACE);
        for (DroneSnapshot member : squad.slots()) {
            if (!member.state().powered()) {
                continue;
            }
            Vec3 slot = shape.slot(squad.indexOf(member), anchor.pos(), forward, squad.spacing());
            if (member.pos().distanceTo(slot) > tolerance) {
                return false;
            }
        }
        return true;
    }
}
