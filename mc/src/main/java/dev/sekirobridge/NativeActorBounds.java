package dev.sekirobridge;
import net.minecraft.util.math.*;
import java.util.*;
import java.util.function.Function;

/** One entity with multiple damage volumes. The outer box only discovers the
 * entity; rays, overlaps and distances use actual parts, including after the
 * expand/offset/stretch operations used by Minecraft and weapon mods. */
public final class NativeActorBounds extends Box {
    private final List<Box> parts;
    private NativeActorBounds(Box outer,List<Box> parts){super(outer.minX,outer.minY,outer.minZ,outer.maxX,outer.maxY,outer.maxZ);this.parts=List.copyOf(parts);}
    public List<Box> parts(){return parts;}
    public static Box of(List<Box> parts){
        if(parts.isEmpty())return new Box(0,0,0,0,0,0);
        Box outer=parts.get(0);for(int i=1;i<parts.size();++i)outer=outer.union(parts.get(i));
        return new NativeActorBounds(outer,parts);
    }
    private Box map(Function<Box,Box> f){return of(parts.stream().map(f).toList());}
    @Override public Optional<Vec3d> raycast(Vec3d start,Vec3d end){
        Vec3d nearest=null;double distance=Double.POSITIVE_INFINITY;
        for(var part:parts){var hit=part.raycast(start,end);
            if(hit.isPresent()){double d=start.squaredDistanceTo(hit.get());if(d<distance){nearest=hit.get();distance=d;}}}
        return Optional.ofNullable(nearest);
    }
    @Override public boolean contains(Vec3d p){return contains(p.x,p.y,p.z);}
    @Override public boolean contains(double x,double y,double z){for(var part:parts)if(part.contains(x,y,z))return true;return false;}
    @Override public boolean intersects(Box other){
        if(other instanceof NativeActorBounds multi){for(var p:parts)for(var q:multi.parts)if(p.intersects(q))return true;return false;}
        return intersects(other.minX,other.minY,other.minZ,other.maxX,other.maxY,other.maxZ);
    }
    @Override public boolean intersects(double a,double b,double c,double d,double e,double f){
        for(var part:parts)if(part.intersects(a,b,c,d,e,f))return true;return false;
    }
    @Override public boolean intersects(Vec3d a,Vec3d b){return intersects(Math.min(a.x,b.x),Math.min(a.y,b.y),Math.min(a.z,b.z),Math.max(a.x,b.x),Math.max(a.y,b.y),Math.max(a.z,b.z));}
    @Override public double squaredMagnitude(Vec3d p){double result=Double.POSITIVE_INFINITY;for(var part:parts)result=Math.min(result,part.squaredMagnitude(p));return result;}
    public Vec3d nearestPoint(Vec3d from){
        Vec3d result=parts.get(0).getCenter();double distance=Double.POSITIVE_INFINITY;
        for(var part:parts){var point=new Vec3d(MathHelper.clamp(from.x,part.minX,part.maxX),MathHelper.clamp(from.y,part.minY,part.maxY),MathHelper.clamp(from.z,part.minZ,part.maxZ));
            double d=from.squaredDistanceTo(point);if(d<distance){distance=d;result=point;}}
        return result;
    }
    @Override public Box expand(double x,double y,double z){return map(p->p.expand(x,y,z));}
    @Override public Box expand(double amount){return expand(amount,amount,amount);}
    @Override public Box contract(double x,double y,double z){return expand(-x,-y,-z);}
    @Override public Box contract(double amount){return contract(amount,amount,amount);}
    @Override public Box offset(double x,double y,double z){return map(p->p.offset(x,y,z));}
    @Override public Box offset(Vec3d delta){return offset(delta.x,delta.y,delta.z);}
    @Override public Box offset(BlockPos delta){return offset(delta.getX(),delta.getY(),delta.getZ());}
    @Override public Box stretch(double x,double y,double z){return map(p->p.stretch(x,y,z));}
    @Override public Box stretch(Vec3d delta){return stretch(delta.x,delta.y,delta.z);}
    @Override public Box shrink(double x,double y,double z){return map(p->p.shrink(x,y,z));}
    @Override public Box union(Box other){var all=new ArrayList<>(parts);if(other instanceof NativeActorBounds multi)all.addAll(multi.parts);else all.add(other);return of(all);}
    @Override public Box intersection(Box other){
        var clipped=new ArrayList<Box>();var others=other instanceof NativeActorBounds multi?multi.parts:List.of(other);
        for(var p:parts)for(var q:others)if(p.intersects(q))clipped.add(p.intersection(q));return of(clipped);
    }
}
