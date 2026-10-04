package dev.sekirobridge;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class NativeTerrain {
    private record Snapshot(long tick,long epoch,UUID player,List<Box> boxes){}
    private static volatile Snapshot snapshot;
    private static long seededAt;
    private static final ByteBuffer packet=Protocol.direct(448);
    private NativeTerrain(){}
    static void seed(Protocol.State s,Vec3d position){
        seededAt=NativeBridge.clockMs();
        // A short bootstrap support while the physics-thread terrain query starts.
        snapshot=new Snapshot(seededAt,s.epoch(),MinecraftClient.getInstance().player.getUuid(),
            List.of(new Box(position.x-1,position.y-.1,position.z-1,position.x+1,position.y,position.z+1)));
    }
    static void clear(){snapshot=null;seededAt=0;}
    static void poll(Protocol.State s){
        var c=MinecraftClient.getInstance();
        if(c.player==null || (s.capabilities()&512)==0 || !NativeBridge.terrain(BridgeClient.handle(),packet))return;
        long tick=packet.getLong(8),epoch=packet.getLong(16),now=NativeBridge.clockMs();
        if(epoch!=s.epoch() || !Protocol.fresh(now,tick))return;
        float x=packet.getFloat(24),y=packet.getFloat(28),z=packet.getFloat(32),spacing=packet.getFloat(36);
        if(spacing!=.5f || !Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z))return;
        var boxes=new ArrayList<Box>();
        for(int iz=0;iz<9;++iz)for(int ix=0;ix<9;++ix){
            int i=iz*9+ix;if(packet.get(364+i)==0)continue;
            double height=packet.getFloat(40+4*i);
            if(!Double.isFinite(height) || Math.abs(height-y)>12)return;
            double cx=x+(ix-4)*spacing,cz=z+(iz-4)*spacing;
            boxes.add(new Box(s.mcX(cx-spacing/2),s.mcY(height-4),s.mcZ(cz+spacing/2),
                s.mcX(cx+spacing/2),s.mcY(height),s.mcZ(cz-spacing/2)));
        }
        // No hit means open space: do not invent a floor across a drop.
        snapshot=new Snapshot(tick,epoch,c.player.getUuid(),List.copyOf(boxes));
    }
    public static Iterable<VoxelShape> add(Entity entity,Box query,Iterable<VoxelShape> original){
        var s=snapshot;
        if(s==null || entity==null || !entity.getUuid().equals(s.player()) || !BridgeClient.connected() ||
            s.epoch()!=BridgeClient.state().epoch() || !Protocol.fresh(NativeBridge.clockMs(),s.tick()))return original;
        var shapes=new ArrayList<VoxelShape>();for(var shape:original)shapes.add(shape);
        for(var box:s.boxes())if(box.intersects(query))shapes.add(VoxelShapes.cuboid(box));
        return shapes;
    }
}
