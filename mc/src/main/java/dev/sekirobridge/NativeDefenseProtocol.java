package dev.sekirobridge;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
/** Raw native hits, not HP losses: vanilla owns shielding, armor and cooldowns. */
final class NativeDefenseProtocol {
    static final int BYTES=2088,ACK_BYTES=40,SLOTS=64;
    record Hit(long sequence,float ratio,boolean sourceKnown,float x,float y,float z){}
    record State(long tick,long epoch,long hero,long session,long produced,List<Hit> hits){}
    static State decode(ByteBuffer p){
        if(p.capacity()<BYTES || p.getLong(0)<=0 || p.getLong(8)==0 || p.getLong(16)==0 || p.getLong(24)==0 || p.getLong(32)<0)return null;
        long produced=p.getLong(32);var hits=new ArrayList<Hit>();
        for(long i=Math.max(0,produced-SLOTS);i<produced;++i){int o=40+(int)(i%SLOTS)*32;
            float ratio=p.getFloat(o+8),x=p.getFloat(o+16),y=p.getFloat(o+20),z=p.getFloat(o+24);int flags=p.getInt(o+12);
            if(p.getLong(o)!=i+1 || !Float.isFinite(ratio) || ratio<=0 || ratio>10000 || (flags&~1)!=0 || p.getInt(o+28)!=0 ||
               !Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z) || (double)x*x+(double)y*y+(double)z*z>150000.0*150000)return null;
            hits.add(new Hit(i+1,ratio,(flags&1)!=0,x,y,z));
        }
        return new State(p.getLong(0),p.getLong(8),p.getLong(16),p.getLong(24),produced,List.copyOf(hits));
    }
}
