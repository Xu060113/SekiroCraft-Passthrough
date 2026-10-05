package dev.sekirobridge.mixin;
import dev.sekirobridge.NativeActorProxy;
import dev.sekirobridge.ProjectileTerrain;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.projectile.ExplosiveProjectileEntity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(ExplosiveProjectileEntity.class)
public abstract class NativeProjectileTargetMixin extends ProjectileEntity {
    protected NativeProjectileTargetMixin(EntityType<? extends ProjectileEntity> type,World world){super(type,world);}
    @Inject(method="canHit(Lnet/minecraft/entity/Entity;)Z",at=@At("RETURN"),cancellable=true)
    private void bridgeProxy(Entity target,CallbackInfoReturnable<Boolean> cir){
        // ExplosiveProjectile's extra noClip predicate excludes our immobile
        // proxies. They still retain normal projectile owner/vehicle checks.
        if(target instanceof NativeActorProxy && ProjectileTerrain.active(target))cir.setReturnValue(super.canHit(target));
    }
}
