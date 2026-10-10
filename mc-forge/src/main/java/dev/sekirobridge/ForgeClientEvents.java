package dev.sekirobridge;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/** Mod-bus registration and runtime-bus ticks have distinct lifetimes in Forge. */
public final class ForgeClientEvents {
    private ForgeClientEvents() {}

    public static void register() {
        var modBus = FMLJavaModLoadingContext.get().getModEventBus();
        CombatBridge.ENTITIES.register(modBus);
        modBus.addListener(CombatBridge::registerAttributes);
        modBus.addListener(CombatBridge::registerRenderers);
        modBus.addListener(ForgeClientEvents::setup);
        var bus = MinecraftForge.EVENT_BUS;
        bus.addListener(BridgeClient::registerCommands);
        bus.addListener(ForgeClientEvents::clientTick);
        bus.addListener(ForgeClientEvents::serverTick);
        bus.addListener(ForgeClientEvents::serverStopping);
        bus.addListener(ForgeClientEvents::guiRendered);
        bus.addListener(ForgeClientEvents::overlayRendering);
    }

    private static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(BridgeClient::initialize);
    }

    private static void clientTick(TickEvent.ClientTickEvent event) {
        BridgeClient.clientTick(event.phase == TickEvent.Phase.END);
    }

    private static void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) BridgeClient.serverTick(event.getServer());
    }

    private static void serverStopping(ServerStoppingEvent event) {
        BridgeClient.serverStopping(event.getServer());
    }

    private static void guiRendered(RenderGuiEvent.Post event) {
        // ForgeGui replaces vanilla InGameHud.render; its Post event is authoritative.
        CombatHud.render(event.getGuiGraphics());
    }

    private static void overlayRendering(RenderGuiOverlayEvent.Pre event) {
        if (BridgeClient.active() && event.getOverlay().id().equals(VanillaGuiOverlay.VIGNETTE.id())) {
            event.setCanceled(true);
        }
    }
}
