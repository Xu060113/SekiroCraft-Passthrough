package dev.sekirobridge.mixin;
import dev.sekirobridge.BridgeClient;
import dev.sekirobridge.ForgePostEffects;
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
        ForgePostEffects.beginFrame();
        BridgeClient.renderBegin();
    }
    @Inject(method = "render", at = @At("RETURN"))
    private void end(CallbackInfo ci) {
        BridgeClient.FRAMES.overlay();
    }
    // Forge dispatches AFTER_LEVEL after WorldRenderer.render returns.
    // Capture only after all mod listeners complete and before hand depth is cleared.
    @Inject(method="renderWorld",at=@At(value="FIELD",
        target="Lnet/minecraft/client/render/GameRenderer;renderHand:Z",
        opcode=org.objectweb.asm.Opcodes.GETFIELD))
    private void world(CallbackInfo ci){
        ForgePostEffects.beforeWorldCapture();
        dev.sekirobridge.ForgeEffectDepth.mergeWorld();
        BridgeClient.FRAMES.world();
    }
    @Inject(method = "getBasicProjectionMatrix", at = @At("HEAD"), cancellable = true)
    private void projection(double fov, CallbackInfoReturnable<Matrix4f> cir) {
        if (BridgeClient.active()) {
            var s = BridgeClient.state();
            cir.setReturnValue(new Matrix4f().setPerspective(BridgeClient.projectionFov(fov), s.aspect(), s.near() * s.scale(),
                                                             s.far() * s.scale()));
        }
    }
    @Inject(method = {"bobView", "tiltViewWhenHurt"}, at = @At("HEAD"), cancellable = true)
    private void noBob(CallbackInfo ci) {
        if (BridgeClient.active())
            ci.cancel();
    }
}
