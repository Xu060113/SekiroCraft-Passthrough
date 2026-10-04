package dev.sekirobridge.mixin;
import dev.sekirobridge.BridgeClient;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {
    @Inject(method = {"renderSky", "renderClouds", "renderWeather"}, at = @At("HEAD"), cancellable = true)
    private void hideBackground(CallbackInfo ci) {
        if (BridgeClient.active())
            ci.cancel();
    }
}
