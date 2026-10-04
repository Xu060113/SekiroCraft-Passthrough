package dev.sekirobridge.mixin;
import dev.sekirobridge.BridgeClient;
import net.minecraft.client.render.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow protected abstract void setPos(double x, double y, double z);
    @Shadow protected abstract void setRotation(float yaw, float pitch);
    @Inject(method = "update", at = @At("RETURN"))
    private void bridgeCamera(CallbackInfo ci) {
        if (BridgeClient.active()) {
            var s = BridgeClient.state();
            setRotation(s.yaw(), s.pitch());
            setPos(s.mcX(s.ex()), s.mcY(s.ey()), s.mcZ(s.ez()));
        }
    }
}
