package dev.sekirobridge.mixin;
import dev.sekirobridge.BridgeClient;
import net.minecraft.client.render.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Inject(method = "update", at = @At("RETURN"))
    private void bridgeCamera(CallbackInfo ci) {
        if (BridgeClient.connected()) {
            dev.sekirobridge.PlayerExporter.camera((Camera)(Object)this);
        }
    }
}
