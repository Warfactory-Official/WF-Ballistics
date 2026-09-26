package com.wf.wflib.client.journeymap;

import com.wf.wflib.WFLib;
import com.wf.wflib.client.journeymap.rail.AlignmentOverlayLayer;
import com.wf.wflib.client.journeymap.rail.RailMapEditor;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.IClientPlugin;
import journeymap.api.v2.common.JourneyMapPlugin;

/**
 * The mod's one JourneyMap plugin, which attaches every map layer wflib draws.
 *
 * <p>One per mod, and not by convention: {@code PluginHelper} keeps its client plugins in a map keyed
 * by {@code getModId()}, so a second annotated class returning the same id simply replaces the first
 * in that map and is never initialised. JourneyMap still logs it as found, so the failure looks like a
 * live plugin that happens to draw nothing. The rail editor was that second plugin, and the only place
 * it showed was a missing line in JourneyMap's own log.</p>
 */
@JourneyMapPlugin(apiVersion = IClientAPI.API_VERSION)
public final class WflibJourneyMapPlugin implements IClientPlugin {

    private final ReconMapLayer recon = new ReconMapLayer();
    private final RailMapEditor rail = new RailMapEditor();
    private final AlignmentOverlayLayer lines = new AlignmentOverlayLayer();

    @Override
    public String getModId() {
        return WFLib.MODID;
    }

    @Override
    public void initialize(IClientAPI clientApi) {
        this.recon.attach(clientApi);
        this.rail.attach(clientApi);
        this.lines.attach(clientApi);
    }
}
