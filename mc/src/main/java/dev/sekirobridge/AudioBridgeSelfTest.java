package dev.sekirobridge;

import java.util.ArrayList;
import java.util.List;

/** Exercises the production volume/pause ownership logic without opening an audio device. */
public final class AudioBridgeSelfTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        ++checks;
        if (!condition) throw new AssertionError(message);
    }
    private static final class Output implements AudioSession.Output {
        float configured;
        float gain;
        int resumes;
        final List<Float> writes = new ArrayList<>();
        Output(float volume) { configured = gain = volume; }
        public float configuredMaster() { return configured; }
        public void master(float volume) { gain = volume; writes.add(volume); }
        public void resume() { ++resumes; }
        void userVolume(AudioSession session, float volume) {
            configured = gain = volume;
            session.configuredMasterChanged(volume);
        }
    }
    public static void main(String[] args) {
        var output = new Output(0);
        var session = new AudioSession(output);
        session.tick(false, false);
        check(output.writes.isEmpty() && output.resumes == 0, "disconnected client untouched");
        session.tick(true, true);
        check(output.gain == .7f && output.configured == 0, "muted master temporarily audible without option mutation");
        check(session.temporaryMaster() && output.resumes == 1, "connection owns only its output gain and resumes once");
        for (int i = 0; i < 120; ++i) session.tick(true, true);
        check(output.writes.size() == 1 && output.resumes == 1, "steady frames do not spam OpenAL commands");
        output.gain = 0; // SoundSystem.start rereads the unchanged option.
        session.engineStarted();
        check(output.gain == .7f && output.configured == 0, "device reload reapplies only temporary output gain");
        session.tick(true, false);
        check(session.status().startsWith("paused;") && output.resumes == 1, "real pause screen respected");
        session.tick(true, true);
        check(output.resumes == 2, "leaving pause resumes existing vanilla sounds");
        session.tick(false, false);
        check(output.gain == 0 && !session.connected(), "connection loss restores original mute");
        int writes = output.writes.size();
        session.release();
        session.engineStarted();
        check(output.writes.size() == writes, "release idempotent and disconnected reload untouched");

        output = new Output(.35f);
        session = new AudioSession(output);
        session.tick(true, true);
        session.engineStarted();
        session.release();
        check(output.writes.isEmpty() && output.gain == .35f, "nonzero user volume never overridden");

        output = new Output(0);
        session = new AudioSession(output);
        session.tick(true, true);
        output.userVolume(session, .2f);
        session.tick(true, true);
        session.engineStarted();
        session.release();
        check(output.gain == .2f && output.configured == .2f, "manual slider change survives reload and release");
        check(output.writes.size() == 1, "relinquishing volume does not overwrite the slider");

        output = new Output(0);
        session = new AudioSession(output);
        session.tick(true, true);
        output.userVolume(session, 0);
        for (int i = 0; i < 120; ++i) session.tick(true, true);
        session.engineStarted();
        session.release();
        check(output.gain == 0 && output.writes.size() == 1, "deliberate remute stays silent for entire connection");

        output = new Output(0);
        session = new AudioSession(output);
        session.tick(true, true);
        output.configured = .4f; // A third-party option edit without vanilla callback.
        session.tick(true, true);
        check(output.gain == .4f && !session.temporaryMaster(), "missed option callback still relinquishes output ownership");
        session.release();
        check(output.gain == .4f, "release restores no stale master value");
        session.tick(true, true);
        check(output.gain == .4f, "reconnect preserves newly configured volume");

        output = new Output(0);
        session = new AudioSession(output);
        session.tick(true, true);
        output.configured = .55f;
        session.release();
        check(output.gain == .55f, "disconnect before next tick restores current option, not stale zero");
        System.out.println("Audio bridge checks passed: " + checks);
    }
}
