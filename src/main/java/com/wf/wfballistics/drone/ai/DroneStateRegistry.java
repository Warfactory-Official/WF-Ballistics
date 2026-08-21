package com.wf.wfballistics.drone.ai;

import com.wf.wfballistics.drone.DroneState;
import com.wf.wfballistics.drone.ai.state.CollectHandler;
import com.wf.wfballistics.drone.ai.state.SupplyHandler;
import com.wf.wfballistics.drone.ai.state.WorkHandler;
import com.wf.wfballistics.drone.ai.state.DeliverHandler;
import com.wf.wfballistics.drone.ai.state.DepletedHandler;
import com.wf.wfballistics.drone.ai.state.DownedHandler;
import com.wf.wfballistics.drone.ai.state.ExfilHandler;
import com.wf.wfballistics.drone.ai.state.IdleHandler;
import com.wf.wfballistics.drone.ai.state.LandingHandler;
import com.wf.wfballistics.drone.ai.state.MusterHandler;
import com.wf.wfballistics.drone.ai.state.PayloadRunHandler;
import com.wf.wfballistics.drone.ai.state.SurveilHandler;
import com.wf.wfballistics.drone.ai.state.TakeoffHandler;
import com.wf.wfballistics.drone.ai.state.TransitHandler;

import java.util.EnumMap;
import java.util.Map;

/**
 * Binds each {@link DroneState} to the handler that flies it. Swap a behaviour by registering a different
 * handler for the state; nothing else has to change.
 *
 * <p>Populated once during class init and only read afterwards, which is what makes it safe for workers to
 * resolve handlers concurrently.
 */
public final class DroneStateRegistry {

    private static final Map<DroneState, DroneStateHandler> BY_STATE = new EnumMap<>(DroneState.class);

    static {
        register(IdleHandler.INSTANCE);
        register(TakeoffHandler.INSTANCE);
        register(MusterHandler.INSTANCE);
        register(TransitHandler.INSTANCE);
        register(DeliverHandler.INSTANCE);
        register(CollectHandler.INSTANCE);
        register(WorkHandler.INSTANCE);
        register(SupplyHandler.INSTANCE);
        register(SurveilHandler.INSTANCE);
        register(ExfilHandler.INSTANCE);
        register(LandingHandler.INSTANCE);
        register(PayloadRunHandler.INSTANCE);
        register(DepletedHandler.INSTANCE);
        register(DownedHandler.INSTANCE);
    }

    private DroneStateRegistry() {
    }

    public static void register(DroneStateHandler handler) {
        BY_STATE.put(handler.state(), handler);
    }

    public static DroneStateHandler get(DroneState state) {
        DroneStateHandler handler = BY_STATE.get(state);
        return handler != null ? handler : IdleHandler.INSTANCE;
    }
}
