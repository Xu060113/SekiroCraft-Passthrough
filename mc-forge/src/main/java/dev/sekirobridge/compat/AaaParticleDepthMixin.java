package dev.sekirobridge.compat;

import dev.sekirobridge.ForgeEffectDepth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets="Effekseer.swig.EffekseerManagerCore",remap=false)
public abstract class AaaParticleDepthMixin {
    @Inject(method="DrawBack()V",at=@At("RETURN"),remap=false)
    private void backDepth(CallbackInfo ci){ForgeEffectDepth.capture(this,"DrawBack",null);}
    @Inject(method="DrawFront()V",at=@At("RETURN"),remap=false)
    private void frontDepth(CallbackInfo ci){ForgeEffectDepth.capture(this,"DrawFront",null);}
    @Inject(method="DrawBack(I)V",at=@At("RETURN"),remap=false)
    private void backLayerDepth(int mask,CallbackInfo ci){ForgeEffectDepth.capture(this,"DrawBack",mask);}
    @Inject(method="DrawFront(I)V",at=@At("RETURN"),remap=false)
    private void frontLayerDepth(int mask,CallbackInfo ci){ForgeEffectDepth.capture(this,"DrawFront",mask);}
}
