package dev.sekirobridge;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/** Bounded asynchronous native segments; JNI transport remains on the client thread. */
public final class ProjectileTerrain {
    public record Ticket(long id,long epoch,long created,Vec3d start,Vec3d end){}
    private record Result(boolean hit,Vec3d position,Vec3d normal){}
    private static final ConcurrentHashMap<Long,Ticket> pending=new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long,Result> completed=new ConcurrentHashMap<>();
    private static final AtomicLong ids=new AtomicLong();
    private static long sequence;
    private static final java.nio.ByteBuffer queries=Protocol.direct(2080),hits=Protocol.direct(2592);
    private ProjectileTerrain(){}
    public static boolean active(Entity entity){return entity!=null && NativeTerrain.worldActive(entity.getWorld());}
    public static Ticket request(Entity entity,Vec3d start,Vec3d end){
        var state=BridgeClient.state();
        if(!active(entity) || state==null || !finite(start) || !finite(end) || start.squaredDistanceTo(end)>256*state.scale()*state.scale())return null;
        var ticket=new Ticket(ids.incrementAndGet(),state.epoch(),NativeBridge.clockMs(),start,end);
        pending.put(ticket.id(),ticket);return ticket;
    }
    static boolean finite(Vec3d p){return Double.isFinite(p.x) && Double.isFinite(p.y) && Double.isFinite(p.z);}
    public static boolean matches(Ticket t,Vec3d start,Vec3d end){
        var state=BridgeClient.state();return t!=null && state!=null && t.epoch()==state.epoch() &&
            t.start().squaredDistanceTo(start)<1e-8 && t.end().squaredDistanceTo(end)<1e-8;
    }
    public static boolean waiting(Ticket t){return t!=null && !completed.containsKey(t.id()) && NativeBridge.clockMs()-t.created()<350;}
    public static BlockHitResult result(Ticket t){
        if(t==null)return null;var r=completed.get(t.id());
        return r==null || !r.hit()?null:new BlockHitResult(r.position(),Direction.getFacing(r.normal().x,r.normal().y,r.normal().z),
            BlockPos.ofFloored(r.position()),false);
    }
    public static void release(Ticket t){if(t!=null){pending.remove(t.id());completed.remove(t.id());}}
    public static BlockHitResult nearest(Vec3d start,BlockHitResult original,BlockHitResult nativeHit){
        return nativeHit!=null && (original.getType()==HitResult.Type.MISS ||
            start.squaredDistanceTo(nativeHit.getPos())<start.squaredDistanceTo(original.getPos()))?nativeHit:original;
    }
    static void poll(){
        if(!BridgeClient.connected()){pending.clear();completed.clear();return;}
        var state=BridgeClient.state();long now=NativeBridge.clockMs();
        if(NativeBridge.projectileHits(BridgeClient.handle(),hits) && hits.getLong(16)==state.epoch() && Protocol.fresh(now,hits.getLong(8))){
            int count=hits.getInt(24);
            if(count>=0 && count<=64)for(int i=0;i<count;++i){int at=32+i*40;long id=hits.getLong(at);var ticket=pending.get(id);
                if(ticket==null || ticket.epoch()!=state.epoch())continue;
                boolean found=hits.getInt(at+32)==1;
                var position=new Vec3d(state.mcX(hits.getFloat(at+8)),state.mcY(hits.getFloat(at+12)),state.mcZ(hits.getFloat(at+16)));
                var normal=new Vec3d(hits.getFloat(at+20),hits.getFloat(at+24),-hits.getFloat(at+28));
                if(found && !validHit(ticket.start(),ticket.end(),position,normal,state.scale()))continue;
                completed.putIfAbsent(id,new Result(found,position,normal));
            }
        }
        pending.forEach((id,t) -> {if(t.epoch()!=state.epoch() || now-t.created()>1500){pending.remove(id);completed.remove(id);}});
        completed.keySet().removeIf(id -> !pending.containsKey(id));
        var batch=pending.values().stream().filter(t -> !completed.containsKey(t.id())).sorted(java.util.Comparator.comparingLong(Ticket::id)).limit(64).toList();
        if(batch.isEmpty())return;
        queries.clear();queries.putLong(++sequence).putLong(now).putLong(state.epoch()).putInt(batch.size()).putInt(0);
        for(var t:batch){var a=t.start();var d=t.end().subtract(a);
            queries.putLong(t.id()).putFloat((float)(a.x/state.scale())).putFloat((float)((a.y-state.yOffset())/state.scale())).putFloat((float)(-a.z/state.scale()))
                .putFloat((float)(d.x/state.scale())).putFloat((float)(d.y/state.scale())).putFloat((float)(-d.z/state.scale()));}
        NativeBridge.projectileRays(BridgeClient.handle(),queries);
    }
    static boolean validHit(Vec3d start,Vec3d end,Vec3d hit,Vec3d normal,double scale){
        if(!finite(hit) || !finite(normal) || normal.lengthSquared()<.25 || normal.lengthSquared()>2.25)return false;
        var d=end.subtract(start);double length=d.lengthSquared();if(length<1e-12)return false;
        double t=hit.subtract(start).dotProduct(d)/length;
        return t>=-.001 && t<=1.001 && start.add(d.multiply(t)).squaredDistanceTo(hit)<.0025*scale*scale;
    }
}
