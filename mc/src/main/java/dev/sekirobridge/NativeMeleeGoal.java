package dev.sekirobridge;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.entity.mob.PathAwareEntity;
/** Vanilla melee with local steering when native ground has no MC path nodes. */
public final class NativeMeleeGoal extends MeleeAttackGoal {
    private final PathAwareEntity actor;
    public NativeMeleeGoal(PathAwareEntity mob){super(mob,1,true);actor=mob;}
    private boolean nativeTarget(){return actor.getTarget() instanceof NativeActorProxy proxy && proxy.hostile();}
    @Override public boolean canStart(){return nativeTarget() && (actor.canSee(actor.getTarget()) || super.canStart());}
    @Override public boolean shouldContinue(){return nativeTarget();}
    @Override public void tick(){super.tick();
        if(nativeTarget() && actor.getNavigation().isIdle() && NativeTerrain.supports(actor) && actor.canSee(actor.getTarget())){
            var target=actor.getTarget();actor.getMoveControl().moveTo(target.getX(),target.getY(),target.getZ(),1);
        }
    }
}
