package dev.sekirobridge.mixin;

import dev.sekirobridge.NativeTerrain;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Entity.class)
public abstract class NativeFootstepMixin {
    // In 1.20.1 move's first lookup is landing/fall physics; only its second lookup
    // controls the vanilla step-distance gate. Do not change the landing block.
    @Redirect(method = "move", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/World;getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/BlockState;",
        ordinal = 1))
    private BlockState bridgeSteppingSurface(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.isAir() && NativeTerrain.supports((Entity)(Object)this)
            ? Blocks.STONE.getDefaultState() : state;
    }

    // move passes its original landing state to this sound/event helper. Resolve
    // that argument here, after fall and onSteppedOn have already used the real state.
    @ModifyVariable(method = "stepOnBlock", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private BlockState bridgeStepSoundSurface(BlockState state) {
        return state.isAir() && NativeTerrain.supports((Entity)(Object)this)
            ? Blocks.STONE.getDefaultState() : state;
    }
}
