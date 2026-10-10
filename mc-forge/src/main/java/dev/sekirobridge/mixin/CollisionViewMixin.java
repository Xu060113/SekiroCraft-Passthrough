package dev.sekirobridge.mixin;

import dev.sekirobridge.NativeTerrain;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockCollisionSpliterator;
import net.minecraft.world.CollisionView;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Implements;
import org.spongepowered.asm.mixin.Interface;
import org.spongepowered.asm.mixin.Mixin;

/** Forge 0.8.5 cannot inject into default interface methods. Override on real worlds. */
@Mixin(World.class)
@Implements(@Interface(iface=CollisionView.class,prefix="bridge$",remap=Interface.Remap.ALL))
public abstract class CollisionViewMixin {
    public Iterable<VoxelShape> bridge$getBlockCollisions(Entity entity,Box box) {
        CollisionView world=(CollisionView)(Object)this;
        // Forge generalizes this iterator with a result function; use its vanilla shape projection.
        Iterable<VoxelShape> vanilla=()->new BlockCollisionSpliterator<VoxelShape>(
            world,entity,box,false,(position,shape)->shape);
        return NativeTerrain.add(this,entity,box,vanilla);
    }
}
