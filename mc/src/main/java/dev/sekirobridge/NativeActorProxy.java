package dev.sekirobridge;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.world.World;
/** Ephemeral invisible hit target; native physics alone supplies its position. */
public final class NativeActorProxy extends PathAwareEntity implements NativeTerrainExcluded {
    long nativeId;int nativeFlags;long epoch,stage;
    public NativeActorProxy(EntityType<? extends PathAwareEntity> type,World world){super(type,world);
        setAiDisabled(true);setNoGravity(true);noClip=true;setPersistent();setSilent(true);}
    public boolean hostile(){return (nativeFlags&1)!=0 && isAlive() && CombatBridge.serverActive();}
    @Override public boolean canHit(){return isAlive();}
    @Override public boolean damage(DamageSource source,float amount){
        if((nativeFlags&2)!=0 || !CombatBridge.serverActive() || getWorld().isClient)return false;
        return super.damage(source,amount);
    }
    @Override protected void applyDamage(DamageSource source,float amount){
        if(!getWorld().isClient && amount>0)CombatBridge.hit(this,source,amount);
        // Health is acknowledged by Sekiro; never run MC death/loot from predicted damage.
    }
    @Override public boolean isPushable(){return false;}
    @Override public void takeKnockback(double strength,double x,double z){}
    @Override public void checkDespawn(){}
    @Override public void tick(){super.tick();setVelocity(0,0,0);}
}
