package dev.sekirobridge;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.ArrowEntityRenderer;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.util.Identifier;

/** Draw lodged arrows only; never draws a second character or the Wolf mesh. */
final class NativeActorRenderer<T extends NativeActorProxy> extends EntityRenderer<T> {
    private final ArrowEntityRenderer arrows;
    private ArrowEntity arrow;
    NativeActorRenderer(EntityRendererFactory.Context context){super(context);arrows=new ArrowEntityRenderer(context);}
    @Override public Identifier getTexture(T actor){return new Identifier("minecraft","textures/misc/white.png");}
    @Override public void render(T actor,float yaw,float delta,MatrixStack matrices,VertexConsumerProvider buffers,int light){
        int count=Math.min(16,actor.getStuckArrowCount());if(count==0)return;
        if(arrow==null || arrow.getWorld()!=actor.getWorld())arrow=new ArrowEntity(actor.getWorld(),0,0,0);
        for(int i=0;i<count;++i){
            float angle=(i*137.5f+actor.getId()*17)%360;
            arrow.setYaw(angle);arrow.prevYaw=angle;arrow.setPitch(-10);arrow.prevPitch=-10;
            double radians=Math.toRadians(angle);
            var box=actor.getBoundingBox();
            if(box instanceof NativeActorBounds body)box=body.parts().get(i%body.parts().size());
            double radius=(box.maxX-box.minX)*.3;
            matrices.push();matrices.translate((box.minX+box.maxX)*.5-actor.getX()+Math.sin(radians)*radius,
                box.minY-actor.getY()+(box.maxY-box.minY)*(.36+(i%5)*.1),(box.minZ+box.maxZ)*.5-actor.getZ()+Math.cos(radians)*radius);
            arrows.render(arrow,angle,delta,matrices,buffers,light);matrices.pop();
        }
    }
}
