package dev.sekirobridge;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;

/** Scoped export compatibility for SlashBlade's color-only, additive render layers. */
public final class SwordEffectCapture implements AutoCloseable {
    private static final ThreadLocal<SwordEffectCapture> CURRENT = new ThreadLocal<>();
    private final SwordEffectCapture previous;
    private boolean applied;
    private boolean depthMask, blend;
    private int srcRgb, dstRgb, srcAlpha, dstAlpha, equationRgb, equationAlpha;

    private SwordEffectCapture() {
        previous = CURRENT.get();
        CURRENT.set(this);
    }

    public static boolean supports(String name) {
        return name != null && (name.startsWith("slashblade_blend_luminous_") ||
            name.startsWith("slashblade_blend_write_color_") ||
            name.startsWith("slashblade_charge_effect_"));
    }

    public static SwordEffectCapture begin(boolean bridgeActive, String name) {
        return bridgeActive && supports(name) ? new SwordEffectCapture() : null;
    }

    // ShaderProgram.bind applies its JSON blend state after RenderLayer.startDrawing.
    // Apply here, then restore before endDrawing so Minecraft's cached GL state
    // remains consistent. No render-type cache or third-party classes are changed.
    public static void shaderBound() {
        var scope = CURRENT.get();
        if (scope == null) return;
        scope.depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        scope.blend = GL11.glIsEnabled(GL11.GL_BLEND);
        scope.srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        scope.dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        scope.srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        scope.dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        scope.equationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
        scope.equationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        scope.applied = true;
        GL11.glDepthMask(true);
        GL11.glEnable(GL11.GL_BLEND);
        GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
        // RGB already contains the weighted emission; alpha is opaque MC coverage,
        // not glow opacity. Preserve it so glow adds to native pixels and cannot
        // punch transparent holes into MC bodies or blocks underneath it.
        GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE);
    }

    public static void beforeShaderBind() {
        var scope = CURRENT.get();
        if (scope != null && scope.applied) scope.restore();
    }

    private void restore() {
        GL11.glDepthMask(depthMask);
        GL14.glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
        GL20.glBlendEquationSeparate(equationRgb, equationAlpha);
        if (blend) GL11.glEnable(GL11.GL_BLEND); else GL11.glDisable(GL11.GL_BLEND);
        applied = false;
    }

    public void close() {
        if (applied) restore();
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }
}
