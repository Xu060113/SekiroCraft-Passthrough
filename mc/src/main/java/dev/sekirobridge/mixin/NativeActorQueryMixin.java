package dev.sekirobridge.mixin;
import dev.sekirobridge.*;
import net.minecraft.entity.Entity;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;
import java.util.*;
import java.util.function.Predicate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(World.class)
public abstract class NativeActorQueryMixin {
    @Inject(method="getOtherEntities",at=@At("RETURN"),cancellable=true)
    private void bridgeBodyQuery(Entity except,Box box,Predicate<? super Entity> predicate,CallbackInfoReturnable<List<Entity>> ci){
        var actors=NativeActorIndex.actors((World)(Object)this);if(actors.isEmpty())return;
        var result=new ArrayList<>(ci.getReturnValue());
        result.removeIf(e->e instanceof NativeActorProxy && !e.getBoundingBox().intersects(box));
        for(var actor:actors)if(actor!=except && actor.getBoundingBox().intersects(box) && !result.contains(actor) && predicate.test(actor))result.add(actor);
        ci.setReturnValue(result);
    }
    @Inject(method="collectEntitiesByType(Lnet/minecraft/util/TypeFilter;Lnet/minecraft/util/math/Box;Ljava/util/function/Predicate;Ljava/util/List;I)V",at=@At("RETURN"))
    private <T extends Entity> void bridgeTypedBodyQuery(TypeFilter<Entity,T> type,Box box,Predicate<? super T> predicate,List<? super T> result,int limit,CallbackInfo ci){
        var actors=NativeActorIndex.actors((World)(Object)this);if(actors.isEmpty())return;
        result.removeIf(e->e instanceof NativeActorProxy && !((Entity)e).getBoundingBox().intersects(box));
        for(var actor:actors){if(result.size()>=limit)break;T value=type.downcast(actor);
            if(value!=null && actor.getBoundingBox().intersects(box) && !result.contains(value) && predicate.test(value))result.add(value);}
    }
}
