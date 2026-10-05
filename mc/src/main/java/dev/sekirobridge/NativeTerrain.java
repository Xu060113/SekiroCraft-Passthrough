package dev.sekirobridge;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
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
import java.util.WeakHashMap;

public final class NativeTerrain {
    private record Snapshot(long epoch,UUID player,List<TerrainGeometry.Surface> surfaces,
        Map<TerrainGeometry.Key,Long> known,double spacing,TerrainEntityPolicy.Scope scope,Vec3d playerPosition){}
    private static volatile Snapshot snapshot;
    private static final TerrainGeometry geometry=new TerrainGeometry();
    private static final ByteBuffer packet=Protocol.direct(448);
    private static final Map<Entity,TerrainEntityPolicy.Wait> waits=new WeakHashMap<>();
    private NativeTerrain(){}
    private static Snapshot create(Protocol.State state,List<TerrainGeometry.Surface> surfaces,
        Map<TerrainGeometry.Key,Long> known){
        var client=MinecraftClient.getInstance();
        if(client.player==null || client.world==null)return null;
        var server=client.getServer();
        var serverWorld=server==null?null:server.getWorld(client.world.getRegistryKey());
        return new Snapshot(state.epoch(),client.player.getUuid(),surfaces,known,.5*state.scale(),
            new TerrainEntityPolicy.Scope(client.world,serverWorld),client.player.getPos());
    }
    static void seed(Protocol.State state,Vec3d position){
        geometry.clear();
        // Wait for observed terrain (which can be empty) instead of inventing a startup floor.
        synchronized(waits){waits.clear();}
        snapshot=create(state,List.of(),Map.of());
    }
    static void clear(){snapshot=null;geometry.clear();synchronized(waits){waits.clear();}}
    static void poll(Protocol.State state){
        var client=MinecraftClient.getInstance();
        if(client.player==null || (state.capabilities()&512)==0 || !NativeBridge.terrain(BridgeClient.handle(),packet))return;
        var surfaces=geometry.merge(state,packet,NativeBridge.clockMs());
        if(surfaces!=null)snapshot=create(state,surfaces,geometry.known());
    }
    private static Snapshot available(){
        var current=snapshot;var state=BridgeClient.state();
        return current!=null && BridgeClient.armed() && state!=null &&
            (state.flags()&Protocol.SCENE)!=0 && current.epoch()==state.epoch()?current:null;
    }
    /** Read-only diagnostic for the local player's current footprint. */
    public static boolean ready(){
        var current=available();var player=MinecraftClient.getInstance().player;
        return applies(current,player) && player.getUuid().equals(current.player()) &&
            TerrainGeometry.covers(current.known(),current.spacing(),player.getBoundingBox(),NativeBridge.clockMs());
    }
    private static boolean applies(Snapshot current,Entity entity){
        return current!=null && entity!=null && TerrainEntityPolicy.applies(current.scope(),entity.getWorld(),entity.getClass());
    }
    public static boolean worldActive(Object world){var current=available();var state=BridgeClient.state();
        return current!=null && current.scope().contains(world) && state!=null && Protocol.fresh(NativeBridge.clockMs(),state.tickMs());}
    public static BlockHitResult projectileRaycast(Object world,Vec3d start,Vec3d end){
        var current=available();return worldActive(world)?TerrainGeometry.projectileRaycast(current.surfaces(),NativeBridge.clockMs(),start,end):null;
    }
    /** Read-only native support query for footsteps and other contact feedback. */
    public static boolean supports(Entity entity){
        var current=available();
        return applies(current,entity) && TerrainGeometry.supports(entity.getBoundingBox(),current.surfaces(),
            NativeBridge.clockMs(),.1*current.spacing());
    }
    public static Iterable<VoxelShape> add(Object world,Entity entity,Box query,Iterable<VoxelShape> original){
        var current=available();
        if(!applies(current,entity) || world!=entity.getWorld())return original;
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
        if(!applies(current,entity) || entity.isSpectator() || (entity.noClip && !(entity instanceof ItemEntity)))return false;
        long now=NativeBridge.clockMs();
        boolean known=TerrainGeometry.covers(current.known(),current.spacing(),entity.getBoundingBox().stretch(movement),now);
        boolean ownPlayer=entity.getUuid().equals(current.player());
        boolean hold=!known && ownPlayer;
        if(!ownPlayer){
            boolean nearby=entity.getPos().squaredDistanceTo(current.playerPosition())<=
                TerrainEntityPolicy.ENTITY_WAIT_RADIUS*TerrainEntityPolicy.ENTITY_WAIT_RADIUS &&
                TerrainGeometry.touchesKnown(current.known(),current.spacing(),
                    entity.getBoundingBox().expand(current.spacing()*2),now);
            synchronized(waits){
                if(known || !nearby)waits.remove(entity);
                else hold=waits.computeIfAbsent(entity,key -> new TerrainEntityPolicy.Wait()).hold(false,true,now);
            }
        }
        if(hold){
            entity.setVelocity(Vec3d.ZERO);entity.fallDistance=0;
            return true;
        }
        // Repair only shallow penetration caused by a refreshed sampled surface.
        if(!known || (entity instanceof PlayerEntity player && player.getAbilities().flying) || entity.hasVehicle() ||
            movement.y>0.04 || entity.getVelocity().y>0.04)return false;
        double recoveryLimit=entity instanceof net.minecraft.entity.LivingEntity?
            Math.min(.6,entity.getStepHeight()):.25;
        double rise=TerrainGeometry.recovery(entity.getBoundingBox(),current.surfaces(),now,recoveryLimit);
        if(rise<=0)return false;
        var raised=entity.getBoundingBox().offset(0,rise+1e-6,0);
        if(!entity.getWorld().isSpaceEmpty(entity,raised))return false;
        entity.setPosition(entity.getX(),entity.getY()+rise,entity.getZ());
        entity.prevY+=rise;entity.lastRenderY+=rise;
        // ItemEntity may have enabled escape-from-solid noclip just before move().
        // After a verified shallow recovery its raised box is empty again.
        if(entity instanceof ItemEntity)entity.noClip=false;
        var velocity=entity.getVelocity();entity.setVelocity(velocity.x,Math.max(0,velocity.y),velocity.z);
        entity.setOnGround(true);entity.fallDistance=0;
        return false;
    }
    /** A virtual top-face hit for placement; never makes native terrain mineable. */
    public static BlockHitResult raycast(Vec3d start,Vec3d end){
        var current=available();
        return current==null?null:
            TerrainGeometry.raycast(current.surfaces(),NativeBridge.clockMs(),start,end);
    }
}
