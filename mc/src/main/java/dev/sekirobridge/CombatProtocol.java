package dev.sekirobridge;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
final class CombatProtocol {
    static final int STATE_BYTES=2640,REPORT_BYTES=1600,SLOTS=64;
    record Actor(long id,float x,float y,float z,int hp,int maxHp,int team,int flags){}
    record State(long sequence,long tick,long epoch,long hero,long ackCommand,int hp,int maxHp,int flags,
        double ackDamage,double ackHeal,long ackSession,List<Actor> actors){}
    static State decode(ByteBuffer p){
        int count=p.getInt(52),hp=p.getInt(40),max=p.getInt(44),flags=p.getInt(48);
        if(p.getLong(0)<=0 || p.getLong(16)==0 || p.getLong(24)==0 || count<0 || count>SLOTS ||
           (flags&~3)!=0 || max<=0 || max>10000000 || hp<0 || hp>max)return null;
        double damage=p.getDouble(56),heal=p.getDouble(64);
        if(!Double.isFinite(damage) || !Double.isFinite(heal) || damage<0 || heal<0)return null;
        var actors=new ArrayList<Actor>();
        for(int i=0;i<count;++i){int o=80+i*40;long id=p.getLong(o);float x=p.getFloat(o+8),y=p.getFloat(o+12),z=p.getFloat(o+16);
            int ah=p.getInt(o+20),am=p.getInt(o+24),af=p.getInt(o+32);
            if(id==0 || !Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z) ||
               am<=0 || am>10000000 || ah<0 || ah>am || (af&~3)!=0)return null;
            actors.add(new Actor(id,x,y,z,ah,am,p.getInt(o+28),af));}
        return new State(p.getLong(0),p.getLong(8),p.getLong(16),p.getLong(24),p.getLong(32),hp,max,flags,
            damage,heal,p.getLong(72),List.copyOf(actors));
    }
}
