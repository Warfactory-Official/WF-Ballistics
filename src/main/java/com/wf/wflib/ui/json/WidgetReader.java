package com.wf.wflib.ui.json;

import com.wf.wflib.ui.WidgetDef;

/** Builds a widget from its JSON object; every key it does not read is reported as unknown. */
@FunctionalInterface
public interface WidgetReader {
    WidgetDef read(JsonProps props);
}
