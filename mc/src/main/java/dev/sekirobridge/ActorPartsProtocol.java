package dev.sekirobridge;
import java.nio.ByteBuffer;
import java.util.*;

/** Bone bounds in native metres relative to the actor's physics root. */
final class ActorPartsProtocol {
    static final int CAPABILITY=8192,BYTES=198688,ENTRY_BYTES=3104,PARTS=128;
    record Part(float minX,float minY,float minZ,float maxX,float maxY,float maxZ) {
        boolean valid(){return finite(minX)&&finite(minY)&&finite(minZ)&&finite(maxX)&&finite(maxY)&&finite(maxZ)&&
            minX<maxX && minY<maxY && minZ<maxZ;}
        private static boolean finite(float value){return Float.isFinite(value)&&Math.abs(value)<=4096;}
    }
    record Body(long id,long stage,int bones,int source,List<Part> parts) {}
    record State(long sequence,long tick,long epoch,Map<Long,Body> actors) {
        Body forActor(long id,long stage,long epoch,long now){
            if(this.epoch!=epoch || !ActorShapeProtocol.fresh(now,tick))return null;
            var body=actors.get(id);return body!=null && body.stage==stage?body:null;
        }
    }
    static State decode(ByteBuffer packet){
        if(packet==null || packet.capacity()<BYTES)return null;
        int count=packet.getInt(24);
        if(packet.getLong(0)<=0 || packet.getLong(8)<=0 || packet.getLong(16)==0 || count<0 || count>64 || packet.getInt(28)!=0)return null;
        var actors=new HashMap<Long,Body>();
        for(int i=0;i<count;++i){int at=32+i*ENTRY_BYTES;
            long id=packet.getLong(at),stage=packet.getLong(at+8);
            int parts=packet.getInt(at+16),bones=packet.getInt(at+20),source=packet.getInt(at+24);
            if(id==0 || stage==0 || parts<1 || parts>PARTS || bones<parts || bones>1024 || source<1 || source>2 || packet.getInt(at+28)!=0)return null;
            var boxes=new ArrayList<Part>(parts);
            for(int j=0;j<parts;++j){int o=at+32+j*24;
                var p=new Part(packet.getFloat(o),packet.getFloat(o+4),packet.getFloat(o+8),packet.getFloat(o+12),packet.getFloat(o+16),packet.getFloat(o+20));
                if(!p.valid())return null;boxes.add(p);
            }
            if(actors.put(id,new Body(id,stage,bones,source,List.copyOf(boxes)))!=null)return null;
        }
        return new State(packet.getLong(0),packet.getLong(8),packet.getLong(16),Map.copyOf(actors));
    }
}
