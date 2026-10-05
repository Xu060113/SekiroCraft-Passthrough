package dev.sekirobridge.mixin;
import dev.sekirobridge.NativeTerrain;
import dev.sekirobridge.ProjectileTerrain;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.world.World;
import net.minecraft.world.RaycastContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PersistentProjectileEntity.class)
public abstract class ProjectileTerrainMixin extends ProjectileEntity {
    @Shadow protected boolean inGround;
    @Shadow public abstract boolean isNoClip();
    @Unique private ProjectileTerrain.Ticket bridgeTicket;
    @Unique private BlockHitResult bridgeContact;
    protected ProjectileTerrainMixin(EntityType<? extends ProjectileEntity> type,World world){super(type,world);}
    @Inject(method="tick",at=@At("HEAD"),cancellable=true)
    private void bridgeAwaitNativeSegment(CallbackInfo ci){
        bridgeContact=null;
        if(inGround || isNoClip() || !ProjectileTerrain.active(this)){
            ProjectileTerrain.release(bridgeTicket);bridgeTicket=null;return;
        }
        var start=getPos();var end=start.add(getVelocity());
        if(!ProjectileTerrain.matches(bridgeTicket,start,end)){
            ProjectileTerrain.release(bridgeTicket);bridgeTicket=ProjectileTerrain.request(this,start,end);
        }
        if(ProjectileTerrain.waiting(bridgeTicket)){ci.cancel();return;}
        bridgeContact=ProjectileTerrain.result(bridgeTicket);
        // On a timeout retain only an actually observed local top surface.
        if(bridgeContact==null)bridgeContact=NativeTerrain.projectileRaycast(getWorld(),start,end);
        ProjectileTerrain.release(bridgeTicket);bridgeTicket=null;
    }
    @Redirect(method="tick",at=@At(value="INVOKE",target="Lnet/minecraft/world/World;raycast(Lnet/minecraft/world/RaycastContext;)Lnet/minecraft/util/hit/BlockHitResult;"))
    private BlockHitResult bridgeSegment(World world,RaycastContext context){
        return ProjectileTerrain.nearest(context.getStart(),world.raycast(context),bridgeContact);
    }
    @Inject(method="tick",at=@At("TAIL"))
    private void bridgePrefetch(CallbackInfo ci){
        bridgeContact=null;
        if(!inGround && !isRemoved() && !isNoClip() && ProjectileTerrain.active(this))
            bridgeTicket=ProjectileTerrain.request(this,getPos(),getPos().add(getVelocity()));
    }
}
