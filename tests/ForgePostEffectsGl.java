package dev.sekirobridge;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import org.lwjgl.BufferUtils;
import java.nio.ByteBuffer;

/** Reproduce an opaque full-screen effect, retaining it in world color, not HUD. */
public final class ForgePostEffectsGl {
    private static int checks;
    private static void require(boolean pass, String message) {
        checks++; if (!pass) throw new AssertionError(message);
    }
    private static int rgba(int x, int y, int channel) {
        var p=BufferUtils.createByteBuffer(4);
        GL11.glReadPixels(x,y,1,1,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,p);
        return p.get(channel)&255;
    }
    public static void main(String[] args) {
        require(GLFW.glfwInit(),"GLFW initialization");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);
        long window=GLFW.glfwCreateWindow(32,32,"Forge effects regression",0,0);
        require(window!=0,"hidden GL test context");
        GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
        int fbo=GL30.glGenFramebuffers(),color=GL11.glGenTextures(),depth=GL11.glGenTextures();
        try(var capture=new FrameCaptureTarget()) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);
            for(int tex:new int[]{color,depth}) {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D,tex);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,tex==color?GL11.GL_RGBA8:GL30.GL_DEPTH_COMPONENT24,
                    32,32,0,tex==color?GL11.GL_RGBA:GL11.GL_DEPTH_COMPONENT,
                    tex==color?GL11.GL_UNSIGNED_BYTE:GL11.GL_FLOAT,(ByteBuffer)null);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,
                    tex==color?GL30.GL_COLOR_ATTACHMENT0:GL30.GL_DEPTH_ATTACHMENT,GL11.GL_TEXTURE_2D,tex,0);
            }
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            require(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)==GL30.GL_FRAMEBUFFER_COMPLETE,"source target");
            int[] runs={0};
            Runnable originalEffect=()->{
                runs[0]++;
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);
                GL11.glClearColor(0,0,0,1);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
                GL11.glEnable(GL11.GL_SCISSOR_TEST);GL11.glScissor(8,8,16,16);
                GL11.glClearColor(0,.25f,1,1);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
            };
            // The previous order puts an opaque black post pass into the HUD.
            GL11.glClearColor(0,0,0,0);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            originalEffect.run();
            require(rgba(0,0,3)==255,"old late effect reproduces opaque black HUD");
            var order=new PostEffectOrder();
            for(int frame=0;frame<3;frame++) {
                order.beginFrame();require(!order.deferLatePass(true),"each new frame accepts original effects");
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);
                GL11.glDepthMask(true);GL11.glClearDepth(1);GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
                order.beforeCapture(originalEffect);
                require(capture.copy(fbo,32,32,32,32,true),"production world capture after effect");
                require(rgba(16,16,2)==255 && rgba(16,16,1)>=63,"original blue effect RGB retained");
                var d=BufferUtils.createFloatBuffer(1);GL11.glReadPixels(0,0,1,1,GL11.GL_DEPTH_COMPONENT,GL11.GL_FLOAT,d);
                require(d.get(0)>.99999f,"post pass retains empty-world depth for native visibility");
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);
                GL11.glClearColor(0,0,0,0);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT|GL11.GL_DEPTH_BUFFER_BIT);
                int before=runs[0];
                if(!order.deferLatePass(true))originalEffect.run();
                require(runs[0]==before,"late world pass deferred after frame split");
                require(rgba(0,0,3)==0 && rgba(16,16,3)==0,"HUD remains transparent; native scene cannot be blacked out");
                require(!order.deferLatePass(false),"standalone Minecraft keeps original render order");
            }
            order.beginFrame();
            try {order.beforeCapture(()->{throw new IllegalStateException("fixture failure");});}
            catch(IllegalStateException expected) {}
            require(!order.deferLatePass(true),"failed early pass cannot suppress original fallback");
            require(GL11.glGetError()==GL11.GL_NO_ERROR,"GL operations valid");
        } finally {
            GL30.glDeleteFramebuffers(fbo);GL11.glDeleteTextures(color);GL11.glDeleteTextures(depth);
            GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();
        }
        System.out.println(checks+" Forge full-screen effect/transparent HUD checks passed (no game launched)");
    }
}
