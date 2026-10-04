package dev.sekirobridge.mixin;

import dev.sekirobridge.AudioBridge;
import net.minecraft.client.sound.SoundSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SoundSystem.class)
public abstract class SoundSystemMixin {
    @Inject(method = "start", at = @At("RETURN"))
    private void bridgeRestoreOutputAfterReload(CallbackInfo ci) {
        AudioBridge.engineStarted();
    }

    @Inject(method = "pauseAll", at = @At("HEAD"), cancellable = true)
    private void bridgeKeepBackgroundAudio(CallbackInfo ci) {
        if (AudioBridge.keepPlaying()) ci.cancel();
    }
}
