package dev.sekirobridge;

/** A world post pass must run before the world is split from the transparent HUD. */
public final class PostEffectOrder {
    private boolean captured, running;
    public void beginFrame() { captured = false; running = false; }
    public void beforeCapture(Runnable render) {
        if (captured || running) return;
        running = true;
        try { render.run(); captured = true; }
        finally { running = false; }
    }
    public boolean deferLatePass(boolean bridgeActive) {
        return bridgeActive && captured && !running;
    }
}
