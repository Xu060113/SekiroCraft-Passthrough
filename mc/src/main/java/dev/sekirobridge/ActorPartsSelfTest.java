package dev.sekirobridge;
import net.minecraft.util.math.*;
import java.util.*;

public final class ActorPartsSelfTest {
    private static int checks;
    private static void check(boolean ok,String reason){++checks;if(!ok)throw new AssertionError(reason);}
    public static void main(String[] args){
        var packet=Protocol.direct(ActorPartsProtocol.BYTES);
        packet.putLong(0,1).putLong(8,1000).putLong(16,8).putInt(24,1);
        packet.putLong(32,12).putLong(40,3).putInt(48,2).putInt(52,140).putInt(56,1);
        float[] coordinates={-.5f,0,-.5f,.5f,2,.5f,79,7,-1,81,9,1};
        for(int i=0;i<coordinates.length;++i)packet.putFloat(64+i*4,coordinates[i]);
        var state=ActorPartsProtocol.decode(packet);check(state!=null,"native bone-parts ABI decoded");
        var body=state.forActor(12,3,8,1100);check(body.parts().size()==2 && body.bones()==140,"bone and volume counts preserved");
        check(state.forActor(12,4,8,1100)==null,"old Boss phase discarded");
        check(state.forActor(12,3,9,1100)==null,"old scene discarded");
        check(state.forActor(12,3,8,1251)==null && state.forActor(12,3,8,999)==null,"stale or future parts discarded");
        var parts=List.of(new Box(-.5,0,-.5,.5,2,.5),new Box(79,7,-1,81,9,1));
        var boss=(NativeActorBounds)NativeActorBounds.of(parts);
        check(boss.maxX==81 && boss.parts().size()==2,"one actor discovers its entire animated model");
        var from=new Vec3d(80,8,-5);var end=new Vec3d(80,8,5);
        check(boss.raycast(from,end).isPresent(),"arrow and gun ray hits a limb eighty metres from root");
        check(boss.expand(.3).raycast(from,end).isPresent(),"vanilla projectile targeting margin preserves multipart rays");
        var gap=new Vec3d(40,8,0);
        check(!boss.contains(gap) && boss.raycast(new Vec3d(40,8,-5),new Vec3d(40,8,5)).isEmpty(),"empty union centre is neither inside nor a hit");
        check(!boss.expand(.3).raycast(new Vec3d(40,8,-5),new Vec3d(40,8,5)).isPresent(),"weapon expansion cannot fill distant limb gaps");
        check(boss.intersects(new Box(79,7,-2,82,9,2)),"area slash includes limb");
        check(!boss.intersects(new Box(39,7,-1,41,9,1)),"area slash in empty union gap remains a miss");
        check(!boss.intersects(39,7,-1,41,9,1),"coordinate-based overlap uses parts");
        check(boss.squaredMagnitude(new Vec3d(82,8,0))==1,"server reach uses distance to body, not root");
        check(boss.squaredMagnitude(gap)>1000,"server reach cannot attack empty middle of model");
        check(boss.nearestPoint(new Vec3d(82,8,0)).equals(new Vec3d(81,8,0)),"native damage impact lies on a real body part");
        var moved=boss.offset(10,-4,20);
        check(moved.contains(90,4,20) && !moved.contains(80,8,0),"movement replaces each old limb location");
        check(moved.raycast(new Vec3d(50,4,10),new Vec3d(50,4,30)).isEmpty(),"offset preserves holes");
        check(boss.stretch(new Vec3d(1,0,0)) instanceof NativeActorBounds,"swept projectile queries retain part geometry");
        check(boss.contract(.1) instanceof NativeActorBounds && boss.shrink(.1,0,0) instanceof NativeActorBounds,"shrunk weapon queries retain parts");
        var clipped=boss.intersection(new Box(78,6,-2,82,10,2));
        check(clipped.contains(80,8,0) && !clipped.contains(0,1,0),"intersection cannot retain unrelated torso");
        var nearer=(NativeActorBounds)NativeActorBounds.of(List.of(new Box(-1,0,2,1,2,3),new Box(-1,0,-3,1,2,-2)));
        check(nearer.raycast(new Vec3d(0,1,-5),new Vec3d(0,1,5)).orElseThrow().z==-3,"ray selects nearest part independent of bone order");
        var nativePart=body.parts().get(1);float scale=2;
        var anchor=NativeActorGeometry.anchor(body,new Vec3d(0,0,0),new Vec3d(82,8,0),1);
        check(anchor.equals(new Vec3d(81,8,0)),"giant tracking anchor moves to visible limb, not remote native root");
        var anchored=NativeActorBounds.of(parts.stream().map(p->p.offset(anchor.multiply(-1))).toList()).offset(anchor);
        check(anchored.raycast(from,end).equals(boss.raycast(from,end)) && !anchored.contains(gap),"reanchoring preserves complete world geometry and holes");
        check(NativeActorGeometry.anchor(body,Vec3d.ZERO,new Vec3d(2,1,0),1).equals(Vec3d.ZERO),"nearby actor retains native root");
        check(NativeActorGeometry.anchor(null,Vec3d.ZERO,new Vec3d(82,8,0),1).equals(Vec3d.ZERO),"fallback capsule does not fabricate a body anchor");
        var mirrored=new Box(nativePart.minX()*scale,nativePart.minY()*scale,-nativePart.maxZ()*scale,nativePart.maxX()*scale,nativePart.maxY()*scale,-nativePart.minZ()*scale);
        check(mirrored.minX==158 && mirrored.maxX==162 && mirrored.minZ==-2 && mirrored.maxZ==2,"native dimensions scale and reverse Z correctly");
        packet.putFloat(64,Float.NaN);check(ActorPartsProtocol.decode(packet)==null,"invalid used bone cannot create a partial body");packet.putFloat(64,-.5f);
        packet.putInt(48,129);check(ActorPartsProtocol.decode(packet)==null,"part count bounded");packet.putInt(48,2);
        packet.putInt(52,1025);check(ActorPartsProtocol.decode(packet)==null,"bone count bounded");packet.putInt(52,140);
        packet.putInt(56,3);check(ActorPartsProtocol.decode(packet)==null,"unrecognized geometry source rejected");packet.putInt(56,1);
        packet.putInt(24,2);for(int i=0;i<ActorPartsProtocol.ENTRY_BYTES;++i)packet.put(32+ActorPartsProtocol.ENTRY_BYTES+i,packet.get(32+i));
        check(ActorPartsProtocol.decode(packet)==null,"multiple volumes cannot masquerade as duplicate Boss actors");
        check(ActorPartsProtocol.decode(Protocol.direct(ActorPartsProtocol.BYTES-1))==null,"short multipart packet rejected");
        System.out.println(checks+" MC animated body parts, gaps, projectile, area attack, reach and protocol checks passed");
    }
}
