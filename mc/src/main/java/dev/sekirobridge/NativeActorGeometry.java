package dev.sekirobridge;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.MathHelper;

/** Shared by server collision and client targeting; x/y/z are native root mapped into MC. */
final class NativeActorGeometry {
    private NativeActorGeometry(){}
    static Box bounds(double x,double y,double z,float width,float height,float offset){
        double half=width/2d;
        return new Box(x-half,y+offset,z-half,x+half,y+offset+height,z+half);
    }
    /** Keep the invisible entity's tracking anchor close to the visible body.
     * Minecraft limits tracking by root chunk/view distance even when the
     * actor's full bounding box reaches into the player's loaded chunks. */
    static Vec3d anchor(ActorPartsProtocol.Body body,Vec3d root,Vec3d player,float scale){
        if(body==null || root.squaredDistanceTo(player)*scale*scale<=32*32)return root;
        Vec3d relative=player.subtract(root),nearest=root;double distance=Double.POSITIVE_INFINITY;
        for(var p:body.parts()){
            var point=root.add(MathHelper.clamp(relative.x,p.minX(),p.maxX()),MathHelper.clamp(relative.y,p.minY(),p.maxY()),MathHelper.clamp(relative.z,p.minZ(),p.maxZ()));
            double d=point.squaredDistanceTo(player);if(d<distance){distance=d;nearest=point;}}
        return nearest;
    }
}
