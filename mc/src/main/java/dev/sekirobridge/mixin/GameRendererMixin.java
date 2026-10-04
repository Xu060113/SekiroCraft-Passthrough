package dev.sekirobridge.mixin;
import dev.sekirobridge.BridgeClient;
import net.minecraft.client.render.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void begin(CallbackInfo ci) {
        BridgeClient.renderBegin();
    }
    @Inject(method = "render", at = @At("RETURN"))
    private void end(CallbackInfo ci) {
        BridgeClient.FRAMES.overlay();
    }
    @Inject(
        method = "renderWorld",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/render/WorldRenderer;render(Lnet/minecraft/client/util/math/" +
                     "MatrixStack;FJZLnet/minecraft/client/render/Camera;Lnet/minecraft/client/render/" +
                     "GameRenderer;Lnet/minecraft/client/render/LightmapTextureManager;Lorg/joml/Matrix4f;)V",
            shift = At.Shift.AFTER))
    private void
    world(CallbackInfo ci) {
        BridgeClient.FRAMES.world();
    }
    @Inject(method = "getBasicProjectionMatrix", at = @At("HEAD"), cancellable = true)
    private void projection(double fov, CallbackInfoReturnable<Matrix4f> cir) {
        if (BridgeClient.active()) {
            var s = BridgeClient.state();
            cir.setReturnValue(new Matrix4f().setPerspective(s.fov(), s.aspect(), s.near() * s.scale(),
                                                             s.far() * s.scale()));
        }
    }
    @Inject(method = {"bobView", "tiltViewWhenHurt"}, at = @At("HEAD"), cancellable = true)
    private void noBob(CallbackInfo ci) {
        if (BridgeClient.active())
            ci.cancel();
    }
}
