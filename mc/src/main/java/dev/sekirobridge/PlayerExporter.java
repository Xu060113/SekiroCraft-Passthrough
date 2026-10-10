package dev.sekirobridge;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import java.nio.ByteBuffer;

public final class PlayerExporter {
    private static final ByteBuffer packet=Protocol.direct(104);
    private static long sequence;
    private PlayerExporter(){}
    public static void camera(Camera camera){
        publish(camera,null);
    }
    public static void camera(Camera camera,org.joml.Matrix4f worldView){
        publish(camera,worldView);
    }
    private static void publish(Camera camera,org.joml.Matrix4f worldView){
        if(!BridgeClient.connected())return;
        var c=MinecraftClient.getInstance();var s=BridgeClient.state();var p=c.player;if(p==null)return;
        var eye=camera.getPos();var forward=camera.getHorizontalPlane();
        Protocol.State pose=s.withCamera((float)(eye.x/s.scale()),(float)((eye.y-s.yOffset())/s.scale()),
            (float)(-eye.z/s.scale()),forward.x,forward.y,-forward.z,net.minecraft.util.math.MathHelper.wrapDegrees(camera.getYaw()))
            .withFov(BridgeClient.frameFov());
        if(worldView!=null){
            var axes=RenderedCamera.axes(worldView,camera.getYaw());
            pose=pose.withCamera(pose.ex(),pose.ey(),pose.ez(),axes.fx(),axes.fy(),axes.fz(),axes.yaw(),axes.roll());
        }
        BridgeClient.renderPose(pose);
        packet.clear();packet.putLong(++sequence).putLong(NativeBridge.clockMs()).putLong(s.tickMs()).putLong(s.epoch());
        packet.putInt(1|(p.getAbilities().flying?2:0)|(c.currentScreen!=null?4:0)).putFloat(pose.captureYaw());
        packet.putFloat((float)(p.getX()/s.scale())).putFloat((float)((p.getY()-s.yOffset())/s.scale())).putFloat((float)(-p.getZ()/s.scale()));
        packet.putFloat(pose.ex()).putFloat(pose.ey()).putFloat(pose.ez());packet.putFloat(pose.fx()).putFloat(pose.fy()).putFloat(pose.fz());
        packet.putFloat(pose.fov()).putFloat(s.aspect()).putFloat(s.near()).putFloat(s.far());
        var v=p.getVelocity().multiply(20/s.scale());packet.putFloat((float)v.x).putFloat((float)v.y).putFloat((float)-v.z).flip();
        NativeBridge.player(BridgeClient.handle(),packet);
    }
}
