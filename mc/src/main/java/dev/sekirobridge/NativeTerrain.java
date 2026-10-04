package dev.sekirobridge;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class NativeTerrain {
    private record Snapshot(long epoch,UUID player,List<TerrainGeometry.Surface> surfaces,
        Map<TerrainGeometry.Key,Long> known,double spacing){}
    private static volatile Snapshot snapshot;
    private static final TerrainGeometry geometry=new TerrainGeometry();
    private static final ByteBuffer packet=Protocol.direct(448);
    private NativeTerrain(){}
    static void seed(Protocol.State state,Vec3d position){
        geometry.clear();
        // Wait for observed terrain (which can be empty) instead of inventing a startup floor.
        snapshot=new Snapshot(state.epoch(),MinecraftClient.getInstance().player.getUuid(),
            List.of(),Map.of(),.5*state.scale());
    }
    static void clear(){snapshot=null;geometry.clear();}
    static void poll(Protocol.State state){
        var client=MinecraftClient.getInstance();
        if(client.player==null || (state.capabilities()&512)==0 || !NativeBridge.terrain(BridgeClient.handle(),packet))return;
        var surfaces=geometry.merge(state,packet,NativeBridge.clockMs());
        if(surfaces!=null)snapshot=new Snapshot(state.epoch(),client.player.getUuid(),surfaces,
            geometry.known(),.5*state.scale());
    }
    private static Snapshot available(){
        var current=snapshot;var state=BridgeClient.state();
        return current!=null && BridgeClient.armed() && state!=null &&
            (state.flags()&Protocol.SCENE)!=0 && current.epoch()==state.epoch()?current:null;
    }
    /** Read-only diagnostic for the local player's current footprint. */
    public static boolean ready(){
        var current=available();var player=MinecraftClient.getInstance().player;
        return current!=null && player!=null && player.getUuid().equals(current.player()) &&
            TerrainGeometry.covers(current.known(),current.spacing(),player.getBoundingBox(),NativeBridge.clockMs());
    }
    public static Iterable<VoxelShape> add(Entity entity,Box query,Iterable<VoxelShape> original){
        var current=available();
        if(current==null || entity==null || !entity.getUuid().equals(current.player()))return original;
        long now=NativeBridge.clockMs();
        ArrayList<VoxelShape> shapes=null;
        for(var surface:current.surfaces())if(TerrainGeometry.retained(now,surface.tick()) && surface.box().intersects(query)){
            if(shapes==null){shapes=new ArrayList<>();for(var shape:original)shapes.add(shape);}
            shapes.add(VoxelShapes.cuboid(surface.box()));
        }
        return shapes==null?original:shapes;
    }
    /** Unknown terrain waits for a sample; a confirmed ray miss remains ordinary open space. */
    public static boolean beforeMove(Entity entity,Vec3d movement){
        var current=available();
        if(current==null || !(entity instanceof PlayerEntity player) || !entity.getUuid().equals(current.player()))return false;
        long now=NativeBridge.clockMs();
        if(!TerrainGeometry.covers(current.known(),current.spacing(),player.getBoundingBox().stretch(movement),now)){
            player.setVelocity(Vec3d.ZERO);player.fallDistance=0;
            return true;
        }
        // Repair only shallow penetration caused by a refreshed sampled surface.
        if(player.getAbilities().flying || player.isSpectator() || player.hasVehicle() ||
            movement.y>0.04 || player.getVelocity().y>0.04)return false;
        double rise=TerrainGeometry.recovery(player.getBoundingBox(),current.surfaces(),NativeBridge.clockMs(),
            Math.min(.6,player.getStepHeight()));
        if(rise<=0)return false;
        var raised=player.getBoundingBox().offset(0,rise+1e-6,0);
        if(!player.getWorld().isSpaceEmpty(player,raised))return false;
        player.setPosition(player.getX(),player.getY()+rise,player.getZ());
        player.prevY+=rise;player.lastRenderY+=rise;
        var velocity=player.getVelocity();player.setVelocity(velocity.x,Math.max(0,velocity.y),velocity.z);
        player.setOnGround(true);player.fallDistance=0;
        return false;
    }
    /** A virtual top-face hit for placement; never makes native terrain mineable. */
    public static BlockHitResult raycast(Vec3d start,Vec3d end){
        var current=available();
        return current==null?null:
            TerrainGeometry.raycast(current.surfaces(),NativeBridge.clockMs(),start,end);
    }
}
