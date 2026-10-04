package dev.sekirobridge;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import java.nio.ByteBuffer;

final class PhysicsExporter {
    private final ByteBuffer packet = Protocol.direct(6208);
    private long sequence;
    void update(Protocol.State s) {
        var c = MinecraftClient.getInstance();
        if (c.player == null || c.world == null || (s.capabilities() & 64) == 0) return;
        packet.clear();
        boolean flying = c.player.getAbilities().allowFlying && c.player.getAbilities().flying;
        packet.putLong(NativeBridge.clockMs()).putLong(s.tickMs()).putLong(s.epoch());
        int flags = 1 | (flying ? 2 : 0) | (c.player.isCreative() ? 4 : 0);
        packet.putInt(flags).putInt(0);
        packet.putFloat(s.px()).putFloat(s.py()).putFloat(s.pz());
        packet.putFloat(.3f / s.scale()).putFloat(1.8f / s.scale()).putInt(0).putLong(++sequence);
        BlockPos center = BlockPos.ofFloored(s.mcX(s.px()), s.mcY(s.py()), s.mcZ(s.pz()));
        int count = 0;
        boolean complete = true;
        // Bounded local snapshot, using each block's actual shape (slabs, stairs,
        // fences, doors). Never force-load distant chunks or truncate a solid wall.
        outer: for (BlockPos pos : BlockPos.iterate(center.add(-3, -3, -3), center.add(3, 4, 3))) {
            if (!c.world.isChunkLoaded(pos)) { complete = false; break; }
            var state = c.world.getBlockState(pos);
            for (var box : state.getCollisionShape(c.world, pos, net.minecraft.block.ShapeContext.of(c.player)).getBoundingBoxes()) {
                if (count == 256) { complete = false; break outer; }
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
