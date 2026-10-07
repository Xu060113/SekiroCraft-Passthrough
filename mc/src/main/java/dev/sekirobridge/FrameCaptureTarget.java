package dev.sekirobridge;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import java.nio.ByteBuffer;

/** Private readback target. Its depth image must match the source's actual format. */
final class FrameCaptureTarget implements AutoCloseable {
    private int fbo, color, depth, width, height, depthFormat;

    private static int sourceDepthFormat(int source) {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, source);
        // A multisample resolve cannot also resize. Reject it instead of exporting stale depth.
        if (GL11.glGetInteger(GL30.GL_SAMPLES) != 0)
            return 0;
        int type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER,
            GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        if (type == GL11.GL_NONE)
            return 0;
        int name = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER,
            GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        if (type == GL30.GL_RENDERBUFFER) {
            int previous = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
            try {
                GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, name);
                return GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_INTERNAL_FORMAT);
            } finally {
                GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, previous);
            }
        }
        if (type != GL11.GL_TEXTURE || GL30.glGetFramebufferAttachmentParameteri(
            GL30.GL_READ_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL32.GL_FRAMEBUFFER_ATTACHMENT_LAYERED) != 0 ||
            GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL30.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_CUBE_MAP_FACE) != 0)
            return 0;
        int level = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER,
            GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL);
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, name);
            return GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_INTERNAL_FORMAT);
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
        }
    }

    private void resize(int w, int h, int format) {
        if (fbo != 0 && width == w && height == h && depthFormat == format)
            return;
        int texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int unpack = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        try {
            close();
            fbo = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            color = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, color);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer)null);
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, color, 0);
            depth = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depth);
            boolean packed = format == GL30.GL_DEPTH24_STENCIL8 || format == GL30.GL_DEPTH32F_STENCIL8;
            int dataType = format == GL30.GL_DEPTH24_STENCIL8 ? GL30.GL_UNSIGNED_INT_24_8 :
                format == GL30.GL_DEPTH32F_STENCIL8 ? GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV : GL11.GL_FLOAT;
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, format, w, h, 0,
                packed ? GL30.GL_DEPTH_STENCIL : GL11.GL_DEPTH_COMPONENT, dataType, (ByteBuffer)null);
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL11.GL_TEXTURE_2D, depth, 0);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            if (GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("Incomplete MC readback framebuffer for depth format " + format);
            width = w; height = h; depthFormat = format;
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpack);
        }
    }

    /** Leaves the capture target bound for glReadPixels; caller restores the framebuffer bindings. */
    boolean copy(int source, int sourceWidth, int sourceHeight, int w, int h, boolean withDepth) {
        int format = withDepth ? sourceDepthFormat(source) : depthFormat;
        if (format == 0 || w <= 0 || h <= 0 || sourceWidth <= 0 || sourceHeight <= 0)
            return false;
        resize(w, h, format);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
        // Source read-buffer selection belongs to that FBO; don't change it.
        GL30.glBlitFramebuffer(0, 0, sourceWidth, sourceHeight, 0, 0, w, h,
            GL11.GL_COLOR_BUFFER_BIT | (withDepth ? GL11.GL_DEPTH_BUFFER_BIT : 0), GL11.GL_NEAREST);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
        GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
        return true;
    }

    @Override public void close() {
        if (fbo != 0) GL30.glDeleteFramebuffers(fbo);
        if (color != 0) GL11.glDeleteTextures(color);
        if (depth != 0) GL11.glDeleteTextures(depth);
        fbo = color = depth = width = height = depthFormat = 0;
    }
}
