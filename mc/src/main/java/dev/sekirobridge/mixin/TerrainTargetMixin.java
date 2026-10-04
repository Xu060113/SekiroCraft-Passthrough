package dev.sekirobridge.mixin;

import dev.sekirobridge.BridgeClient;
import dev.sekirobridge.NativeTerrain;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.item.BlockItem;
import net.minecraft.util.hit.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class TerrainTargetMixin {
    @Inject(method="updateTargetedEntity",at=@At("RETURN"))
    private void nativePlacementTarget(float tickDelta,CallbackInfo ci){
        if(!BridgeClient.active())return;
        var c=MinecraftClient.getInstance();
        var camera=c.getCameraEntity();
        if(c.player==null || camera==null || c.interactionManager==null ||
            !(c.player.getMainHandStack().getItem() instanceof BlockItem) &&
            !(c.player.getOffHandStack().getItem() instanceof BlockItem))return;
        var start=camera.getCameraPosVec(tickDelta);
        var current=c.crosshairTarget;
        double reach=c.interactionManager.getReachDistance();
        if(current!=null && current.getType()!=HitResult.Type.MISS)
            reach=Math.min(reach,Math.max(0,start.distanceTo(current.getPos())-1e-5));
        var end=start.add(camera.getRotationVec(tickDelta).multiply(reach));
        var terrain=NativeTerrain.raycast(start,end);
        if(terrain==null)return;
        // Keep vanilla block/entity reach and ordering. This only supplies the
        // missing native ground face; Minecraft still performs useOnBlock,
        // inventory consumption and authoritative server placement itself.
        // Clip the ray BEFORE snapping the virtual face to an integer block cell:
        // comparing the snapped hit distance could steal a closer vanilla target.
        c.crosshairTarget=terrain;
        c.targetedEntity=null;
    }
}
