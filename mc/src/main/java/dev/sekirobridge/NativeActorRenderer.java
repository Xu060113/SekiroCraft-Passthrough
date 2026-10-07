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
            matrices.push();matrices.translate(Math.sin(radians)*.18,.65+(i%5)*.18,Math.cos(radians)*.18);
            arrows.render(arrow,angle,delta,matrices,buffers,light);matrices.pop();
        }
    }
}
