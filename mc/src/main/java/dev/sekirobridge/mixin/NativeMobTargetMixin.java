package dev.sekirobridge.mixin;
import dev.sekirobridge.NativeActorProxy;
import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.ai.goal.ActiveTargetGoal;
import dev.sekirobridge.NativeMeleeGoal;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.ai.RangedAttackMob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(MobEntity.class)
public abstract class NativeMobTargetMixin {
    @Shadow protected GoalSelector targetSelector;
    @Shadow protected GoalSelector goalSelector;
    private boolean bridgeGoalsAdded;
    @Inject(method="tick",at=@At("HEAD"))
    private void bridgeNativeTargets(CallbackInfo ci){
        if(bridgeGoalsAdded)return;bridgeGoalsAdded=true;
        var mob=(MobEntity)(Object)this;
        if(mob.getWorld().isClient || !(mob instanceof HostileEntity))return;
        targetSelector.add(0,new ActiveTargetGoal<>(mob,NativeActorProxy.class,10,true,false,target -> ((NativeActorProxy)target).hostile()));
        if(mob instanceof PathAwareEntity path && !(mob instanceof RangedAttackMob))
            goalSelector.add(1,new NativeMeleeGoal(path));
    }
}
