package dev.sekirobridge;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/** Optional, separately versioned extension to combat-state-v3. Native metres. */
final class ActorShapeProtocol {
    static final int CAPABILITY=4096,BYTES=2080,SLOTS=64,ENTRY_BYTES=32;
    record Shape(long id,long stage,float width,float height,float yOffset,int source) {
        boolean valid(){return id!=0 && stage!=0 && dimensionsValid(width,height,yOffset) && source>=0 && source<=1;}
    }
    record State(long sequence,long tick,long epoch,Map<Long,Shape> shapes) {
        Shape forActor(long actorId,long actorStage,long actorEpoch,long now){
            if(epoch!=actorEpoch || !fresh(now,tick))return null;
            var shape=shapes.get(actorId);return shape!=null && shape.stage()==actorStage?shape:null;
        }
    }
    static boolean fresh(long now,long tick){return tick>0 && now>=tick && now-tick<=250;}
    static boolean dimensionsValid(float width,float height,float offset){
        return Float.isFinite(width) && width>=.04f && width<=64 &&
            Float.isFinite(height) && height>=.04f && height<=64 &&
            Float.isFinite(offset) && Math.abs(offset)<=32;
    }
    static State decode(ByteBuffer p){
        if(p==null || p.capacity()<BYTES)return null;
        int count=p.getInt(24);
        if(p.getLong(0)<=0 || p.getLong(8)<=0 || p.getLong(16)==0 || count<0 || count>SLOTS || p.getInt(28)!=0)return null;
        var shapes=new HashMap<Long,Shape>();
        for(int i=0;i<count;++i){int o=32+i*ENTRY_BYTES;
            var s=new Shape(p.getLong(o),p.getLong(o+8),p.getFloat(o+16),p.getFloat(o+20),p.getFloat(o+24),p.getInt(o+28));
            if(!s.valid() || shapes.put(s.id(),s)!=null)return null;
        }
        return new State(p.getLong(0),p.getLong(8),p.getLong(16),Map.copyOf(shapes));
    }
}
