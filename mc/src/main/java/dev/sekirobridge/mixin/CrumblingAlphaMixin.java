package dev.sekirobridge.mixin;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.sekirobridge.BridgeClient;
import net.minecraft.client.render.RenderPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderPhase.class)
public abstract class CrumblingAlphaMixin {
    // Exact 1.20.1 CRUMBLING_TRANSPARENCY start lambda. Vanilla preserves RGB
    // through DST_COLOR/SRC_COLOR but replaces destination alpha with crack
    // texture alpha (ONE/ZERO), punching holes in our imported opaque blocks.
    @Inject(method="method_23505()V",at=@At("RETURN"))
    private static void preserveBlockCoverage(CallbackInfo ci){
        if(BridgeClient.active())RenderSystem.blendFuncSeparate(
            GlStateManager.SrcFactor.DST_COLOR,GlStateManager.DstFactor.SRC_COLOR,
            GlStateManager.SrcFactor.ZERO,GlStateManager.DstFactor.ONE);
    }
}
