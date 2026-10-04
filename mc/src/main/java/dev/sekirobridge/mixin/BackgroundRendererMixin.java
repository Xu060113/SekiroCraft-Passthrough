package dev.sekirobridge.mixin;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.sekirobridge.BridgeClient;
import net.minecraft.client.render.BackgroundRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(BackgroundRenderer.class)
public abstract class BackgroundRendererMixin {
    @Inject(method = "render", at = @At("RETURN"))
    private static void clear(CallbackInfo ci) {
        if (BridgeClient.active())
            RenderSystem.clearColor(0, 0, 0, 0);
    }
    @Inject(method = "applyFog", at = @At("RETURN"))
    private static void fog(CallbackInfo ci) {
        if (BridgeClient.active()) {
            RenderSystem.setShaderFogStart(1e6f);
            RenderSystem.setShaderFogEnd(1e7f);
        }
    }
}
