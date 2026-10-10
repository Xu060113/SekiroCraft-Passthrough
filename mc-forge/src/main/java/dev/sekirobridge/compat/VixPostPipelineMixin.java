package dev.sekirobridge.compat;

import dev.sekirobridge.ForgePostEffects;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets="com.guhao.vix.client.pipeline.PostEffectPipelines", remap=false)
public abstract class VixPostPipelineMixin {
    @Inject(method="RenderPost()V", at=@At("HEAD"), cancellable=true, remap=false)
    private static void preserveTransparentHud(CallbackInfo ci) {
        // The same original pass already ran on the intact world. Effects queued
        // by hand rendering remain queued for next frame's world pass.
        if (ForgePostEffects.deferLatePass()) ci.cancel();
    }
}
