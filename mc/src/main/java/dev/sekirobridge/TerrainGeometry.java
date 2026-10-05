package dev.sekirobridge;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.hit.BlockHitResult;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Geometry shared by the client and integrated server; no game-thread or JNI state. */
final class TerrainGeometry {
    static final long RETAIN_MS=1500;
    record Key(long x,long z){}
    record Surface(long tick,Box box){}
    private final HashMap<Key,Surface> cells=new HashMap<>();
    // A ray miss still marks the cell as observed. No message is a different state.
    private final HashMap<Key,Long> known=new HashMap<>();
    private long epoch,sequence;

    void clear(){cells.clear();known.clear();epoch=sequence=0;}
    Map<Key,Long> known(){return Map.copyOf(known);}
    List<Surface> merge(Protocol.State state,ByteBuffer packet,long now){
        long nextEpoch=packet.getLong(16),tick=packet.getLong(8),nextSequence=packet.getLong(0);
        if(nextEpoch!=state.epoch() || !Protocol.fresh(now,tick))return null;
        if(epoch!=nextEpoch){clear();epoch=nextEpoch;}
        if(sequence==nextSequence)return null;
        sequence=nextSequence;
        double cx=packet.getFloat(24),cy=packet.getFloat(28),cz=packet.getFloat(32);
        // The native sampler uses fixed world cells, whose centers are n*0.5+0.25.
        long centerX=Math.round((cx-.25)/.5),centerZ=Math.round((cz-.25)/.5);
        // Each packet can be a peripheral patch. Evict relative to the player, not
        // that patch, otherwise the next patch would erase recently sampled neighbors.
        long playerX=(long)Math.floor(state.px()/.5),playerZ=(long)Math.floor(state.pz()/.5);
        cells.entrySet().removeIf(entry -> !retained(now,entry.getValue().tick()) ||
            Math.abs(entry.getKey().x()-playerX)>16 || Math.abs(entry.getKey().z()-playerZ)>16);
        known.entrySet().removeIf(entry -> !retained(now,entry.getValue()) ||
            Math.abs(entry.getKey().x()-playerX)>16 || Math.abs(entry.getKey().z()-playerZ)>16);
        for(int z=0;z<9;++z)for(int x=0;x<9;++x){
            int index=z*9+x;
            var key=new Key(centerX+x-4,centerZ+z-4);
            known.put(key,tick);
            // A completed ray miss is known empty space, not a missing IPC update.
            if(packet.get(364+index)==0){cells.remove(key);continue;}
            double height=packet.getFloat(40+index*4);
            if(!Double.isFinite(height) || Math.abs(height-cy)>12)continue;
            double minX=key.x()*.5,minZ=key.z()*.5;
            var box=new Box(state.mcX(minX),state.mcY(height-4),state.mcZ(minZ+.5),
                state.mcX(minX+.5),state.mcY(height),state.mcZ(minZ));
            var previous=cells.get(key);
            // Suppress submillimeter float noise without moving collision boundaries.
            if(previous!=null && Math.abs(previous.box().maxY-box.maxY)<1e-4*state.scale())box=previous.box();
            cells.put(key,new Surface(tick,box));
        }
        return List.copyOf(cells.values());
    }
    static boolean retained(long now,long tick){return tick>0 && now>=tick && now-tick<=RETAIN_MS;}
    /** A limited neighbor check for short non-player sampling waits. */
    static boolean touchesKnown(Map<Key,Long> known,double size,Box probe,long now){
        if(known.isEmpty() || size<=0)return false;
        long minX=(long)Math.floor(probe.minX/size),maxX=(long)Math.floor(probe.maxX/size);
        long minZ=(long)Math.floor(probe.minZ/size),maxZ=(long)Math.floor(probe.maxZ/size);
        if(maxX-minX>32 || maxZ-minZ>32)return false;
        for(long z=minZ;z<=maxZ;++z)for(long x=minX;x<=maxX;++x){
            Long tick=known.get(new Key(x,-z-1));
            if(tick!=null && retained(now,tick))return true;
        }
        return false;
    }
    /** All fixed X/Z cells touched by the feet's swept box must have completed rays. */
    static boolean covers(Map<Key,Long> known,double size,Box swept,long now){
        if(known.isEmpty() || !Double.isFinite(size) || size<=0)return false;
        long minX=(long)Math.floor((swept.minX+1e-7)/size),maxX=(long)Math.floor((swept.maxX-1e-7)/size);
        long minZ=(long)Math.floor((swept.minZ+1e-7)/size),maxZ=(long)Math.floor((swept.maxZ-1e-7)/size);
        if(maxX-minX>32 || maxZ-minZ>32)return false;
        for(long z=minZ;z<=maxZ;++z)for(long x=minX;x<=maxX;++x){
            // Minecraft Z reverses the host grid's orientation, including the cell index.
            Long tick=known.get(new Key(x,-z-1));
            if(tick==null || !retained(now,tick))return false;
        }
        return true;
    }
    static boolean supports(Box feet,List<Surface> surfaces,long now,double tolerance){
        for(var surface:surfaces){
            var box=surface.box();
            if(retained(now,surface.tick()) && Math.abs(box.maxY-feet.minY)<=tolerance &&
               feet.maxX>box.minX+1e-7 && feet.minX<box.maxX-1e-7 &&
               feet.maxZ>box.minZ+1e-7 && feet.minZ<box.maxZ-1e-7)return true;
        }
        return false;
    }
    static double recovery(Box feet,List<Surface> surfaces,long now,double stepHeight){
        double target=feet.minY;
        for(var surface:surfaces){
            var box=surface.box();
            double rise=box.maxY-feet.minY;
            if(retained(now,surface.tick()) && rise>1e-5 && rise<=stepHeight+1e-5 &&
               feet.maxX>box.minX+1e-7 && feet.minX<box.maxX-1e-7 &&
               feet.maxZ>box.minZ+1e-7 && feet.minZ<box.maxZ-1e-7)target=Math.max(target,box.maxY);
        }
        return target-feet.minY;
    }
    static BlockHitResult raycast(List<Surface> surfaces,long now,Vec3d start,Vec3d end){
        double dy=end.y-start.y;
        if(dy>=-1e-7)return null;
        double nearest=Double.POSITIVE_INFINITY;
        Vec3d hit=null;
        for(var surface:surfaces){
            if(!retained(now,surface.tick()))continue;
            var box=surface.box();double t=(box.maxY-start.y)/dy;
            if(t<0 || t>1 || t>=nearest)continue;
            double x=start.x+(end.x-start.x)*t,z=start.z+(end.z-start.z)*t;
            if(x<box.minX || x>=box.maxX || z<box.minZ || z>=box.maxZ)continue;
            // Air is replaceable: place in the first whole MC cell above the native
            // surface, and keep the hit inside that cell for server validation.
            double y=Math.ceil(box.maxY-1e-5);
            nearest=t;hit=new Vec3d(x,y,z);
        }
        return hit==null?null:new BlockHitResult(hit,Direction.UP,BlockPos.ofFloored(hit),false);
    }
    /** Exact sampled top contact for projectiles, without placement-cell rounding. */
    static BlockHitResult projectileRaycast(List<Surface> surfaces,long now,Vec3d start,Vec3d end){
        double dy=end.y-start.y;if(dy>=-1e-7)return null;
        double nearest=Double.POSITIVE_INFINITY;Vec3d hit=null;
        for(var s:surfaces){if(!retained(now,s.tick()))continue;var b=s.box();double t=(b.maxY-start.y)/dy;
            if(t<0 || t>1 || t>=nearest)continue;
            var p=start.add(end.subtract(start).multiply(t));
            if(p.x>=b.minX && p.x<b.maxX && p.z>=b.minZ && p.z<b.maxZ){nearest=t;hit=p;}}
        return hit==null?null:new BlockHitResult(hit,Direction.UP,BlockPos.ofFloored(hit),false);
    }
}
