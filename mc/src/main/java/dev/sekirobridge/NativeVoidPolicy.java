package dev.sekirobridge;

import java.util.UUID;

/** Native ownership, not MC's build height, decides whether a void tick applies. */
public final class NativeVoidPolicy {
    private NativeVoidPolicy(){}
    public record Scope(long epoch,UUID player,Object clientWorld,Object serverWorld) {
        public boolean owns(boolean connected,long currentEpoch,Object world,UUID entity,
                            boolean playerEntity,boolean nativeActor) {
            return connected && epoch!=0 && epoch==currentEpoch && world!=null &&
                (world==clientWorld || world==serverWorld) &&
                (nativeActor || (playerEntity && player!=null && player.equals(entity)));
        }
    }
}
