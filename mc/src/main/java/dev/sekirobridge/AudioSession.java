package dev.sekirobridge;

/** Owns only a temporary output gain; never changes the user's persisted options. */
final class AudioSession {
    static final float TEMPORARY_MASTER = 0.7f;
    interface Output {
        float configuredMaster();
        void master(float volume);
        void resume();
    }
    private final Output output;
    private boolean connected;
    private boolean playing;
    private boolean temporaryMaster;
    private float observedMaster;

    AudioSession(Output output) { this.output = output; }

    void tick(boolean bridgeConnected, boolean gamePlaying) {
        if (!bridgeConnected) {
            release();
            return;
        }
        float configured = output.configuredMaster();
        if (!connected) {
            connected = true;
            observedMaster = configured;
            temporaryMaster = configured == 0.0f;
            if (temporaryMaster) output.master(TEMPORARY_MASTER);
        } else if (Float.compare(configured, observedMaster) != 0) {
            // Also catches option changes made without SoundManager's normal callback.
            configuredMasterChanged(configured);
            output.master(configured);
        }
        if (gamePlaying && !playing) output.resume();
        playing = gamePlaying;
    }

    void configuredMasterChanged(float volume) {
        if (!connected) return;
        observedMaster = volume;
        // A user's deliberate mute remains muted for the rest of this connection.
        temporaryMaster = false;
    }

    void engineStarted() {
        if (connected && temporaryMaster) output.master(TEMPORARY_MASTER);
    }

    void release() {
        boolean restore = connected && temporaryMaster;
        connected = playing = temporaryMaster = false;
        if (restore) output.master(output.configuredMaster());
    }

    boolean connected() { return connected; }
    boolean temporaryMaster() { return temporaryMaster; }
    String status() {
        if (!connected) return "off";
        return (playing ? "playing; " : "paused; ") +
            (temporaryMaster ? "temporary master 70%" : "user master volume");
    }
}
