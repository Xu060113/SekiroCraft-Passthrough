package dev.sekirobridge;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;
import java.nio.ByteBuffer;
import java.util.List;

/** Exercises real vanilla voxel collision and replayed sampled terrain without opening a game. */
public final class TerrainSelfTest {
    private static int checks;
    private static void require(boolean condition,String name){
        checks++;if(!condition)throw new AssertionError(name);
    }
    private static ByteBuffer sample(long sequence,long tick,float x,float z,float height){
        var packet=Protocol.direct(448);
        packet.putLong(0,sequence).putLong(8,tick).putLong(16,123);
        packet.putFloat(24,x).putFloat(28,height).putFloat(32,z).putFloat(36,.5f);
        packet.putFloat(40+40*4,height).put(364+40,(byte)1);
        return packet;
    }
    public static void run(){
        checks=0;
        require(net.minecraft.entity.DamageUtil.getDamageLeft(10,0,0)==10,"vanilla unarmored incoming damage remains unchanged");
        float diamond=net.minecraft.entity.DamageUtil.getDamageLeft(10,20,8);
        require(Math.abs(diamond-3f)<1e-6,"vanilla diamond armor and toughness reduce an incoming 10-point hit to 3");
        require(net.minecraft.entity.DamageUtil.getDamageLeft(40,20,8)<net.minecraft.entity.DamageUtil.getDamageLeft(40,20,0),"vanilla toughness protects against stronger incoming attacks");
        require(Math.abs(net.minecraft.entity.DamageUtil.getInflictedDamage(diamond,20)-.6f)<1e-6,"vanilla protection reduces the already armor-reduced injury");
        var state=new Protocol.State(1,1000,123,3,768,0,0,0,0,1.6f,0,0,0,1,
            1.1f,1.7f,.05f,1000,0,1,1280,720,new byte[32],0,0,0,0,0,0,new int[8],180);
        var grid=new TerrainGeometry();
        var feet=new Box(.05,10,-.45,.45,11.8,-.05);
        require(!TerrainGeometry.covers(grid.known(),.5,feet,1000),"startup waits without making a temporary floor");
        var packet=sample(1,1000,.25f,.25f,10);
        var cells=grid.merge(state,packet,1000);
        require(cells.size()==1,"single sampled cell");
        var ground=cells.get(0).box();
        require(ground.minX==0 && ground.maxX==.5 && ground.minZ==-.5 && ground.maxZ==0,
            "fixed world cell and coordinate handedness");
        require(TerrainGeometry.covers(grid.known(),.5,feet,1000),"first completed sample releases the covered player");
        require(TerrainGeometry.covers(grid.known(),.5,feet.stretch(.7,-.4,0),1000),
            "swept footprint can enter confirmed empty cells");
        require(!TerrainGeometry.covers(grid.known(),.5,feet.stretch(3,0,0),1000),
            "movement into an unobserved cell waits at the coverage boundary");
        require(TerrainGeometry.covers(grid.known(),.5,feet,1600),"short IPC pause keeps known coverage usable");
        require(!TerrainGeometry.covers(grid.known(),.5,feet,2601),"expired coverage waits instead of permitting a fall");
        require(TerrainGeometry.covers(grid.known(),1,new Box(.1,20,-4.9,.9,21.8,-4.1),1000),
            "scaled negative MC Z maps to positive host cell index");
        require(!TerrainGeometry.covers(grid.known(),1,new Box(.1,20,4.1,.9,21.8,4.9),1000),
            "scaled positive MC Z respects the asymmetric opposite edge of the sampled window");
        var shape=VoxelShapes.cuboid(ground);
        require(Math.abs(shape.calculateMaxDistance(Direction.Axis.Y,feet,-.08))<1e-7,
            "real vanilla collision stops feet above floor");
        var penetrating=feet.offset(0,-.12,0);
        require(shape.calculateMaxDistance(Direction.Axis.Y,penetrating,-.08)<-.079,
            "reproduce vanilla falling through a floor that appears around feet");
        double rise=TerrainGeometry.recovery(penetrating,cells,1050,.6);
        require(Math.abs(rise-.12)<1e-7,"shallow sampled-floor penetration recovers to top");
        require(Math.abs(shape.calculateMaxDistance(Direction.Axis.Y,penetrating.offset(0,rise,0),-.08))<1e-7,
            "recovered feet remain supported by vanilla collision");
        require(TerrainGeometry.recovery(feet.offset(0,-1,0),cells,1050,.6)==0,
            "recovery cannot pull a player through deep ground or ceilings");
        require(TerrainGeometry.recovery(penetrating.offset(2,0,0),cells,1050,.6)==0,
            "unknown cells do not create a floor across a cliff");
        require(TerrainGeometry.retained(1600,cells.get(0).tick()),"600ms missing IPC retains known static floor");
        require(!TerrainGeometry.retained(2601,cells.get(0).tick()),"retention remains bounded");
        var shifted=sample(2,1100,.75f,.25f,10);
        // The old center remains a hit at the new grid's x-1 cell.
        shifted.put(364+39,(byte)1).putFloat(40+39*4,10);
        var shiftedCells=grid.merge(state,shifted,1100);
        require(shiftedCells.stream().anyMatch(s -> s.box().equals(ground)),
            "moving sample window does not move existing collision cell boundaries");
        var miss=sample(3,1200,.75f,.25f,10);
        miss.put(364+40,(byte)0);
        require(grid.merge(state,miss,1200).isEmpty(),"fresh ray misses remove cached floor immediately");
        require(TerrainGeometry.covers(grid.known(),.5,feet.stretch(0,-3,0),1250),
            "known empty terrain releases falling instead of creating an invisible floor");
        grid.merge(state,sample(4,2800,.25f,.25f,10),2800);
        require(TerrainGeometry.covers(grid.known(),.5,feet,2800),"fresh reconnect sample releases the terrain wait");
        grid.clear();
        require(grid.known().isEmpty(),"disconnect or world change clears coverage");
        var fractional=List.of(new TerrainGeometry.Surface(1000,new Box(0,5,-1,1,9.2,0)));
        var hit=TerrainGeometry.raycast(fractional,1100,new Vec3d(.5,11,-.5),new Vec3d(.5,7,-.5));
        require(hit!=null && hit.getSide()==Direction.UP && hit.getBlockPos().getY()==10 && hit.getPos().y==10,
            "native slope uses replaceable air cell above surface and legal server hit coordinates");
        require(TerrainGeometry.raycast(fractional,1100,new Vec3d(2,11,-.5),new Vec3d(2,7,-.5))==null,
            "native placement requires an actual sampled hit");
        require(TerrainGeometry.raycast(fractional,2700,new Vec3d(.5,11,-.5),new Vec3d(.5,7,-.5))==null,
            "expired native terrain is not interactable");
        require(TerrainGeometry.raycast(fractional,1100,new Vec3d(.5,8,-.5),new Vec3d(.5,11,-.5))==null,
            "native virtual terrain only exposes top faces");
        require(TerrainGeometry.raycast(fractional,1100,new Vec3d(.5,11,-.5),new Vec3d(.5,9.5,-.5))==null,
            "nearest real MC target clips native ray before rounding its placement cell");
        var arrowHit=TerrainGeometry.projectileRaycast(fractional,1100,new Vec3d(.5,11,-.5),new Vec3d(.5,7,-.5));
        require(arrowHit!=null && arrowHit.getPos().y==9.2,"arrow contact uses exact floor, without placement rounding");
        require(TerrainGeometry.projectileRaycast(fractional,2700,new Vec3d(.5,11,-.5),new Vec3d(.5,7,-.5))==null,"stale projectile floor cannot become invisible geometry");
        var start=new Vec3d(0,1,0);var end=new Vec3d(4,1,0);
        require(ProjectileTerrain.validHit(start,end,new Vec3d(2,1,0),new Vec3d(-1,0,0),1),"native wall contact lies on arrow segment");
        require(!ProjectileTerrain.validHit(start,end,new Vec3d(8,1,0),new Vec3d(-1,0,0),1),"out-of-segment native contact rejected");
        require(!ProjectileTerrain.validHit(start,end,new Vec3d(2,3,0),new Vec3d(-1,0,0),1),"off-segment native contact rejected");
        var clientWorld=new Object();var serverWorld=new Object();
        var scope=new TerrainEntityPolicy.Scope(clientWorld,serverWorld);
        require(TerrainEntityPolicy.applies(scope,serverWorld,net.minecraft.entity.TntEntity.class),"ignited TNT on integrated server receives terrain");
        require(TerrainEntityPolicy.applies(scope,clientWorld,net.minecraft.entity.TntEntity.class),"client TNT receives matching terrain");
        require(TerrainEntityPolicy.applies(scope,serverWorld,net.minecraft.entity.FallingBlockEntity.class),"falling sand receives terrain");
        require(TerrainEntityPolicy.applies(scope,serverWorld,net.minecraft.entity.ItemEntity.class),"dropped items receive terrain");
        require(TerrainEntityPolicy.applies(scope,serverWorld,net.minecraft.entity.mob.ZombieEntity.class),"spawned monsters receive terrain");
        require(!TerrainEntityPolicy.applies(scope,new Object(),net.minecraft.entity.TntEntity.class),"other dimensions untouched");
        require(!TerrainEntityPolicy.applies(scope,serverWorld,NativeActorProxy.class),"native actor proxies excluded from MC terrain movement");
        var wait=new TerrainEntityPolicy.Wait();
        require(wait.hold(false,true,1000),"nearby entity briefly waits for unknown sample");
        require(!wait.hold(false,true,1500),"entity wait expires without freezing TNT fuse forever");
        require(!wait.hold(false,false,1600),"distant entity never waits");
        require(!wait.hold(true,true,1700),"known empty sample releases entity");
        var tntBox=new Box(.01,10,-.49,.99,10.98,.49);
        require(Math.abs(VoxelShapes.cuboid(new Box(-1,6,-1,2,10,2)).calculateMaxDistance(Direction.Axis.Y,tntBox,-.4))<1e-7,
            "vanilla TNT footprint cannot fall through sampled ground");
        require(TerrainGeometry.supports(feet,cells,1100,.025),"native floor supplies stone footstep contact");
        require(!TerrainGeometry.supports(feet.offset(0,.5,0),cells,1100,.025),"airborne entity has no footstep support");
        var expanded=new TerrainGeometry();expanded.merge(state,sample(1,1000,.25f,.25f,10),1000);
        var outer=expanded.merge(state,sample(2,1100,4.25f,4.25f,10),1100);
        require(outer.size()==2,"peripheral patch retains center collision");
        require(expanded.merge(state,sample(3,1200,-3.75f,-3.75f,10),1200).size()==3,"outer patches merge around player anchor");
        System.out.println(checks+" terrain stability and native placement checks passed");
    }
    public static void main(String[] args){run();}
}
