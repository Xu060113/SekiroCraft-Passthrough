package dev.sekirobridge.mixin;
import dev.sekirobridge.BridgeClient;
import dev.sekirobridge.InputForwarder;
import net.minecraft.client.Keyboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Keyboard.class)
public abstract class KeyboardMixin {
    @Inject(method={"onKey","onChar"},at=@At("HEAD"),cancellable=true)
    private void bridgeOnly(CallbackInfo ci){if(BridgeClient.active() && !InputForwarder.replaying)ci.cancel();}
}
