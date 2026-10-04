package dev.sekirobridge.mixin;
import dev.sekirobridge.NativeTerrain;
import net.minecraft.entity.Entity;
import net.minecraft.world.CollisionView;
import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(CollisionView.class)
public interface CollisionViewMixin {
    @Inject(method="getBlockCollisions",at=@At("RETURN"),cancellable=true)
    default void bridgeTerrain(Entity entity,Box box,CallbackInfoReturnable<Iterable<VoxelShape>> cir){
        cir.setReturnValue(NativeTerrain.add(this,entity,box,cir.getReturnValue()));
    }
}
