package dev.sekirobridge;

import net.minecraft.client.MinecraftClient;
import net.minecraft.sound.SoundCategory;

/** Keeps the original OpenAL audio active while Minecraft is the background peer. */
public final class AudioBridge {
    private static boolean applyingOutput;
    private static final AudioSession SESSION = new AudioSession(new AudioSession.Output() {
        public float configuredMaster() {
            return MinecraftClient.getInstance().options.getSoundVolumeOption(SoundCategory.MASTER)
                .getValue().floatValue();
        }
        public void master(float volume) {
            applyingOutput = true;
            try {
                // Do not change SimpleOption: even an unrelated options save must retain the user's value.
                MinecraftClient.getInstance().getSoundManager().updateSoundVolume(SoundCategory.MASTER, volume);
            } finally {
                applyingOutput = false;
            }
        }
        public void resume() { MinecraftClient.getInstance().getSoundManager().resumeAll(); }
    });

    private AudioBridge() {}

    /** Call from the client/render thread after the latest bridge state has been polled. */
    public static void tick() {
        SESSION.tick(BridgeClient.connected(), gamePlaying());
    }

    /** Call when disarming or stopping, before disposing the bridge/native handle. */
    public static void release() { SESSION.release(); }
    public static String status() { return SESSION.status(); }

    private static boolean gamePlaying() {
        var client = MinecraftClient.getInstance();
        return !client.isPaused() &&
            (client.currentScreen == null || !client.currentScreen.shouldPause()) &&
            (client.getOverlay() == null || !client.getOverlay().pausesGame());
    }

    public static boolean keepPlaying() {
        // Preserve actual pause screens, unload/reload, and every normal stopAll call.
        return SESSION.connected() && BridgeClient.connected() && gamePlaying();
    }

    public static void configuredMasterChanged(SoundCategory category, float volume) {
        if (!applyingOutput && category == SoundCategory.MASTER)
            SESSION.configuredMasterChanged(volume);
    }

    public static void engineStarted() {
        // SoundSystem.start reads the unchanged option after device/resource reload.
        if (BridgeClient.connected()) SESSION.engineStarted();
    }
}
