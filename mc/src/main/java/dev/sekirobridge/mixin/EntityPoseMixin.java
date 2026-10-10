package dev.sekirobridge.mixin;

import dev.sekirobridge.TerrainPoseProbe;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Entity.class)
public abstract class EntityPoseMixin {
    @Redirect(method="wouldPoseNotCollide(Lnet/minecraft/entity/EntityPose;)Z",
        at=@At(value="INVOKE",target="Lnet/minecraft/world/World;isSpaceEmpty(Lnet/minecraft/entity/Entity;Lnet/minecraft/util/math/Box;)Z"))
    private boolean bridgePoseSpace(World world,Entity entity,Box bounds){
        return TerrainPoseProbe.test(entity,()->world.isSpaceEmpty(entity,bounds));
    }
}
