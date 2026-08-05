package org.acestream.engine.service.v0;

/** Ace Stream Engine lifecycle callback (upstream MIT-licensed AIDL contract). */
oneway interface IAceStreamEngineCallback {
    void onUnpacking();
    void onStarting();
    void onReady(int listenPort);
    void onStopped();
    void onWaitForNetworkConnection();
    void onPlaylistUpdated();
    void onEPGUpdated();
    void onRestartPlayer();
    void onSettingsUpdated();
    void onAuthUpdated();
}
