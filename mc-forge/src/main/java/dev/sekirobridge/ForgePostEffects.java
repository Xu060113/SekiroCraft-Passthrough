package dev.sekirobridge;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import net.minecraftforge.fml.ModList;

/** Optional VIX integration; the original processor, effects and assets are retained. */
public final class ForgePostEffects {
    private static final PostEffectOrder ORDER = new PostEffectOrder();
    private static boolean resolved, failed;
    private static Method render;
    private ForgePostEffects() {}
    public static void beginFrame() { ORDER.beginFrame(); }
    public static void beforeWorldCapture() {
        if (!BridgeClient.active() || failed) return;
        if (!resolved) {
            resolved = true;
            if (!ModList.get().isLoaded("vix")) return;
            try {
                render = Class.forName("com.guhao.vix.client.pipeline.PostEffectPipelines")
                    .getMethod("RenderPost");
                BridgeClient.LOG.info("VIX world post effects integrated before bridge capture");
            } catch (ReflectiveOperationException e) {
                failed = true;
                BridgeClient.LOG.error("VIX post-effect adapter unavailable; report this mod version", e);
            }
        }
        if (render == null) return;
        try {
            ORDER.beforeCapture(() -> {
                try { render.invoke(null); }
                catch (IllegalAccessException | InvocationTargetException e) {
                    throw new IllegalStateException("VIX original post pass failed", e);
                }
            });
        } catch (RuntimeException e) {
            failed = true;
            BridgeClient.LOG.error("VIX original post pass failed; adapter disabled", e);
        }
    }
    public static boolean deferLatePass() { return ORDER.deferLatePass(BridgeClient.active()); }
}
