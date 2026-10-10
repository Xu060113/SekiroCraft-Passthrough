package dev.sekirobridge.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.sekirobridge.BridgeClient;
import net.minecraft.client.render.RenderPhase;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderPhase.class)
public abstract class CrumblingAlphaMixin {
    @Shadow @Final protected String name;

    // Target the public phase entry rather than a synthetic lambda hidden from Forge's AP.
    @Inject(method="startDrawing",at=@At("RETURN"))
    private void preserveBlockCoverage(CallbackInfo ci){
        if(BridgeClient.active() && "crumbling_transparency".equals(name))
            RenderSystem.blendFuncSeparate(GlStateManager.SrcFactor.DST_COLOR,
                GlStateManager.DstFactor.SRC_COLOR,GlStateManager.SrcFactor.ZERO,
                GlStateManager.DstFactor.ONE);
    }
}
