package dev.sekirobridge;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Shares authoritative mob floors with the render world; only existing client JNI queues are used. */
final class EntityTerrain {
    static final double RADIUS=48;
    private static final int MAX_ENTITIES=256,MAX_PENDING=48,RAYS_PER_WINDOW=32;
    private static final Map<UUID,Floor> floors=new HashMap<>();
    private static long epoch,budgetWindow;
    private static int issued;
    private record Pending(EntityTerrainGeometry.Probe probe,ProjectileTerrain.Ticket ticket){}
    private static final class Floor {
        final EntityTerrainGeometry geometry=new EntityTerrainGeometry();
        final Map<TerrainGeometry.Key,Pending> pending=new LinkedHashMap<>();
        long touched;
    }
    record Support(boolean known,List<TerrainGeometry.Surface> surfaces){}
    private EntityTerrain(){}
    static synchronized void clear(){
        floors.values().forEach(EntityTerrain::release);floors.clear();epoch=budgetWindow=0;issued=0;
    }
    private static void release(Floor floor){floor.pending.values().forEach(p -> ProjectileTerrain.release(p.ticket()));floor.pending.clear();}
    static boolean near(Entity entity,net.minecraft.util.math.Vec3d player,double scale){
        return entity.getPos().squaredDistanceTo(player)<=RADIUS*RADIUS*scale*scale;
    }
    static synchronized Support read(Entity entity,Box swept,double size,long now){
        var floor=floors.get(entity.getUuid());
        return floor==null?new Support(false,List.of()):new Support(
            floor.geometry.covers(swept,entity.getY(),entity.getStepHeight(),size,now),
            floor.geometry.surfaces(entity.getY(),entity.getStepHeight(),now));
    }
    static synchronized Support update(Entity entity,Box swept,double size,long nextEpoch,long now,boolean authoritative){
        if(epoch!=nextEpoch){clear();epoch=nextEpoch;}
        if(!authoritative)return read(entity,swept,size,now);
        floors.entrySet().removeIf(entry -> {
            if(now-entry.getValue().touched<=10000)return false;
            release(entry.getValue());return true;
        });
        var floor=floors.get(entity.getUuid());
        if(floor==null){
            if(floors.size()>=MAX_ENTITIES)return new Support(false,List.of());
            floor=new Floor();floors.put(entity.getUuid(),floor);
        }
        floor.touched=now;floor.geometry.prune(swept,size,now);
        var iterator=floor.pending.values().iterator();
        while(iterator.hasNext()){
            var p=iterator.next();var answer=ProjectileTerrain.contact(p.ticket());
            if(answer!=null)floor.geometry.observe(p.probe(),answer,now);
            if(answer!=null || now-p.ticket().created()>=350 ||
                !EntityTerrainGeometry.relevant(p.probe(),entity.getY(),entity.getStepHeight())){
                ProjectileTerrain.release(p.ticket());iterator.remove();
            }
        }
        if(now-budgetWindow>=50 || now<budgetWindow){budgetWindow=now;issued=0;}
        int outstanding=floors.values().stream().mapToInt(f -> f.pending.size()).sum(),perEntity=0;
        var activeFloor=floor;
        var keys=EntityTerrainGeometry.footprint(swept,size).stream().sorted(java.util.Comparator.comparing(
            key -> activeFloor.geometry.known(key,entity.getY(),entity.getStepHeight(),now))).toList();
        for(var key:keys){
            if(issued>=RAYS_PER_WINDOW || outstanding>=MAX_PENDING || perEntity>=16)break;
            if(floor.pending.containsKey(key) || !floor.geometry.refresh(key,entity.getY(),entity.getStepHeight(),now))continue;
            var probe=EntityTerrainGeometry.probe(key,entity.getY(),size,entity.getStepHeight());
            var ticket=ProjectileTerrain.request(entity,probe.start(),probe.end());
            if(ticket==null)continue;
            floor.pending.put(key,new Pending(probe,ticket));issued++;outstanding++;perEntity++;
        }
        return read(entity,swept,size,now);
    }
}
