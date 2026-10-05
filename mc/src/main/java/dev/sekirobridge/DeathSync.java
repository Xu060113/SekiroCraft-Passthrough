package dev.sekirobridge;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DeathScreen;

/** Native resurrection owns the life boundary; MC respawns only after it. */
public final class DeathSync {
    private static Object requestedPlayer;
    private static long requestAt,sequence;
    private DeathSync(){}
    public static boolean nativeDead(){var s=BridgeClient.state();return BridgeClient.connected() && s!=null && (s.flags()&Protocol.NATIVE_DEAD)!=0;}
    public static void requestNativeRespawn(){
        var s=BridgeClient.state();if(!nativeDead() || !BridgeClient.active())return;
        var packet=Protocol.direct(32);packet.putLong(NativeBridge.clockMs()).putLong(s.epoch()).putLong(++sequence).putInt(1).putInt(0);
        NativeBridge.nativeAction(BridgeClient.handle(),packet);
    }
    static void tick(){
        var c=MinecraftClient.getInstance();var p=c.player;var nativeState=CombatBridge.snapshot();
        if(!BridgeClient.connected() || p==null || p.isAlive()){requestedPlayer=null;return;}
        if(!(c.currentScreen instanceof DeathScreen) || nativeState==null || nativeState.hp()<=0 ||
           nativeState.epoch()!=BridgeClient.state().epoch() || nativeDead() ||
           !Protocol.fresh(NativeBridge.clockMs(),nativeState.tick()) || c.world.getLevelProperties().isHardcore())return;
        long now=NativeBridge.clockMs();
        if(requestedPlayer!=p || now-requestAt>1000){requestedPlayer=p;requestAt=now;p.requestRespawn();}
    }
}
