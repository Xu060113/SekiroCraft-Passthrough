package dev.sekirobridge;

import org.joml.Matrix4f;
import org.joml.Vector3f;

public final class ForgeRenderedCamera {
    private static int checks;
    private static void require(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    public static void main(String[] args){
        for(float yaw:new float[]{0,37,179,-143})for(float pitch:new float[]{0,42,-68,90,-90})for(float roll:new float[]{0,35,-79,160}){
            var view=new Matrix4f().rotateZ((float)Math.toRadians(roll)).rotateX((float)Math.toRadians(pitch))
                .rotateY((float)Math.toRadians(yaw+180));
            var axes=RenderedCamera.axes(view,yaw);
            float angle=(float)Math.toRadians(axes.yaw()),tilt=(float)Math.toRadians(axes.roll());
            var forward=new Vector3f(axes.fx(),axes.fy(),axes.fz());
            var right=new Vector3f(-(float)Math.cos(angle),0,(float)Math.sin(angle));
            var up=new Vector3f(forward).cross(right).normalize();
            right.mul((float)Math.cos(tilt)).add(up.mul((float)Math.sin(tilt)));
            require(right.dot(new Vector3f(view.m00(),view.m10(),-view.m20()))>.99999,"final roll basis incl. poles");
            require(Math.abs(forward.length()-1)<.00001,"normalized camera forward");
            int packed=Protocol.packAngles(axes.yaw(),axes.roll());
            require(Math.abs((short)(packed>>>16)*180f/32767-axes.roll())<.006,"native packed roll precision");
        }
        System.out.println(checks+" final Forge camera/roll checks passed (no game launched)");
    }
}
