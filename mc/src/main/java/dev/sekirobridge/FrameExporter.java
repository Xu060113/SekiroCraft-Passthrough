package dev.sekirobridge;

import net.minecraft.client.MinecraftClient;
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
        long captureSequence, capturedAt;
        int width, height;
        long guiGeneration;
        int guiWidth,guiHeight;
        Protocol.State pose;
    }
    private final Slot[] slots = {new Slot(), new Slot(), new Slot()};
    private Slot current;
    private final FrameCaptureTarget scaled = new FrameCaptureTarget();
    private boolean reportedUnsupportedDepth;
    private int width, height;
    private long sequence;
    private long captured, lastPublishedCapture;
    public long published, dropped;
    static final class GLState implements AutoCloseable {
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
        Slot newest=null;
        for(var slot:slots)if(slot.fence!=0){
            int r=GL32.glClientWaitSync(slot.fence,0,0);
            if(r==GL32.GL_ALREADY_SIGNALED || r==GL32.GL_CONDITION_SATISFIED)
                if(slot.captureSequence>lastPublishedCapture && (newest==null || slot.captureSequence>newest.captureSequence))newest=slot;
        }
        for (var s : slots)
            if (s.fence != 0) {
                int result = GL32.glClientWaitSync(s.fence, 0, 0); // zero timeout, never glFinish
                if (result == GL32.GL_TIMEOUT_EXPIRED)
                    continue;
                if (s==newest && result != GL32.GL_WAIT_FAILED && BridgeClient.active() &&
                    Protocol.fresh(NativeBridge.clockMs(),s.capturedAt) &&
                    s.pose.epoch() == BridgeClient.state().epoch()) {
                    GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, s.pbo);
                    ByteBuffer mapped = GL30.glMapBufferRange(
                        GL21.GL_PIXEL_PACK_BUFFER, 0, (long)s.width * s.height * 12, GL30.GL_MAP_READ_BIT);
                    if (mapped != null) {
                        try {
                            var metadata=Protocol.metadata(s.pose, ++sequence, s.width, s.height,s.capturedAt);
                            metadata.putLong(96,s.guiGeneration).putInt(104,s.guiWidth).putInt(108,s.guiHeight);
                            metadata.limit(Protocol.META_BYTES);
                            if (NativeBridge.publish(BridgeClient.handle(),
                                                     metadata,
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
                lastPublishedCapture=Math.max(lastPublishedCapture,s.captureSequence);
                s.fence = 0;
                s.pose = null;
            }
    }
    private void resize(int w, int h) {
        if (w == width && h == height)
            return;
        discard();
        width = w;
        height = h;
        for (var s : slots) {
            if (s.pbo == 0)
                s.pbo = GL15.glGenBuffers();
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, s.pbo);
            GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, (long)w * h * 12, GL15.GL_STREAM_READ);
        }
    }
    private boolean copy(Framebuffer source, boolean depth) {
        return scaled.copy(source.fbo, source.textureWidth, source.textureHeight, width, height, depth);
    }
    public void world() {
        if (!BridgeClient.active())
            return;
        try (var restore = new GLState()) {
            poll();
            var s = BridgeClient.renderPose();
            if(s==null)return;
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
            current.captureSequence=++captured;current.capturedAt=NativeBridge.clockMs();
            current.width = width;
            current.height = height;
            var framebuffer = MinecraftClient.getInstance().getFramebuffer();
            if (!copy(framebuffer, true)) {
                current.pose = null;
                current = null;
                dropped++;
                if (!reportedUnsupportedDepth) {
                    BridgeClient.LOG.warn("MC frame capture requires a single-sample 2D depth attachment; frame discarded");
                    reportedUnsupportedDepth = true;
                }
                return;
            }
            reportedUnsupportedDepth = false;
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
            current.guiGeneration=GuiIdentity.current();
            current.guiWidth=MinecraftClient.getInstance().getWindow().getScaledWidth();
            current.guiHeight=MinecraftClient.getInstance().getWindow().getScaledHeight();
            if (!BridgeClient.active()) {
                current.pose = null;
                current = null;
                return;
            }
            if (!copy(MinecraftClient.getInstance().getFramebuffer(), false)) {
                current.pose = null;
                current = null;
                dropped++;
                return;
            }
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, current.pbo);
            GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE,
                              (long)width * height * 8);
            current.fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            GL11.glFlush();
            current = null;
            // Publishing only at the next world pass adds a whole render frame
            // even when this readback has already completed. This is still a
            // zero-timeout fence poll; slow GPU work never stalls the client.
            poll();
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
        scaled.close();
        for (var s : slots)
            if (s.pbo != 0) {
                GL15.glDeleteBuffers(s.pbo);
                s.pbo = 0;
            }
    }
}
