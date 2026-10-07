package dev.sekirobridge.mixin;

import dev.sekirobridge.NativeVoidProtection;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class NativeVoidMixin {
    @Inject(method="attemptTickInVoid",at=@At("HEAD"),cancellable=true)
    private void bridgeNativeVoid(CallbackInfo ci){
        // Low native floors can map below MC's bottomY - 64. Preserve ordinary
        // damage/death handling; only native-owned entities skip this height test.
        if(NativeVoidProtection.blocks((Entity)(Object)this))ci.cancel();
    }
}
