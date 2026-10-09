package dev.sekirobridge.mixin;
import dev.sekirobridge.NativeActorProxy;
import dev.sekirobridge.NativeActorBounds;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(Entity.class)
public abstract class NativeActorDistanceMixin {
    @Inject(method="squaredDistanceTo(Lnet/minecraft/entity/Entity;)D",at=@At("HEAD"),cancellable=true)
    private void bridgeBodyDistance(Entity other,CallbackInfoReturnable<Double> ci){
        if(other instanceof NativeActorProxy && other.getBoundingBox() instanceof NativeActorBounds bounds)
            ci.setReturnValue(bounds.squaredMagnitude(((Entity)(Object)this).getPos()));
    }
}
