package dev.sekirobridge;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Per-entity floor observations. Different elevations never share a player's height map. */
final class EntityTerrainGeometry {
    static final int MAX_CELLS=256;
    static final long REFRESH_MS=500;
    record Probe(TerrainGeometry.Key key,Vec3d start,Vec3d end,double size){}
    record Observation(Probe probe,long tick,Double height){}
    private final Map<TerrainGeometry.Key,Observation> observations=new HashMap<>();

    static List<TerrainGeometry.Key> footprint(Box box,double size){
        if(!Double.isFinite(size) || size<=0 || !ProjectileTerrain.finite(new Vec3d(box.minX,box.minY,box.minZ)) ||
            !ProjectileTerrain.finite(new Vec3d(box.maxX,box.maxY,box.maxZ)))return List.of();
        long x0=(long)Math.floor((box.minX+1e-7)/size),x1=(long)Math.floor((box.maxX-1e-7)/size);
        long z0=(long)Math.floor((box.minZ+1e-7)/size),z1=(long)Math.floor((box.maxZ-1e-7)/size);
        if(x1<x0 || z1<z0 || x1-x0>=MAX_CELLS || z1-z0>=MAX_CELLS ||
            (x1-x0+1)*(z1-z0+1)>MAX_CELLS)return List.of();
        var keys=new ArrayList<TerrainGeometry.Key>();
        for(long z=z0;z<=z1;++z)for(long x=x0;x<=x1;++x)keys.add(new TerrainGeometry.Key(x,-z-1));
        return List.copyOf(keys);
    }
    static Probe probe(TerrainGeometry.Key key,double feetY,double size,double stepHeight){
        double scale=size*2;
        double up=Math.min(3*scale,Math.max(1.25*scale,stepHeight+.05));
        var start=new Vec3d((key.x()+.5)*size,feetY+up,(-key.z()-.5)*size);
        return new Probe(key,start,new Vec3d(start.x,feetY-12*scale,start.z),size);
    }
    static boolean relevant(Probe probe,double feetY,double stepHeight){
        return feetY<=probe.start().y-.05 && feetY>=probe.end().y+Math.max(.05,stepHeight);
    }
    boolean observe(Probe probe,ProjectileTerrain.Contact answer,long now){
        if(answer==null || !TerrainGeometry.retained(now,answer.tick()))return false;
        Double height=null;
        if(answer.hit()){
            // A wall hit is not proof of empty space beneath it. Retry the vertical segment.
            if(answer.normal().y<=.25 || !ProjectileTerrain.validHit(probe.start(),probe.end(),
                answer.position(),answer.normal(),probe.size()*2))return false;
            height=answer.position().y;
        }
        observations.put(probe.key(),new Observation(probe,answer.tick(),height));
        return true;
    }
    boolean known(TerrainGeometry.Key key,double feetY,double stepHeight,long now){
        var o=observations.get(key);
        return o!=null && TerrainGeometry.retained(now,o.tick()) && relevant(o.probe(),feetY,stepHeight) &&
            (o.height()==null || o.height()<=feetY+Math.max(.6,stepHeight)+1e-5);
    }
    boolean refresh(TerrainGeometry.Key key,double feetY,double stepHeight,long now){
        var o=observations.get(key);
        return !known(key,feetY,stepHeight,now) || now-o.tick()>=REFRESH_MS;
    }
    boolean covers(Box swept,double feetY,double stepHeight,double size,long now){
        var keys=footprint(swept,size);
        if(keys.isEmpty())return false;
        for(var key:keys)if(!known(key,feetY,stepHeight,now))return false;
        return true;
    }
    List<TerrainGeometry.Surface> surfaces(double feetY,double stepHeight,long now){
        var result=new ArrayList<TerrainGeometry.Surface>();
        for(var entry:observations.entrySet()){
            var o=entry.getValue();
            if(o.height()==null || !known(entry.getKey(),feetY,stepHeight,now))continue;
            double size=o.probe().size();var key=entry.getKey();double height=o.height();
            result.add(new TerrainGeometry.Surface(o.tick(),new Box(key.x()*size,height-8*size,
                (-key.z()-1)*size,(key.x()+1)*size,height,-key.z()*size)));
        }
        return List.copyOf(result);
    }
    void prune(Box box,double size,long now){
        observations.entrySet().removeIf(e -> !TerrainGeometry.retained(now,e.getValue().tick()) ||
            Math.abs((e.getKey().x()+.5)*size-box.getCenter().x)>box.maxX-box.minX+4*size ||
            Math.abs((-e.getKey().z()-.5)*size-box.getCenter().z)>box.maxZ-box.minZ+4*size);
    }
}
