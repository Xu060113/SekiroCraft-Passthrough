package dev.sekirobridge.mixin;

import dev.sekirobridge.BridgeClient;
import dev.sekirobridge.CrouchModelPose;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Run the final composition after playerAnimator (2000) and weapon hooks (1000).
@Mixin(value=PlayerEntityModel.class, priority=500)
public abstract class PlayerCrouchModelMixin {
    @Unique private boolean bridgeCrouch;
    @Unique private boolean bridgeOldSneaking;

    @Inject(method="setAngles(Lnet/minecraft/entity/LivingEntity;FFFFF)V", at=@At("HEAD"))
    private void bridgeStandingBase(LivingEntity entity,float walk,float amount,float age,
                                    float yaw,float pitch,CallbackInfo ci) {
        var client=MinecraftClient.getInstance();
        bridgeCrouch=BridgeClient.active() && entity==client.player &&
            !client.options.getPerspective().isFirstPerson() && entity.isInSneakingPose() &&
            !client.player.getAbilities().flying && !entity.isFallFlying() &&
            !entity.isSwimming() && !entity.isSleeping() && !entity.hasVehicle();
        if(bridgeCrouch) {
            var model=(PlayerEntityModel<?>)(Object)this;
            bridgeOldSneaking=model.sneaking;
            // Let weapon layers animate a standing base; compose crouch once below.
            model.sneaking=false;
        }
    }

    @Inject(method="setAngles(Lnet/minecraft/entity/LivingEntity;FFFFF)V", at=@At("RETURN"))
    private void bridgeCrouchAfterAnimation(LivingEntity entity,float walk,float amount,float age,
                                           float yaw,float pitch,CallbackInfo ci) {
        if(!bridgeCrouch)return;
        bridgeCrouch=false;
        var model=(PlayerEntityModel<?>)(Object)this;
        model.sneaking=bridgeOldSneaking;
        CrouchModelPose.apply(model);
    }
}
