package dev.sekirobridge.mixin;
import dev.sekirobridge.NativeTerrain;
import net.minecraft.entity.Entity;
import net.minecraft.entity.MovementType;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Entity.class)
public abstract class TerrainMotionMixin {
    @Inject(method="move",at=@At("HEAD"),cancellable=true)
    private void bridgeRecoverTerrain(MovementType type,Vec3d movement,CallbackInfo ci){
        if(NativeTerrain.beforeMove((Entity)(Object)this,movement))ci.cancel();
    }
}
