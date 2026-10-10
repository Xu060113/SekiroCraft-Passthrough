package dev.sekirobridge;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.hit.EntityHitResult;

/** Shows engine counters; never manufactures a deathblow opportunity. */
public final class CombatHud {
    private CombatHud(){}
    private static void gauge(DrawContext draw,int x,int y,float ratio){
        draw.fill(x-1,y-1,x+101,y+5,0xb0000000);
        draw.fill(x,y,x+100,y+4,0xff403927);
        draw.fill(x,y,x+Math.round(100*ratio),y+4,0xffe6a441);
    }
    public static void render(DrawContext draw){
        var c=MinecraftClient.getInstance();var s=CombatBridge.snapshot();var pose=BridgeClient.state();
        if(!BridgeClient.postureHudVisible() || !BridgeClient.active() || c.player==null || c.options.hudHidden || c.player.isSpectator() ||
           s==null || pose==null || s.epoch()!=pose.epoch() || !Protocol.fresh(NativeBridge.clockMs(),s.tick()))return;
        int x=c.getWindow().getScaledWidth()/2-50,y=c.getWindow().getScaledHeight()-62;
        if(!c.player.isCreative() && s.maxPosture()>0)
            gauge(draw,x,y,CombatProtocol.postureRatio(s.posture(),s.maxPosture()));
        if(c.crosshairTarget instanceof EntityHitResult hit && hit.getEntity() instanceof NativeActorProxy){
            var pos=hit.getEntity().getPos();CombatProtocol.Actor target=null;double nearest=.25;
            for(var actor:s.actors()){
                double dx=pose.mcX(actor.x())-pos.x,dy=pose.mcY(actor.y())-pos.y,dz=pose.mcZ(actor.z())-pos.z;
                double distance=dx*dx+dy*dy+dz*dz;
                if(distance<nearest){target=actor;nearest=distance;}
            }
            if(target!=null && target.maxPosture()>0){
                int top=c.getWindow().getScaledHeight()/2+23;
                gauge(draw,x,top,CombatProtocol.postureRatio(target.posture(),target.maxPosture()));
                // Zero remaining posture alone is insufficient to prove that a
                // scripted boss currently accepts a deathblow.
            }
        }
    }
}
