package dev.sekirobridge;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.gl.Framebuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import java.nio.ByteBuffer;

public final class FrameExporter implements AutoCloseable {
    private static final class Slot {
        int pbo;
        long fence;
        int width, height;
        Protocol.State pose;
    }
    private final Slot[] slots = {new Slot(), new Slot(), new Slot()};
    private Slot current;
    private SimpleFramebuffer scaled;
    private int width, height;
    private long sequence;
    public long published, dropped;
    private static final class GLState implements AutoCloseable {
        final int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING),
                  draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        final int pack = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING),
                  alignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        final int rowLength = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH),
                  skipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS),
                  skipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
        GLState() {
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
        }
        public void close() {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pack);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, alignment);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, rowLength);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, skipRows);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, skipPixels);
        }
    }
    private void poll() {
        for (var s : slots)
            if (s.fence != 0) {
                int result = GL32.glClientWaitSync(s.fence, 0, 0); // zero timeout, never glFinish
                if (result == GL32.GL_TIMEOUT_EXPIRED)
                    continue;
                if (result != GL32.GL_WAIT_FAILED && BridgeClient.active() &&
                    s.pose.epoch() == BridgeClient.state().epoch()) {
                    GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, s.pbo);
                    ByteBuffer mapped = GL30.glMapBufferRange(
                        GL21.GL_PIXEL_PACK_BUFFER, 0, (long)s.width * s.height * 12, GL30.GL_MAP_READ_BIT);
                    if (mapped != null) {
                        try {
                            if (NativeBridge.publish(BridgeClient.handle(),
                                                     Protocol.metadata(s.pose, ++sequence, s.width, s.height),
                                                     mapped))
                                published++;
                            else
                                dropped++;
                        } finally {
                            GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
                        }
                    } else
                        dropped++;
                }
                GL32.glDeleteSync(s.fence);
                s.fence = 0;
                s.pose = null;
            }
    }
    private void resize(int w, int h) {
        if (w == width && h == height && scaled != null)
            return;
        discard();
        if (scaled != null)
            scaled.delete();
        scaled = new SimpleFramebuffer(w, h, true, MinecraftClient.IS_SYSTEM_MAC);
        width = w;
        height = h;
        for (var s : slots) {
            if (s.pbo == 0)
                s.pbo = GL15.glGenBuffers();
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, s.pbo);
            GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, (long)w * h * 12, GL15.GL_STREAM_READ);
        }
    }
    private void copy(Framebuffer source, boolean depth) {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source.fbo);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, scaled.fbo);
        GL30.glBlitFramebuffer(0, 0, source.textureWidth, source.textureHeight, 0, 0, width, height,
                               GL11.GL_COLOR_BUFFER_BIT | (depth ? GL11.GL_DEPTH_BUFFER_BIT : 0),
                               GL11.GL_NEAREST);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, scaled.fbo);
    }
    public void world() {
        if (!BridgeClient.active())
            return;
        try (var restore = new GLState()) {
            poll();
            var s = BridgeClient.state();
            resize(s.width(), s.height());
            if (current != null) {
                current.pose = null;
                current = null;
            }
            for (var slot : slots)
                if (slot.fence == 0) {
                    current = slot;
                    break;
                }
            if (current == null) {
                dropped++;
                return;
            }
            current.pose = s;
            current.width = width;
            current.height = height;
            var framebuffer = MinecraftClient.getInstance().getFramebuffer();
            copy(framebuffer, true);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, current.pbo);
            long plane = (long)width * height * 4;
            GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
            GL11.glReadPixels(0, 0, width, height, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, plane);
            // Hands, HUD and screens now render onto transparent pixels, independently of world depth.
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer.fbo);
            GL11.glClearColor(0, 0, 0, 0);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        }
    }
    public void overlay() {
        if (current == null)
            return;
        try (var restore = new GLState()) {
            if (!BridgeClient.active()) {
                current.pose = null;
                current = null;
                return;
            }
            copy(MinecraftClient.getInstance().getFramebuffer(), false);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, current.pbo);
            GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE,
                              (long)width * height * 8);
            current.fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            GL11.glFlush();
            current = null;
        }
    }
    public void discard() {
        current = null;
        for (var slot : slots) {
            if (slot.fence != 0) {
                GL32.glDeleteSync(slot.fence);
                slot.fence = 0;
            }
            slot.pose = null;
        }
    }
    public void close() {
        discard();
        if (scaled != null) {
            scaled.delete();
            scaled = null;
        }
        for (var s : slots)
            if (s.pbo != 0) {
                GL15.glDeleteBuffers(s.pbo);
                s.pbo = 0;
            }
    }
}
