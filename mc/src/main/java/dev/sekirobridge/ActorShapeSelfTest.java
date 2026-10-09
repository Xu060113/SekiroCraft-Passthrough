package dev.sekirobridge;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Box;

public final class ActorShapeSelfTest {
    private static int checks;
    private static void check(boolean ok,String reason){++checks;if(!ok)throw new AssertionError(reason);}
    public static void main(String[] args){
        var packet=Protocol.direct(ActorShapeProtocol.BYTES);
        packet.putLong(0,8).putLong(8,1000).putLong(16,7).putInt(24,1);
        packet.putLong(32,19).putLong(40,2).putFloat(48,3).putFloat(52,5.4f).putFloat(56,.75f).putInt(60,1);
        var state=ActorShapeProtocol.decode(packet);check(state!=null,"C++ shape packet layout decoded");
        var shape=state.forActor(19,2,7,1100);check(shape!=null && shape.width()==3 && shape.height()==5.4f && shape.yOffset()==.75f,"large Boss dimensions retained");
        check(state.forActor(19,3,7,1100)==null,"previous Boss phase shape cannot apply to new stage");
        check(state.forActor(19,2,8,1100)==null,"previous scene shape cannot apply");
        check(state.forActor(20,2,7,1100)==null,"foreign entity dimensions cannot apply");
        check(state.forActor(19,2,7,1251)==null && state.forActor(19,2,7,999)==null,"stale/future geometry discarded");
        // These are Minecraft's actual Box ray and intersection implementations,
        // using the same bounds function as client AND integrated-server proxies.
        var boss=NativeActorGeometry.bounds(10,128,-20,shape.width(),shape.height(),shape.yOffset());
        var human=NativeActorGeometry.bounds(10,128,-20,.6f,1.8f,0);
        var from=new Vec3d(10,131,-26);var end=new Vec3d(10,131,-20);
        check(boss.raycast(from,end).isPresent() && human.raycast(from,end).isEmpty(),"arrow/gun ray reaches high Boss torso outside old human box");
        var slash=new Box(8.6,130,-22,9,131,-21);
        check(boss.intersects(slash) && !human.intersects(slash),"area slash overlaps large Boss body edge");
        check(boss.raycast(new Vec3d(11.4,130,-26),new Vec3d(11.4,130,-20)).isPresent(),"wide Boss flank is targetable");
        check(boss.raycast(new Vec3d(12,130,-26),new Vec3d(12,130,-20)).isEmpty(),"ray outside actual body remains a miss");
        check(boss.minY==128.75 && Math.abs(boss.maxY-134.15)<.0001,"native vertical offset aligns body to visible model");
        var moved=NativeActorGeometry.bounds(17,128,-15,3,5.4f,.75f);
        check(moved.contains(new Vec3d(17,131,-15)) && !moved.contains(new Vec3d(10,131,-20)),"root motion refresh replaces old hit volume");
        var scaled=NativeActorGeometry.bounds(20,128,-40,6,10.8f,1.5f);
        check(scaled.minX==17 && scaled.maxX==23 && scaled.minY==129.5,"bridge scale applies to width/height/offset together");
        packet.putFloat(48,Float.NaN);check(ActorShapeProtocol.decode(packet)==null,"invalid dimension rejected");packet.putFloat(48,3);
        packet.putInt(24,2);for(int i=0;i<32;++i)packet.put(64+i,packet.get(32+i));
        check(ActorShapeProtocol.decode(packet)==null,"duplicate native identity rejected");packet.putInt(24,1);
        packet.putInt(60,2);check(ActorShapeProtocol.decode(packet)==null,"unrecognized bounds source rejected");packet.putInt(60,1);
        packet.putFloat(56,33);check(ActorShapeProtocol.decode(packet)==null,"offset bounded");packet.putFloat(56,.75f);
        packet.putInt(24,65);check(ActorShapeProtocol.decode(packet)==null,"packet overflow rejected");
        check(ActorShapeProtocol.decode(Protocol.direct(2079))==null,"short packet rejected");
        System.out.println(checks+" MC native body protocol, projectile ray and area-attack geometry checks passed");
    }
}
