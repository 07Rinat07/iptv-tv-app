package org.acestream.engine.service.v0;

import org.acestream.engine.service.v0.IAceStreamEngineCallback;
import org.acestream.engine.service.v0.IStartEngineResponse;

/** Ace Stream Engine service interface (upstream MIT-licensed AIDL contract). */
interface IAceStreamEngine {
    void registerCallback(IAceStreamEngineCallback cb);
    void unregisterCallback(IAceStreamEngineCallback cb);
    void startEngine();
    void registerCallbackExt(IAceStreamEngineCallback cb, boolean skipMobileNetworksCheck);
    void startEngineWithCallback(IStartEngineResponse callback);
    int getEngineApiPort();
    int getHttpApiPort();
    String getAccessToken();
    void enableAceCastServer();
}
