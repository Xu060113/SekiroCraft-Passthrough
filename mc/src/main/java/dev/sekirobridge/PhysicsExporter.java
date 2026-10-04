package dev.sekirobridge;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import java.nio.ByteBuffer;

final class PhysicsExporter {
    static final int MAX_SHAPES=4096,PACKET_BYTES=98392;
    private final ByteBuffer packet = Protocol.direct(PACKET_BYTES);
    private long sequence,lastTick;
    void update(Protocol.State s) {
        var c = MinecraftClient.getInstance();
        if (c.player == null || c.world == null || (s.capabilities() & 64) == 0) return;
        long now=NativeBridge.clockMs();
        if(now-lastTick<50)return;
        lastTick=now;
        packet.clear();
        boolean flying = c.player.getAbilities().allowFlying && c.player.getAbilities().flying;
        packet.putLong(now).putLong(s.tickMs()).putLong(s.epoch());
        int flags = 1 | (flying ? 2 : 0) | (c.player.isCreative() ? 4 : 0);
        packet.putInt(flags).putInt(0);
        float px=(float)(c.player.getX()/s.scale()),py=(float)((c.player.getY()-s.yOffset())/s.scale()),
              pz=(float)(-c.player.getZ()/s.scale());
        packet.putFloat(px).putFloat(py).putFloat(pz);
        packet.putFloat(.3f / s.scale()).putFloat(1.8f / s.scale()).putInt(0).putLong(++sequence);
        BlockPos center = c.player.getBlockPos();
        BlockPos low=center.add(-6,-4,-6),high=center.add(6,7,6);
        packet.putFloat(low.getX()/s.scale()).putFloat((low.getY()-s.yOffset())/s.scale())
              .putFloat(-(high.getZ()+1)/s.scale());
        packet.putFloat((high.getX()+1)/s.scale()).putFloat((high.getY()+1-s.yOffset())/s.scale())
              .putFloat(-low.getZ()/s.scale());
        int count = 0;
        boolean complete = true;
        // Bounded local snapshot, using each block's actual shape (slabs, stairs,
        // fences, doors). Never force-load distant chunks or truncate a solid wall.
        outer: for (BlockPos pos : BlockPos.iterate(low,high)) {
            if (!c.world.isChunkLoaded(pos)) { complete = false; break; }
            var state = c.world.getBlockState(pos);
            for (var box : state.getCollisionShape(c.world, pos, net.minecraft.block.ShapeContext.of(c.player)).getBoundingBoxes()) {
                if (count == MAX_SHAPES) { complete = false; break outer; }
                packet.putFloat((float)((pos.getX() + box.minX) / s.scale()));
                packet.putFloat((float)((pos.getY() + box.minY - s.yOffset()) / s.scale()));
                packet.putFloat((float)(-(pos.getZ() + box.maxZ) / s.scale()));
                packet.putFloat((float)((pos.getX() + box.maxX) / s.scale()));
                packet.putFloat((float)((pos.getY() + box.maxY - s.yOffset()) / s.scale()));
                packet.putFloat((float)(-(pos.getZ() + box.minZ) / s.scale()));
                count++;
            }
        }
        packet.putInt(24, complete ? flags : flags & ~1);
        packet.putInt(28, complete ? count : 0);
        packet.position(0);
        NativeBridge.physics(BridgeClient.handle(), packet);
    }
}
