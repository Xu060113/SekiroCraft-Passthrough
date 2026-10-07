package dev.sekirobridge;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import org.lwjgl.BufferUtils;
import java.nio.*;

/** Exercises the production copy path on a hidden GL context, without launching Minecraft. */
public final class FrameCaptureGl {
    private static int checks;
    private static void require(boolean pass, String message) {
        checks++;
        if (!pass) throw new AssertionError(message);
    }
    private static void noError(String message) {
        int error = GL11.glGetError();
        require(error == GL11.GL_NO_ERROR, message + ": GL error " + error);
    }
    private static final class Source implements AutoCloseable {
        final int fbo = GL30.glGenFramebuffers(), color = GL11.glGenTextures();
        int depth;
        final int w, h;
        final boolean renderbuffer;
        Source(int w, int h, int format, boolean renderbuffer, int samples) {
            this.w = w; this.h = h; this.renderbuffer = renderbuffer;
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            if (samples == 0) {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, color);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer)null);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, color, 0);
            } else {
                GL11.glBindTexture(GL32.GL_TEXTURE_2D_MULTISAMPLE, color);
                GL32.glTexImage2DMultisample(GL32.GL_TEXTURE_2D_MULTISAMPLE, samples, GL11.GL_RGBA8, w, h, true);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL32.GL_TEXTURE_2D_MULTISAMPLE, color, 0);
            }
            replaceDepth(format, samples);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            require(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE,
                "source complete for " + format);
        }
        void replaceDepth(int format, int samples) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            if (renderbuffer) {
                if (depth == 0) depth = GL30.glGenRenderbuffers();
                GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depth);
                if (samples == 0) GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, format, w, h);
                else GL30.glRenderbufferStorageMultisample(GL30.GL_RENDERBUFFER, samples, format, w, h);
                GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                    GL30.GL_RENDERBUFFER, depth);
            } else {
                if (depth == 0) depth = GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, depth);
                boolean packed = format == GL30.GL_DEPTH24_STENCIL8 || format == GL30.GL_DEPTH32F_STENCIL8;
                int type = format == GL30.GL_DEPTH24_STENCIL8 ? GL30.GL_UNSIGNED_INT_24_8 :
                    format == GL30.GL_DEPTH32F_STENCIL8 ? GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV : GL11.GL_FLOAT;
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, format, w, h, 0,
                    packed ? GL30.GL_DEPTH_STENCIL : GL11.GL_DEPTH_COMPONENT, type, (ByteBuffer)null);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                    GL11.GL_TEXTURE_2D, depth, 0);
            }
        }
        void world(boolean front) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glClearDepth(1); GL11.glClearColor(0, 0, 0, 0);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(front ? w / 2 : 0, 0, w / 2, h);
            GL11.glClearDepth(.2); GL11.glClearColor(1, .25f, 0, 1);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
        void overlay() {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL11.glClearColor(0, .5f, 1, .5f); GL11.glClearDepth(.7);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        }
        public void close() {
            GL30.glDeleteFramebuffers(fbo); GL11.glDeleteTextures(color);
            if (renderbuffer) GL30.glDeleteRenderbuffers(depth); else GL11.glDeleteTextures(depth);
        }
    }
    private static void planes(FrameCaptureTarget target, Source source, int w, int h, boolean front) {
        source.world(front);
        int pbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo);
        int plane = w * h * 4;
        GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, plane * 3L, GL15.GL_STREAM_READ);
        require(target.copy(source.fbo, source.w, source.h, w, h, true), "world copy accepted");
        GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
        GL11.glReadPixels(0, 0, w, h, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (long)plane);
        source.overlay();
        require(target.copy(source.fbo, source.w, source.h, w, h, false), "overlay copy accepted");
        GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 2L * plane);
        noError("world/depth/overlay capture");
        // Blocking map is confined to this fixture; production uses zero-timeout fences.
        ByteBuffer bytes = GL30.glMapBufferRange(GL21.GL_PIXEL_PACK_BUFFER, 0, 3L * plane, GL30.GL_MAP_READ_BIT);
        require(bytes != null, "map captured planes");
        bytes.order(ByteOrder.nativeOrder());
        int near = (h / 2 * w + (front ? w * 3 / 4 : w / 4)) * 4;
        int far = (h / 2 * w + (front ? w / 4 : w * 3 / 4)) * 4;
        require((bytes.get(near) & 255) == 255 && (bytes.get(near + 3) & 255) == 255, "third-person world silhouette retained");
        require((bytes.get(far + 3) & 255) == 0, "transparent world background retained");
        require(Math.abs(bytes.getFloat(plane + near) - .2f) < .0001f, "visible world depth retained");
        require(Math.abs(bytes.getFloat(plane + far) - 1f) < .0001f, "empty world depth retained");
        require((bytes.get(2 * plane + near + 2) & 255) == 255 &&
            Math.abs((bytes.get(2 * plane + near + 3) & 255) - 128) <= 1, "HUD plane remains independent");
        GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0); GL15.glDeleteBuffers(pbo);
    }
    private static void regression() {
        try (var source = new Source(8, 6, GL30.GL_DEPTH32F_STENCIL8, false, 0);
             var vanillaTarget = new Source(4, 4, GL11.GL_DEPTH_COMPONENT, false, 0)) {
            source.world(false);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source.fbo);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, vanillaTarget.fbo);
            GL30.glBlitFramebuffer(0, 0, 8, 6, 0, 0, 4, 4,
                GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
            require(GL11.glGetError() == GL11.GL_INVALID_OPERATION, "old TaCZ/vanilla format mismatch reproduced");
            try (var target = new FrameCaptureTarget()) { planes(target, source, 4, 4, false); }
        }
    }
    private static void formats() {
        int[] formats = {GL11.GL_DEPTH_COMPONENT, GL14.GL_DEPTH_COMPONENT16, GL14.GL_DEPTH_COMPONENT24,
            GL14.GL_DEPTH_COMPONENT32, GL30.GL_DEPTH_COMPONENT32F, GL30.GL_DEPTH24_STENCIL8, GL30.GL_DEPTH32F_STENCIL8};
        try (var target = new FrameCaptureTarget()) {
            for (int format : formats) {
                try (var source = new Source(16, 12, format, false, 0)) {
                    planes(target, source, 8, 6, false);
                    planes(target, source, 8, 6, true);
                    planes(target, source, 12, 8, false);
                }
                if (format != GL11.GL_DEPTH_COMPONENT)
                    try (var source = new Source(16, 12, format, true, 0)) {
                        planes(target, source, 8, 6, true);
                    }
            }
            try (var source = new Source(16, 12, GL14.GL_DEPTH_COMPONENT24, false, 0)) {
                planes(target, source, 8, 6, false);
                // Same FBO, texture ID and output dimensions, changed by a mod at runtime.
                source.replaceDepth(GL30.GL_DEPTH32F_STENCIL8, 0);
                planes(target, source, 8, 6, true);
                source.replaceDepth(GL14.GL_DEPTH_COMPONENT24, 0);
                planes(target, source, 8, 6, false);
            }
            target.close();
            try (var source = new Source(16, 12, GL30.GL_DEPTH32F_STENCIL8, false, 0)) {
                planes(target, source, 8, 6, true);
            }
            try (var source = new Source(16, 12, GL14.GL_DEPTH_COMPONENT24, true, 4)) {
                require(!target.copy(source.fbo, source.w, source.h, 8, 6, true), "multisample resize rejected safely");
                noError("unsupported multisample capture");
            }
        }
    }
    private static void state() {
        try (var source = new Source(16, 12, GL30.GL_DEPTH32F_STENCIL8, false, 0);
             var other = new Source(4, 4, GL14.GL_DEPTH_COMPONENT24, true, 0);
             var target = new FrameCaptureTarget()) {
            source.world(false);
            int pack = GL15.glGenBuffers(), unpack = GL15.glGenBuffers(), texture = GL11.glGenTextures();
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pack);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpack);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, other.depth);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, other.fbo);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, source.fbo);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 8);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 19);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 2);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 3);
            try (var restore = new FrameExporter.GLState()) {
                require(target.copy(source.fbo, 16, 12, 8, 6, true), "copy with unrelated GL bindings");
                require(GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH) == 0, "capture has tightly packed rows");
            }
            require(GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING) == other.fbo, "read FBO restored");
            require(GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) == source.fbo, "draw FBO restored");
            require(GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING) == pack, "pack PBO restored");
            require(GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING) == unpack, "unpack PBO restored");
            require(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) == texture, "texture restored");
            require(GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING) == other.depth, "renderbuffer restored");
            require(GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT) == 8 && GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH) == 19 &&
                GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS) == 2 && GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS) == 3,
                "pixel pack layout restored");
            noError("GL state restoration and null allocation with bound unpack PBO");
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0); GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            GL15.glDeleteBuffers(pack); GL15.glDeleteBuffers(unpack); GL11.glDeleteTextures(texture);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4); GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0); GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
        }
    }
    public static void main(String[] args) {
        if (!GLFW.glfwInit()) throw new IllegalStateException("GLFW initialization failed");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(32, 32, "Frame capture regression (hidden)", 0, 0);
        if (window == 0) throw new IllegalStateException("Hidden GL context unavailable");
        try {
            GLFW.glfwMakeContextCurrent(window); GL.createCapabilities();
            System.out.println("Hidden GL fixture: " + GL11.glGetString(GL11.GL_RENDERER));
            regression(); formats(); state(); noError("final GL state");
            System.out.println("Frame capture GL checks: " + checks + " passed; no game launched");
        } finally { GLFW.glfwDestroyWindow(window); GLFW.glfwTerminate(); }
    }
}
