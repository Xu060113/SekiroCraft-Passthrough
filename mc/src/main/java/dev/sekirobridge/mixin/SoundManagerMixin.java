package dev.sekirobridge.mixin;

import dev.sekirobridge.AudioBridge;
import net.minecraft.client.sound.SoundManager;
import net.minecraft.sound.SoundCategory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SoundManager.class)
public abstract class SoundManagerMixin {
    @Inject(method = "updateSoundVolume", at = @At("HEAD"))
    private void bridgeUserVolume(SoundCategory category, float volume, CallbackInfo ci) {
        AudioBridge.configuredMasterChanged(category, volume);
    }
}
