package dev.sekirobridge.mixin;
import dev.sekirobridge.BridgeClient;
import dev.sekirobridge.InputForwarder;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(Mouse.class)
public abstract class MouseMixin {
    // MC must not clip or warp the OS cursor while Sekiro owns foreground input.
    @Inject(method = {"lockCursor", "unlockCursor"}, at = @At("HEAD"), cancellable = true)
    private void noCapture(CallbackInfo ci) {
        if (BridgeClient.active())
            ci.cancel();
    }
    @Inject(method={"onMouseButton", "onCursorPos", "onMouseScroll", "updateMouse"},at=@At("HEAD"),cancellable=true)
    private void bridgeOnly(CallbackInfo ci){
        if(BridgeClient.active() && !InputForwarder.replaying)ci.cancel();
    }
    @Inject(method = "isCursorLocked", at = @At("HEAD"), cancellable = true)
    private void logicalCapture(CallbackInfoReturnable<Boolean> cir) {
        if (BridgeClient.active())
            cir.setReturnValue(MinecraftClient.getInstance().currentScreen == null);
    }
}
