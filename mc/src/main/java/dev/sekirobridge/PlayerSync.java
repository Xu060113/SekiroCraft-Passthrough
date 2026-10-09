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
    private volatile boolean cinematicPositionPending;
    private long clientLoadRevision,serverLoadRevision;
    private Object heldClient;
    private boolean clientGravity,serverHeld,serverGravity;
    void client(Protocol.State s){
        var p=MinecraftClient.getInstance().player;if(p==null)return;
        if(BridgeClient.loading()){
            if(heldClient!=p){restoreClientGravity();heldClient=p;clientGravity=p.hasNoGravity();}
            p.setNoGravity(true);p.setVelocity(Vec3d.ZERO);p.fallDistance=0;return;
        }
        restoreClientGravity();
        boolean resumed=clientLoadRevision!=BridgeClient.loadRevision();
        boolean handoff=(s.flags()&(Protocol.NATIVE_ACTION|Protocol.NATIVE_DEAD|Protocol.NATIVE_UI|Protocol.NATIVE_CINEMATIC))!=0;
        if((s.flags()&Protocol.NATIVE_CINEMATIC)!=0)cinematicPositionPending=true;
        boolean initial=clientIdentity!=p || clientEpoch!=s.epoch();
        if(!initial && !handoff && !clientHandoff && !resumed)return;
        clientLoadRevision=BridgeClient.loadRevision();
        clientHandoff=handoff;
        clientIdentity=p;clientEpoch=s.epoch();
        // Seed once; vanilla owns movement, gravity, rotation and animation afterwards.
        p.setPosition(s.mcX(s.px()),s.mcY(s.py()),s.mcZ(s.pz()));
        p.prevX=p.lastRenderX=p.getX();p.prevY=p.lastRenderY=p.getY();p.prevZ=p.lastRenderZ=p.getZ();
        if(initial){p.setYaw(s.yaw());p.setPitch(s.pitch());p.prevYaw=p.getYaw();p.prevPitch=p.getPitch();}
        if(initial || resumed)NativeTerrain.seed(s,p.getPos());
        p.setVelocity(Vec3d.ZERO);p.fallDistance=0;
    }
    void server(MinecraftServer server,Protocol.State s){
        if(s==null){release();return;}
        var client=MinecraftClient.getInstance();if(client.player==null){release();return;}
        var p=server.getPlayerManager().getPlayer(client.player.getUuid());if(p==null){release();return;}
        if(BridgeClient.loading()){
            // Keep the existing ownership and position. Never teleport to an
            // unloaded/stale native pose or release into the dedicated void world.
            if(owned==p){if(!serverHeld){serverGravity=p.hasNoGravity();serverHeld=true;}
                p.setNoGravity(true);p.setVelocity(Vec3d.ZERO);p.fallDistance=0;}
            return;
        }
        restoreServerGravity();
        boolean resumed=serverLoadRevision!=BridgeClient.loadRevision();
        if(owned!=p || epoch!=s.epoch()){
            release();owned=p;epoch=s.epoch();command=s.command();oldGravity=p.hasNoGravity();oldClip=p.noClip;
            originalPosition=p.getPos();originalYaw=p.getYaw();originalPitch=p.getPitch();
            p.setNoGravity(false);p.noClip=false;
            p.teleport(p.getServerWorld(),s.mcX(s.px()),s.mcY(s.py()),s.mcZ(s.pz()),s.yaw(),s.pitch());p.setVelocity(Vec3d.ZERO);
        }
        boolean handoff=(s.flags()&(Protocol.NATIVE_ACTION|Protocol.NATIVE_DEAD|Protocol.NATIVE_UI|Protocol.NATIVE_CINEMATIC))!=0;
        if(handoff || serverHandoff || cinematicPositionPending || resumed){
            // The server's last received yaw may lag the live client camera.
            // Absolute position + zero relative rotation preserves that camera.
            p.networkHandler.requestTeleport(s.mcX(s.px()),s.mcY(s.py()),s.mcZ(s.pz()),0,0,
                net.minecraft.network.packet.s2c.play.PositionFlag.ROT);
            p.setVelocity(Vec3d.ZERO);p.fallDistance=0;
        }
        serverHandoff=handoff;
        serverLoadRevision=BridgeClient.loadRevision();
        if((s.flags()&Protocol.NATIVE_CINEMATIC)!=0){command=s.command();return;}
        cinematicPositionPending=false;
        if(command!=s.command()){
            command=s.command();BlockPos target=BlockPos.ofFloored(p.getEyePos().add(p.getRotationVec(1).multiply(4)));
            if(p.getServerWorld().isInBuildLimit(target) && p.getServerWorld().isAir(target) &&
               !p.getBoundingBox().intersects(new net.minecraft.util.math.Box(target)))
                p.getServerWorld().setBlockState(target,Blocks.GRASS_BLOCK.getDefaultState());
        }
    }
    private void restoreClientGravity(){if(heldClient instanceof net.minecraft.entity.Entity p)p.setNoGravity(clientGravity);heldClient=null;}
    private void restoreServerGravity(){if(serverHeld && owned!=null)owned.setNoGravity(serverGravity);serverHeld=false;}
    void reset(){restoreClientGravity();clientIdentity=null;clientEpoch=0;clientHandoff=false;cinematicPositionPending=false;NativeTerrain.clear();}
    private void release(){restoreServerGravity();if(owned!=null){owned.setNoGravity(oldGravity);owned.noClip=oldClip;owned.setVelocity(Vec3d.ZERO);
        if(owned.isAlive())owned.teleport(owned.getServerWorld(),originalPosition.x,originalPosition.y,originalPosition.z,originalYaw,originalPitch);owned=null;}serverHandoff=false;}
}
