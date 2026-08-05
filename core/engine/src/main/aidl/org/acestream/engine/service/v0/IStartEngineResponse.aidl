package org.acestream.engine.service.v0;

/** Result callback for startEngineWithCallback (upstream MIT-licensed AIDL contract). */
oneway interface IStartEngineResponse {
    void onResult(boolean success);
}
