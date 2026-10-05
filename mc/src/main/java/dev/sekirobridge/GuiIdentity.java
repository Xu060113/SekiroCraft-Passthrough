package dev.sekirobridge;

import net.minecraft.client.MinecraftClient;

/** A generation belongs to one Screen instance and its exact coordinate geometry. */
final class GuiIdentity {
    private static Object screen;
    private static int width,height,scaledWidth,scaledHeight;
    private static long next,generation;
    static long current(){
        var c=MinecraftClient.getInstance();var w=c.getWindow();
        if(c.currentScreen!=screen || width!=w.getWidth() || height!=w.getHeight() ||
            scaledWidth!=w.getScaledWidth() || scaledHeight!=w.getScaledHeight()){
            screen=c.currentScreen;width=w.getWidth();height=w.getHeight();
            scaledWidth=w.getScaledWidth();scaledHeight=w.getScaledHeight();generation=++next;
        }
        return screen==null?0:generation;
    }
    private GuiIdentity(){}
}
