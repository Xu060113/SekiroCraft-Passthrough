package dev.sekirobridge.mixin;

import dev.sekirobridge.SwordEffectCapture;
import net.minecraft.client.gl.ShaderProgram;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ShaderProgram.class)
public abstract class SwordEffectShaderMixin {
    @Inject(method = "bind", at = @At("HEAD"))
    private void restoreBeforeBind(CallbackInfo ci) {
        SwordEffectCapture.beforeShaderBind();
    }

    @Inject(method = "bind", at = @At("RETURN"))
    private void captureGlow(CallbackInfo ci) {
        SwordEffectCapture.shaderBound();
    }
}
