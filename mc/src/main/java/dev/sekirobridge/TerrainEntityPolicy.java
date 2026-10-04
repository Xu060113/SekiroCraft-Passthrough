package dev.sekirobridge;

import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.TntEntity;

/** World identity and bounded waiting rules, shared by the client and integrated server. */
final class TerrainEntityPolicy {
    static final long ENTITY_WAIT_MS=500;
    static final double ENTITY_WAIT_RADIUS=8;
    record Scope(Object clientWorld,Object serverWorld){
        boolean contains(Object world){return world!=null && (world==clientWorld || world==serverWorld);}
    }
    static boolean applies(Scope scope,Object world,Class<?> type){
        return scope!=null && scope.contains(world) && !NativeTerrainExcluded.class.isAssignableFrom(type) &&
            (LivingEntity.class.isAssignableFrom(type) || TntEntity.class.isAssignableFrom(type) ||
             FallingBlockEntity.class.isAssignableFrom(type) || ItemEntity.class.isAssignableFrom(type));
    }
    static final class Wait {
        private long since=-1;
        boolean hold(boolean known,boolean nearby,long now){
            if(known || !nearby){since=-1;return false;}
            if(since<0)since=now;
            // One short wait per uninterrupted unknown episode. Entity.tick still runs,
            // including TNT's fuse; distant entities never wait for the player's samples.
            return now>=since && now-since<ENTITY_WAIT_MS;
        }
    }
}
