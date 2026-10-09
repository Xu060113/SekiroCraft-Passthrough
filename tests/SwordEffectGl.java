package dev.sekirobridge;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import org.lwjgl.BufferUtils;
import java.nio.*;
import java.nio.file.*;

/** Real GL rendering + production depth/color export; never launches either game. */
public final class SwordEffectGl {
    private static int checks, program, vao;
    private static void require(boolean pass, String message) {
        checks++;
        if (!pass) throw new AssertionError(message);
    }
    private static int shader(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source); GL20.glCompileShader(shader);
        require(GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) != 0, GL20.glGetShaderInfoLog(shader));
        return shader;
    }
    private static void draw(float z, float textureAlpha, float vertexAlpha) {
        GL20.glUseProgram(program);
        GL20.glUniform1f(GL20.glGetUniformLocation(program, "z"), z);
        GL20.glUniform1f(GL20.glGetUniformLocation(program, "textureAlpha"), textureAlpha);
        GL20.glUniform1f(GL20.glGetUniformLocation(program, "vertexAlpha"), vertexAlpha);
        GL30.glBindVertexArray(vao); GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
    }
    private static ByteBuffer readColor() {
        var color = BufferUtils.createByteBuffer(32 * 32 * 4);
        GL11.glReadPixels(0, 0, 32, 32, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, color);
        return color;
    }
    private static float readDepth() {
        var depth = BufferUtils.createFloatBuffer(1);
        GL11.glReadPixels(16, 16, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depth);
        return depth.get(0);
    }
    private static void clear(float red, float alpha, float depth) {
        GL11.glDepthMask(true); GL11.glClearColor(red, 0, 0, alpha); GL11.glClearDepth(depth);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glDepthMask(false);
        GL11.glEnable(GL11.GL_BLEND);
        // SlashBlade LIGHTNING_ADDITIVE_TRANSPARENCY, including its old alpha overwrite.
        GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ZERO);
        GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
    }
    private static void restored() {
        require(!GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK), "color-only depth mask restored");
        require(GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA) == GL11.GL_ONE &&
            GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA) == GL11.GL_ZERO,
            "original alpha blend restored before layer end");
        require(GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB) == GL11.GL_SRC_ALPHA &&
            GL11.glGetInteger(GL14.GL_BLEND_DST_RGB) == GL11.GL_ONE, "RGB blend restored");
    }
    private static void regression(Path fixture) throws Exception {
        int fbo = GL30.glGenFramebuffers(), color = GL11.glGenTextures(), depth = GL11.glGenTextures();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, color);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 32, 32, 0,
            GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer)null);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, color, 0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depth);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH32F_STENCIL8, 32, 32, 0,
            GL30.GL_DEPTH_STENCIL, GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV, (ByteBuffer)null);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depth, 0);
        require(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE,
            "packed-depth source complete");
        GL11.glViewport(0, 0, 32, 32); GL11.glEnable(GL11.GL_DEPTH_TEST); GL11.glDepthFunc(GL11.GL_LEQUAL);
        float d = (100f - 100f * .05f / 3) / (100f - .05f), z = d * 2 - 1;
        clear(0, 0, 1);
        draw(z, 1, .5f);
        require(readColor().get(0) != 0 && readDepth() == 1, "original glow has RGB but no exportable depth");
        for (var name : new String[]{"slashblade_blend_luminous_slashblade:model/util/ss.png",
                "slashblade_blend_luminous_depth_write_slashblade:model/util/ss.png",
                "slashblade_blend_write_color_slashblade:model/util/slash.png",
                "slashblade_charge_effect_slashblade:model/blade.png_0.0_0.0"}) {
            clear(0, 0, 1);
            try (var scope = SwordEffectCapture.begin(true, name)) {
                require(scope != null, "installed additive render layer selected: " + name);
                SwordEffectCapture.beforeShaderBind();
                // The shader JSON changes blending after startDrawing; emulate that exact ordering.
                GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE, GL11.GL_ZERO);
                SwordEffectCapture.shaderBound();
                draw(z, 1, .5f);
                require(Math.abs(readDepth() - d) < .000001f, "glow writes the actual fragment depth");
                var rgb = readColor();
                require(Math.abs(Byte.toUnsignedInt(rgb.get(0)) - 102) <= 1 &&
                    Math.abs(Byte.toUnsignedInt(rgb.get(1)) - 64) <= 1 && rgb.get(3) == 0,
                    "premultiplied emission keeps zero opaque coverage: " + Byte.toUnsignedInt(rgb.get(0)) + "," +
                    Byte.toUnsignedInt(rgb.get(1)) + "," + Byte.toUnsignedInt(rgb.get(3)));
                SwordEffectCapture.beforeShaderBind();
                require(!GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK), "rebind restores before shader cached state applies");
                // Return to the original layer state before the second bind.
                GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ZERO);
                SwordEffectCapture.shaderBound();
            }
            restored();
        }
        clear(.2f, 1, 1);
        try (var scope = SwordEffectCapture.begin(true, "slashblade_blend_luminous_fx")) {
            SwordEffectCapture.shaderBound(); draw(z, 1, .25f);
        }
        require(Byte.toUnsignedInt(readColor().get(3)) == 255, "glow cannot erase coverage of an MC body underneath");
        restored();
        clear(0, 0, 1);
        try (var scope = SwordEffectCapture.begin(true, "slashblade_blend_luminous_fx")) {
            SwordEffectCapture.shaderBound(); draw(z, .05f, 1);
        }
        require(readDepth() == 1 && readColor().get(0) == 0, "discarded transparent texture texels do not stamp depth");
        clear(.2f, 1, .1f);
        try (var scope = SwordEffectCapture.begin(true, "slashblade_blend_luminous_fx")) {
            SwordEffectCapture.shaderBound(); draw(z, 1, .5f);
        }
        require(Byte.toUnsignedInt(readColor().get(0)) == 51 && Math.abs(readDepth() - .1f) < .000001f,
            "MC geometry in front still occludes sword glow");
        clear(0, 0, 1);
        for (var name : new String[]{"entity_translucent", "slashblade_blend_reverse_luminous_fx", "slashblade_blend_fx"}) {
            require(SwordEffectCapture.begin(true, name) == null, "unrelated or subtractive render layer unchanged");
            SwordEffectCapture.shaderBound(); restored();
        }
        require(SwordEffectCapture.begin(false, "slashblade_blend_luminous_fx") == null, "standalone MC unchanged");
        try {
            try (var scope = SwordEffectCapture.begin(true, "slashblade_blend_luminous_fx")) {
                SwordEffectCapture.shaderBound(); throw new IllegalStateException("fixture");
            }
        } catch (IllegalStateException expected) { restored(); }
        // Export actual GL emission + fragment depth to exercise native D3D composition.
        clear(0, 0, 1);
        try (var scope = SwordEffectCapture.begin(true, "slashblade_blend_luminous_fx")) {
            SwordEffectCapture.shaderBound(); draw(z, 1, .5f);
        }
        try (var capture = new FrameCaptureTarget()) {
            require(capture.copy(fbo, 32, 32, 32, 32, true), "production FBO copy exports additive sword effect");
            var bytes = BufferUtils.createByteBuffer(32 * 32 * 8);
            GL11.glReadPixels(0, 0, 32, 32, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, bytes.slice(0, 32 * 32 * 4));
            GL11.glReadPixels(0, 0, 32, 32, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, bytes.slice(32 * 32 * 4, 32 * 32 * 4));
            byte[] data = new byte[bytes.capacity()]; bytes.get(data); Files.write(fixture, data);
        }
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "no GL errors across export and state restoration");
        GL30.glDeleteFramebuffers(fbo); GL11.glDeleteTextures(color); GL11.glDeleteTextures(depth);
    }
    public static void main(String[] args) throws Exception {
        if (!GLFW.glfwInit()) throw new IllegalStateException("GLFW init failed");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3); GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(32, 32, "Sword effect export regression (hidden)", 0, 0);
        if (window == 0) throw new IllegalStateException("Hidden GL context unavailable");
        try {
            GLFW.glfwMakeContextCurrent(window); GL.createCapabilities();
            int vertex = shader(GL20.GL_VERTEX_SHADER, """
                #version 150
                uniform float z;
                void main() { vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                    gl_Position = vec4(p * 2.0 - 1.0, z, 1.0); }
                """);
            int fragment = shader(GL20.GL_FRAGMENT_SHADER, """
                #version 150
                uniform float textureAlpha, vertexAlpha;
                out vec4 fragColor;
                void main() { if (textureAlpha < 0.1) discard;
                    fragColor = vec4(0.8, 0.5, 0.0, textureAlpha * vertexAlpha); }
                """);
            program = GL20.glCreateProgram(); GL20.glAttachShader(program, vertex); GL20.glAttachShader(program, fragment);
            GL20.glLinkProgram(program); require(GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) != 0, "link effect shader");
            vao = GL30.glGenVertexArrays(); regression(Path.of(args[0]));
            GL30.glDeleteVertexArrays(vao); GL20.glDeleteProgram(program); GL20.glDeleteShader(vertex); GL20.glDeleteShader(fragment);
            System.out.println(checks + " sword-effect GL capture checks passed; no game launched");
        } finally { GLFW.glfwDestroyWindow(window); GLFW.glfwTerminate(); }
    }
}
