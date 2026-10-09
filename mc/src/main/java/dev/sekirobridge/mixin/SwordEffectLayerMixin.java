package dev.sekirobridge.mixin;

import dev.sekirobridge.BridgeClient;
import dev.sekirobridge.SwordEffectCapture;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(RenderLayer.class)
public abstract class SwordEffectLayerMixin extends RenderPhase {
    protected SwordEffectLayerMixin(String name, Runnable begin, Runnable end) {
        super(name, begin, end);
    }

    @Redirect(method = "draw", at = @At(value = "INVOKE", target =
        "Lnet/minecraft/client/render/BufferRenderer;drawWithGlobalProgram(Lnet/minecraft/client/render/BufferBuilder$BuiltBuffer;)V"))
    private void captureGlow(BufferBuilder.BuiltBuffer buffer) {
        try (var capture = SwordEffectCapture.begin(BridgeClient.active(), name)) {
            BufferRenderer.drawWithGlobalProgram(buffer);
        }
    }
}
