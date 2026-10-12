package com.wf.wflib.client.map.journeymap;

import com.wf.wflib.WFLib;
import com.wf.wflib.client.map.WFMaps;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.IClientPlugin;
import journeymap.api.v2.common.JourneyMapPlugin;

/**
 * The mod's only JM plugin: {@code PluginHelper} keys client plugins by {@code getModId()}, a second
 * annotated class silently replaces the first (still logged as found). Tell: {@code journeymap.log}
 * {@code Initialized IJourneyMapPlugin as:}.
 */
@JourneyMapPlugin(apiVersion = IClientAPI.API_VERSION)
public final class WflibJourneyMapPlugin implements IClientPlugin {

    @Override
    public String getModId() {
        return WFLib.MODID;
    }

    @Override
    public void initialize(IClientAPI clientApi) {
        new JourneyMapTools().attach();
        WFMaps.addHost(new JourneyMapHost(clientApi));
    }
}
