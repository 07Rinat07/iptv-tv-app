package org.acestream.engine.service.v0;

/** Callback for startEngineWithCallback. */
oneway interface IStartEngineResponse {
    void onResult(boolean success);
}
