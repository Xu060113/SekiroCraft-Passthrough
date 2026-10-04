package dev.sekirobridge.mixin;
import dev.sekirobridge.BridgeClient;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {
    // Name-only selectors also match buffer-building overloads, including static renderSky.
    @Inject(method = {"renderSky(Lnet/minecraft/client/util/math/MatrixStack;Lorg/joml/Matrix4f;FLnet/" +
                      "minecraft/client/render/Camera;ZLjava/lang/Runnable;)V",
                      "renderClouds(Lnet/minecraft/client/util/math/MatrixStack;Lorg/joml/Matrix4f;FDDD)V",
                      "renderWeather(Lnet/minecraft/client/render/LightmapTextureManager;FDDD)V"},
            at = @At("HEAD"), cancellable = true)
    private void hideBackground(CallbackInfo ci) {
        if (BridgeClient.active())
            ci.cancel();
    }
}
