package dev.sekirobridge;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;

/** Published by the client; also read during integrated-server Entity.baseTick. */
public final class NativeVoidProtection {
    private static volatile NativeVoidPolicy.Scope scope;
    private NativeVoidProtection(){}
    static void refresh(){
        if(!BridgeClient.connected()){clear();return;}
        var client=MinecraftClient.getInstance();var state=BridgeClient.state();
        var serverWorld=client.getServer().getWorld(client.world.getRegistryKey());
        scope=new NativeVoidPolicy.Scope(state.epoch(),client.player.getUuid(),client.world,serverWorld);
    }
    static void clear(){scope=null;}
    public static boolean blocks(Entity entity){
        var current=scope;var state=BridgeClient.state();
        return current!=null && entity!=null && state!=null &&
            current.owns(BridgeClient.connected(),state.epoch(),entity.getWorld(),entity.getUuid(),
                entity instanceof PlayerEntity,entity instanceof NativeActorProxy);
    }
}
