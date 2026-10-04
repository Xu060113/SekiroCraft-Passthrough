package dev.sekirobridge;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

public final class InteractionRay {
    private InteractionRay() {}
    public static void update() {
        if (!BridgeClient.active()) return;
        var c = MinecraftClient.getInstance();
        var s = BridgeClient.state();
        if (c.player == null || c.world == null || c.interactionManager == null) return;
        Vec3d eye = new Vec3d(s.mcX(s.ex()), s.mcY(s.ey()), s.mcZ(s.ez()));
        Vec3d direction = new Vec3d(s.fx(), s.fy(), -s.fz()).normalize();
        double reach = c.interactionManager.getReachDistance();
        Vec3d end = eye.add(direction.multiply(reach));
        BlockHitResult block = c.world.raycast(new RaycastContext(eye, end,
            RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, c.player));
        c.crosshairTarget = block;
        c.targetedEntity = null;
        double distance = eye.squaredDistanceTo(block.getPos());
        var entity = ProjectileUtil.raycast(c.player, eye, end, new Box(eye, end).expand(1),
            e -> !e.isSpectator() && e.canHit(), distance);
        if (entity != null && eye.squaredDistanceTo(entity.getPos()) < distance &&
            (c.interactionManager.hasExtendedReach() || eye.squaredDistanceTo(entity.getPos()) <= 9)) {
            c.crosshairTarget = entity;
            c.targetedEntity = entity.getEntity();
        }
    }
}
