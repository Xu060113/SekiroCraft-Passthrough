package dev.sekirobridge.mixin;
import dev.sekirobridge.BridgeClient;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(MinecraftClient.class)
public abstract class ClientMixin {
    @Inject(method="isPaused",at=@At("HEAD"),cancellable=true)
    private void nativeMenuPause(CallbackInfoReturnable<Boolean> cir){
        var state=BridgeClient.state();
        if(BridgeClient.connected() && state!=null &&
           (state.flags()&(dev.sekirobridge.Protocol.NATIVE_UI|dev.sekirobridge.Protocol.NATIVE_CINEMATIC))!=0)
            cir.setReturnValue(true);
    }
    @Inject(method = "isWindowFocused", at = @At("HEAD"), cancellable = true)
    private void focus(CallbackInfoReturnable<Boolean> cir) {
        if (BridgeClient.armed())
            cir.setReturnValue(true);
    }
    @Inject(method = "getFramerateLimit", at = @At("HEAD"), cancellable = true)
    private void fps(CallbackInfoReturnable<Integer> cir) {
        if (BridgeClient.armed())
            cir.setReturnValue(60);
    }
}
