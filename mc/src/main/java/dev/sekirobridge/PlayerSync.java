package dev.sekirobridge;

import net.minecraft.client.MinecraftClient;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

final class PlayerSync {
    private ServerPlayerEntity owned;
    private boolean oldGravity;
    private boolean oldClip;
    private Vec3d originalPosition;
    private float originalYaw, originalPitch;
    private int command;
    private long epoch;
    private Vec3d lastClientPosition;
    private Object clientIdentity;
    private long clientEpoch, sampleTick;
    private int animationAge = -1;
    private Vec3d movement = Vec3d.ZERO;
    void client(Protocol.State s) {
        var p = MinecraftClient.getInstance().player;
        if (p == null)
            return;
        Vec3d position = new Vec3d(s.mcX(s.px()), s.mcY(s.py()), s.mcZ(s.pz()));
        if (clientIdentity != p || clientEpoch != s.epoch()) {
            clientIdentity = p;
            clientEpoch = s.epoch();
            lastClientPosition = position;
            sampleTick = s.tickMs();
            animationAge = -1;
            movement = Vec3d.ZERO;
        }
        if (s.tickMs() > sampleTick) {
            long dt = s.tickMs() - sampleTick;
            Vec3d delta = position.subtract(lastClientPosition);
            movement = dt <= 350 && delta.lengthSquared() < 25 ? delta.multiply(50.0 / dt) : Vec3d.ZERO;
            sampleTick = s.tickMs();
            lastClientPosition = position;
        }
        p.setPosition(position);
        p.prevX = p.lastRenderX = position.x;
        p.prevY = p.lastRenderY = position.y;
        p.prevZ = p.lastRenderZ = position.z;
        p.setYaw(s.yaw());
        p.setPitch(s.pitch());
        p.prevYaw = p.lastRenderYaw = s.yaw();
        p.prevPitch = p.lastRenderPitch = s.pitch();
        p.setHeadYaw(s.yaw());
        if (movement.horizontalLengthSquared() > .0001)
            p.setBodyYaw((float)Math.toDegrees(Math.atan2(-movement.x, movement.z)));
        p.setSprinting(movement.horizontalLengthSquared() > .07);
        p.setVelocity(Vec3d.ZERO);
    }
    void animate() {
        var p = MinecraftClient.getInstance().player;
        if (p != null && p == clientIdentity && animationAge != p.age) {
            animationAge = p.age;
            p.limbAnimator.updateLimbs((float)Math.min(1, movement.horizontalLength() * 4), .4f);
        }
    }
    void server(MinecraftServer server, Protocol.State s) {
        if (s == null) {
            release();
            return;
        }
        var client = MinecraftClient.getInstance();
        if (client.player == null) {
            release();
            return;
        }
        var p = server.getPlayerManager().getPlayer(client.player.getUuid());
        if (p == null) {
            release();
            return;
        }
        if (owned != p) {
            release();
            owned = p;
            oldGravity = p.hasNoGravity();
            oldClip = p.noClip;
            originalPosition = p.getPos();
            originalYaw = p.getYaw();
            originalPitch = p.getPitch();
            command = s.command();
            epoch = s.epoch();
        }
        if (epoch != s.epoch()) {
            epoch = s.epoch();
            command = s.command();
        }
        // Host locomotion is authoritative; this never writes Sekiro's transform or physics.
        p.setNoGravity(true);
        p.noClip = true;
        p.setVelocity(Vec3d.ZERO);
        p.fallDistance = 0;
        p.teleport(p.getServerWorld(), s.mcX(s.px()), s.mcY(s.py()), s.mcZ(s.pz()), s.yaw(), s.pitch());
        if (command != s.command()) {
            command = s.command();
            BlockPos target = BlockPos.ofFloored(s.mcX(s.ex() + s.fx() * 4), s.mcY(s.ey() + s.fy() * 4),
                                                 s.mcZ(s.ez() + s.fz() * 4));
            if (p.getServerWorld().isInBuildLimit(target) && p.getServerWorld().isAir(target) &&
                !p.getBoundingBox().intersects(new net.minecraft.util.math.Box(target)))
                p.getServerWorld().setBlockState(target, Blocks.GRASS_BLOCK.getDefaultState());
        }
    }
    private void release() {
        if (owned != null) {
            owned.setNoGravity(oldGravity);
            owned.noClip = oldClip;
            owned.setVelocity(Vec3d.ZERO);
            // Do not leave the player stranded above a void world when the host disappears.
            owned.teleport(owned.getServerWorld(), originalPosition.x, originalPosition.y, originalPosition.z,
                           originalYaw, originalPitch);
            owned = null;
        }
    }
}
