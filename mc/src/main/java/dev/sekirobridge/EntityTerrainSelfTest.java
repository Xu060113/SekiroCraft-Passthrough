package dev.sekirobridge;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;

/** Replays independent mob rays, including elevated arenas and delayed transport. */
public final class EntityTerrainSelfTest {
    private static int checks;
    private static void require(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static ProjectileTerrain.Contact floor(EntityTerrainGeometry.Probe probe,double height,long tick){
        return new ProjectileTerrain.Contact(true,new Vec3d(probe.start().x,height,probe.start().z),new Vec3d(0,1,0),tick);
    }
    public static void main(String[] args){
        var box=new Box(20.05,30,-20.45,20.45,31.8,-20.05);
        var keys=EntityTerrainGeometry.footprint(box,.5);
        require(keys.size()==1,"distant mob footprint is independent of the player's nine-cell patch");
        var key=keys.get(0);require(key.equals(new TerrainGeometry.Key(40,40)),"negative MC Z keeps native cell handedness");
        var probe=EntityTerrainGeometry.probe(key,box.minY,.5,.6);
        require(probe.start().y==31.25 && probe.end().y==18,"high-platform ray starts at the mob's feet, not the player");
        require(probe.start().squaredDistanceTo(probe.end())<=256,"native ray length stays within existing ABI limit");
        var geometry=new EntityTerrainGeometry();
        require(!geometry.covers(box,30,.6,.5,1000),"new spawn waits until its own native floor is observed");
        require(!geometry.covers(box,30,.6,.5,1700),"missing response after 500ms cannot release a mob into the void");
        require(!geometry.observe(probe,null,1700),"transport timeout is never interpreted as a cliff");
        require(geometry.observe(probe,floor(probe,30,1800),1800),"delayed ray establishes elevated arena floor");
        require(geometry.covers(box,30,.6,.5,1800),"completed spawn footprint releases movement");
        var surfaces=geometry.surfaces(30,.6,1800);
        require(surfaces.size()==1 && surfaces.get(0).box().maxY==30,"mob floor follows its platform elevation");
        require(surfaces.get(0).box().minX==20 && surfaces.get(0).box().maxZ==-20,"fixed floor stays in the sampled world cell");
        require(Math.abs(VoxelShapes.cuboid(surfaces.get(0).box()).calculateMaxDistance(Direction.Axis.Y,box,-.8))<1e-7,
            "real vanilla collision prevents a spawned monster falling through the sampled platform");
        require(!geometry.covers(box.stretch(.7,-.08,0),30,.6,.5,1800),"walking into unseen slope cells waits for rays");
        for(var next:EntityTerrainGeometry.footprint(box.stretch(.7,-.08,0),.5)){
            var p=EntityTerrainGeometry.probe(next,30,.5,.6);geometry.observe(p,floor(p,30.1,1850),1850);
        }
        require(geometry.covers(box.stretch(.7,-.08,0),30,.6,.5,1850),"all swept cells resolve before a mob walks onto a slope");
        require(Math.abs(TerrainGeometry.recovery(box,geometry.surfaces(30,.6,1850),1850,.6)-.1)<1e-7,
            "shallow slope penetration keeps bounded vanilla step recovery");
        require(!geometry.refresh(key,30,.6,1900),"recent static floor is reused instead of issuing a ray every tick");
        require(geometry.refresh(key,30,.6,2400),"dynamic native floor gets periodic refresh");
        require(!geometry.covers(box,30,.6,.5,3401),"expired observation holds movement pending refresh");
        require(geometry.surfaces(30,.6,3401).isEmpty(),"stale surfaces do not invent persistent floors");
        var lower=new EntityTerrainGeometry();var lowProbe=EntityTerrainGeometry.probe(key,10,.5,.6);
        require(lower.observe(lowProbe,floor(lowProbe,10,2000),2000),"lower platform underneath upper arena has its own answer");
        require(lower.surfaces(10,.6,2000).get(0).box().maxY==10,"same XZ on a lower layer cannot inherit upper platform collision");
        require(geometry.surfaces(10,.6,2000).isEmpty(),"upper layer observation is discarded after downward teleport");
        require(lower.surfaces(30,.6,2000).isEmpty(),"lower layer is not reused after upward teleport");
        var cliff=new EntityTerrainGeometry();
        require(cliff.observe(probe,new ProjectileTerrain.Contact(false,Vec3d.ZERO,Vec3d.ZERO,2100),2100),"verified native miss is known open space");
        require(cliff.covers(box,30,.6,.5,2100) && cliff.surfaces(30,.6,2100).isEmpty(),"real cliff releases falling without a fake floor");
        require(!cliff.covers(box.offset(0,-20,0),10,.6,.5,2100),"falling beyond a miss segment requires a new lower query");
        require(!cliff.observe(probe,new ProjectileTerrain.Contact(true,new Vec3d(probe.start().x,30,probe.start().z),
            new Vec3d(1,0,0),2200),2200),"wall normal does not establish empty space below a mob");
        require(!cliff.observe(probe,floor(probe,100,2200),2200),"out-of-segment floor is rejected");
        require(!cliff.observe(probe,floor(probe,30,5000),2200),"future response cannot become collision");
        require(!cliff.observe(probe,floor(probe,30,100),2200),"stale response cannot become collision");
        var positive=EntityTerrainGeometry.footprint(new Box(.01,0,.01,.49,1,.49),.5);
        require(positive.equals(java.util.List.of(new TerrainGeometry.Key(0,-1))),"positive MC Z uses inverted cell indices");
        require(EntityTerrainGeometry.footprint(new Box(0,0,0,.5,1,.5),.5).size()==1,"exact cell edges do not demand a phantom neighbor");
        require(EntityTerrainGeometry.footprint(new Box(0,0,0,4,4,4),.5).size()==64,"large modded mob footprint is sampled across its body");
        require(EntityTerrainGeometry.footprint(new Box(0,0,0,1000,1,1000),.5).isEmpty(),"pathological footprints cannot exhaust the ray queue");
        require(EntityTerrainGeometry.footprint(box,0).isEmpty(),"invalid terrain scale is rejected");
        var scaled=EntityTerrainGeometry.probe(key,50,1,1.2);
        require(scaled.start().y==52.5 && scaled.end().y==26,"mob ray vertical range follows bridge scale");
        require(scaled.start().squaredDistanceTo(scaled.end())<=256*4,"scaled ray still fits native segment limit");
        geometry.prune(box.offset(100,0,0),.5,2200);
        require(geometry.surfaces(30,.6,2200).isEmpty(),"old cells are evicted when a mob moves away");
        System.out.println(checks+" entity-specific native terrain checks passed");
    }
}
