package dev.sekirobridge;
import net.minecraft.client.MinecraftClient;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

final class PlayerSync {
    private ServerPlayerEntity owned;
    private Vec3d originalPosition;
    private float originalYaw,originalPitch;
    private boolean oldGravity,oldClip;
    private int command;
    private long epoch;
    private Object clientIdentity;
    private long clientEpoch;
    private boolean clientHandoff,serverHandoff;
    void client(Protocol.State s){
        var p=MinecraftClient.getInstance().player;if(p==null)return;
        boolean handoff=(s.flags()&(Protocol.NATIVE_ACTION|Protocol.NATIVE_DEAD))!=0;
        boolean initial=clientIdentity!=p || clientEpoch!=s.epoch();
        if(!initial && !handoff && !clientHandoff)return;
        clientHandoff=handoff;
        clientIdentity=p;clientEpoch=s.epoch();
        // Seed once; vanilla owns movement, gravity, rotation and animation afterwards.
        p.setPosition(s.mcX(s.px()),s.mcY(s.py()),s.mcZ(s.pz()));
        p.prevX=p.lastRenderX=p.getX();p.prevY=p.lastRenderY=p.getY();p.prevZ=p.lastRenderZ=p.getZ();
        if(initial){p.setYaw(s.yaw());p.setPitch(s.pitch());p.prevYaw=p.getYaw();p.prevPitch=p.getPitch();NativeTerrain.seed(s,p.getPos());}
        p.setVelocity(Vec3d.ZERO);p.fallDistance=0;
    }
    void server(MinecraftServer server,Protocol.State s){
        if(s==null){release();return;}
        var client=MinecraftClient.getInstance();if(client.player==null){release();return;}
        var p=server.getPlayerManager().getPlayer(client.player.getUuid());if(p==null){release();return;}
        if(owned!=p || epoch!=s.epoch()){
            release();owned=p;epoch=s.epoch();command=s.command();oldGravity=p.hasNoGravity();oldClip=p.noClip;
            originalPosition=p.getPos();originalYaw=p.getYaw();originalPitch=p.getPitch();
            p.setNoGravity(false);p.noClip=false;
            p.teleport(p.getServerWorld(),s.mcX(s.px()),s.mcY(s.py()),s.mcZ(s.pz()),s.yaw(),s.pitch());p.setVelocity(Vec3d.ZERO);
        }
        boolean handoff=(s.flags()&(Protocol.NATIVE_ACTION|Protocol.NATIVE_DEAD))!=0;
        if(handoff || serverHandoff){
            p.teleport(p.getServerWorld(),s.mcX(s.px()),s.mcY(s.py()),s.mcZ(s.pz()),p.getYaw(),p.getPitch());
            p.setVelocity(Vec3d.ZERO);p.fallDistance=0;
        }
        serverHandoff=handoff;
        if(command!=s.command()){
            command=s.command();BlockPos target=BlockPos.ofFloored(p.getEyePos().add(p.getRotationVec(1).multiply(4)));
            if(p.getServerWorld().isInBuildLimit(target) && p.getServerWorld().isAir(target) &&
               !p.getBoundingBox().intersects(new net.minecraft.util.math.Box(target)))
                p.getServerWorld().setBlockState(target,Blocks.GRASS_BLOCK.getDefaultState());
        }
    }
    void reset(){clientIdentity=null;clientEpoch=0;clientHandoff=false;NativeTerrain.clear();}
    private void release(){if(owned!=null){owned.setNoGravity(oldGravity);owned.noClip=oldClip;owned.setVelocity(Vec3d.ZERO);
        if(owned.isAlive())owned.teleport(owned.getServerWorld(),originalPosition.x,originalPosition.y,originalPosition.z,originalYaw,originalPitch);owned=null;}serverHandoff=false;}
}
