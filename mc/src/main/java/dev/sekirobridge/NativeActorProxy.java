package dev.sekirobridge;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;
import org.joml.Vector3f;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.Vec3d;
import java.util.*;
/** Ephemeral invisible hit target; native physics alone supplies its position. */
public class NativeActorProxy extends PathAwareEntity implements NativeTerrainExcluded {
    private static final TrackedData<Vector3f> BODY=DataTracker.registerData(NativeActorProxy.class,TrackedDataHandlerRegistry.VECTOR3F);
    private static final TrackedData<NbtCompound> PARTS=DataTracker.registerData(NativeActorProxy.class,TrackedDataHandlerRegistry.NBT_COMPOUND);
    private boolean bodyTracked;
    private List<Box> relativeParts=List.of();
    private boolean physicalTick;
    long nativeId;int nativeFlags;long epoch,stage;
    public NativeActorProxy(EntityType<? extends PathAwareEntity> type,World world){super(type,world);
        setAiDisabled(true);setNoGravity(true);noClip=true;setPersistent();setSilent(true);}
    @Override protected void initDataTracker(){super.initDataTracker();dataTracker.startTracking(BODY,new Vector3f(.6f,1.8f,0));dataTracker.startTracking(PARTS,new NbtCompound());bodyTracked=true;}
    private Vector3f body(){return bodyTracked?dataTracker.get(BODY):new Vector3f(.6f,1.8f,0);}
    void updateBody(ActorShapeProtocol.Shape shape,float scale){
        float width=shape==null?.6f:shape.width(),height=shape==null?1.8f:shape.height(),offset=shape==null?0:shape.yOffset();
        var next=new Vector3f(width*scale,height*scale,offset*scale);
        // DataTracker carries the same atomic bounds to the client used by
        // vanilla crosshair, arrows, TaCZ and SlashBlade ray/entity queries.
        if(!next.equals(body())){dataTracker.set(BODY,next);calculateDimensions();}
    }
    void updateModelParts(ActorPartsProtocol.Body model,float scale,Vec3d offset){
        var packet=new NbtCompound();
        if(model!=null){var coordinates=new int[model.parts().size()*6];int at=0;
            for(var p:model.parts())for(float f:new float[]{p.minX(),p.minY(),p.minZ(),p.maxX(),p.maxY(),p.maxZ()})coordinates[at++]=Float.floatToIntBits(f);
            packet.putIntArray("boxes",coordinates);packet.putFloat("scale",scale);
            packet.putFloat("dx",(float)offset.x);packet.putFloat("dy",(float)offset.y);packet.putFloat("dz",(float)offset.z);}
        if(!packet.equals(dataTracker.get(PARTS)))dataTracker.set(PARTS,packet);
    }
    private void readModelParts(){
        var packet=dataTracker.get(PARTS);int[] coordinates=packet.getIntArray("boxes");float scale=packet.getFloat("scale");
        float dx=packet.getFloat("dx"),dy=packet.getFloat("dy"),dz=packet.getFloat("dz");
        var next=new ArrayList<Box>();
        if(coordinates.length%6==0 && coordinates.length<=128*6 && Float.isFinite(scale) && scale>0 && scale<=16 &&
           Float.isFinite(dx) && Math.abs(dx)<=4096 && Float.isFinite(dy) && Math.abs(dy)<=4096 && Float.isFinite(dz) && Math.abs(dz)<=4096){
            for(int i=0;i<coordinates.length;i+=6){var p=new ActorPartsProtocol.Part(Float.intBitsToFloat(coordinates[i]),Float.intBitsToFloat(coordinates[i+1]),
                Float.intBitsToFloat(coordinates[i+2]),Float.intBitsToFloat(coordinates[i+3]),Float.intBitsToFloat(coordinates[i+4]),Float.intBitsToFloat(coordinates[i+5]));
                if(!p.valid()){next.clear();break;}
                // Native Z is reversed by the bridge; swap its minimum and maximum.
                next.add(new Box((p.minX()+dx)*scale,(p.minY()+dy)*scale,-(p.maxZ()+dz)*scale,(p.maxX()+dx)*scale,(p.maxY()+dy)*scale,-(p.minZ()+dz)*scale));}
        }
        relativeParts=List.copyOf(next);
    }
    @Override public void onTrackedDataSet(TrackedData<?> data){super.onTrackedDataSet(data);
        if(PARTS.equals(data)){readModelParts();calculateDimensions();NativeActorIndex.track(this);}
        else if(BODY.equals(data))calculateDimensions();}
    @Override public EntityDimensions getDimensions(EntityPose pose){var b=body();return EntityDimensions.changing(b.x,b.y);}
    @Override protected Box calculateBoundingBox(){
        if(physicalTick)return NativeActorGeometry.bounds(getX(),getY(),getZ(),.6f,1.8f,0);
        if(relativeParts!=null && !relativeParts.isEmpty())return NativeActorBounds.of(relativeParts.stream().map(p->p.offset(getX(),getY(),getZ())).toList());
        var b=body();
        return NativeActorGeometry.bounds(getX(),getY(),getZ(),b.x,b.y,b.z);}
    @Override protected float getActiveEyeHeight(EntityPose pose,EntityDimensions dimensions){return dimensions.height*.85f+body().z;}
    @Override public void updateTrackedPositionAndAngles(double x,double y,double z,float yaw,float pitch,int steps,boolean interpolate){
        // Native animation/root motion is already displayed in Sekiro. Adding
        // vanilla's three-tick NPC interpolation displaces its invisible target.
        refreshPositionAndAngles(x,y,z,yaw,pitch);
    }
    public boolean hostile(){return (nativeFlags&1)!=0 && isAlive() && CombatBridge.serverActive();}
    @Override public double squaredDistanceTo(double x,double y,double z){
        return getBoundingBox() instanceof NativeActorBounds bounds?bounds.squaredMagnitude(new Vec3d(x,y,z)):super.squaredDistanceTo(x,y,z);}
    @Override public boolean canHit(){return isAlive();}
    @Override public boolean damage(DamageSource source,float amount){
        if((nativeFlags&2)!=0 || !CombatBridge.serverActive() || getWorld().isClient)return false;
        return super.damage(source,amount);
    }
    @Override protected void applyDamage(DamageSource source,float amount){
        if(!getWorld().isClient && amount>0)CombatBridge.hit(this,source,amount);
        // Health is acknowledged by Sekiro; never run MC death/loot from predicted damage.
    }
    @Override public boolean isPushable(){return false;}
    @Override public void takeKnockback(double strength,double x,double z){}
    @Override public void checkDespawn(){}
    @Override public void tick(){
        // Vanilla checks water/blocks across an entity's entire outer AABB.
        // A serpent's damage bounds can span hundreds of metres; scanning that
        // volume every tick would stall MC. Keep environmental bookkeeping on
        // a small, non-colliding root and restore multipart DAMAGE geometry in
        // finally. Fluids must not move the native-owned invisible target.
        double x=getX(),y=getY(),z=getZ();physicalTick=true;setBoundingBox(calculateBoundingBox());
        try{super.tick();}finally{physicalTick=false;setVelocity(0,0,0);setPosition(x,y,z);}
    }
}
