package dev.sekirobridge;
import net.fabricmc.api.ModInitializer;
/** Entity types and attributes must be registered before vanilla freezes registries. */
public final class BridgeCommon implements ModInitializer {
    @Override public void onInitialize(){CombatBridge.registerEntities();}
}
