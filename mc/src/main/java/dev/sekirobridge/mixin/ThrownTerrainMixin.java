package dev.sekirobridge.mixin;
import dev.sekirobridge.NativeTerrain;
import dev.sekirobridge.ProjectileTerrain;
import dev.sekirobridge.NativeActorProxy;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.entity.projectile.thrown.ThrownEntity;
import net.minecraft.entity.projectile.ExplosiveProjectileEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.function.Predicate;

@Mixin({ThrownEntity.class,ExplosiveProjectileEntity.class})
public abstract class ThrownTerrainMixin extends ProjectileEntity {
    @Unique private ProjectileTerrain.Ticket bridgeTicket;
    @Unique private BlockHitResult bridgeContact;
    protected ThrownTerrainMixin(EntityType<? extends ProjectileEntity> type,World world){super(type,world);}
    @Inject(method="tick",at=@At("HEAD"),cancellable=true)
    private void bridgeAwait(CallbackInfo ci){
        bridgeContact=null;
        if(!ProjectileTerrain.active(this)){ProjectileTerrain.release(bridgeTicket);bridgeTicket=null;return;}
        var start=getPos();var end=start.add(getVelocity());
        if(!ProjectileTerrain.matches(bridgeTicket,start,end)){
            ProjectileTerrain.release(bridgeTicket);bridgeTicket=ProjectileTerrain.request(this,start,end);}
        if(ProjectileTerrain.waiting(bridgeTicket)){ci.cancel();return;}
        bridgeContact=ProjectileTerrain.result(bridgeTicket);
        if(bridgeContact==null)bridgeContact=NativeTerrain.projectileRaycast(getWorld(),start,end);
        ProjectileTerrain.release(bridgeTicket);bridgeTicket=null;
    }
    @Redirect(method="tick",at=@At(value="INVOKE",target="Lnet/minecraft/entity/projectile/ProjectileUtil;getCollision(Lnet/minecraft/entity/Entity;Ljava/util/function/Predicate;)Lnet/minecraft/util/hit/HitResult;"))
    private HitResult bridgeCollision(Entity entity,Predicate<Entity> predicate){
        var original=ProjectileUtil.getCollision(entity,predicate);
        return bridgeContact!=null && (original.getType()==HitResult.Type.MISS ||
            getPos().squaredDistanceTo(bridgeContact.getPos())<getPos().squaredDistanceTo(original.getPos()))?bridgeContact:original;
    }
    @Inject(method="tick",at=@At("TAIL"))
    private void bridgePrefetch(CallbackInfo ci){
        bridgeContact=null;
        if(!isRemoved() && ProjectileTerrain.active(this))bridgeTicket=ProjectileTerrain.request(this,getPos(),getPos().add(getVelocity()));
    }
}
