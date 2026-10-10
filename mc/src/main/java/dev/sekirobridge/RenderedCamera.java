package dev.sekirobridge;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Extract the actual world-view axes after mod camera events and roll. */
public final class RenderedCamera {
    private RenderedCamera() {}
    public record Axes(float fx,float fy,float fz,float yaw,float roll) {}
    public static Axes axes(Matrix4f view,float poleYaw) {
        // MC looks down view -Z. Native world coordinates reflect MC world Z.
        var f=new Vector3f(-view.m02(),-view.m12(),view.m22()).normalize();
        var r=new Vector3f(view.m00(),view.m10(),-view.m20()).normalize();
        float yaw=Math.hypot(f.x,f.z)<0.0001 ? poleYaw : (float)Math.toDegrees(Math.atan2(-f.x,-f.z));
        float radians=(float)Math.toRadians(yaw);
        var horizontalRight=new Vector3f(-(float)Math.cos(radians),0,(float)Math.sin(radians));
        var up=new Vector3f(f).cross(horizontalRight).normalize();
        float roll=(float)Math.toDegrees(Math.atan2(r.dot(up),r.dot(horizontalRight)));
        return new Axes(f.x,f.y,f.z,yaw,roll);
    }
}
