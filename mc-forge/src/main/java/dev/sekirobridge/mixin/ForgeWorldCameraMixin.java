package dev.sekirobridge.mixin;

import dev.sekirobridge.BridgeClient;
import dev.sekirobridge.PlayerExporter;
import dev.sekirobridge.ForgeEffectDepth;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldRenderer.class)
public abstract class ForgeWorldCameraMixin {
    @Inject(method="render",at=@At("HEAD"))
    private void captureFinalCamera(MatrixStack matrices,float tickDelta,long limitTime,boolean blockOutline,
        Camera camera,GameRenderer renderer,LightmapTextureManager lightmap,Matrix4f projection,CallbackInfo ci) {
        if(BridgeClient.active()) {
            PlayerExporter.camera(camera,matrices.peek().getPositionMatrix());
            ForgeEffectDepth.beginWorld();
        }
    }
}
