package dev.sekirobridge;

import java.nio.ByteBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;

/** Exercise real JNI OpenGL detours, private geometry replay and depth merge. */
public final class ForgeParticleDepthGl {
    private static int checks;
    private static void require(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static float depth(int x,int y){var p=BufferUtils.createFloatBuffer(1);GL11.glReadPixels(x,y,1,1,GL11.GL_DEPTH_COMPONENT,GL11.GL_FLOAT,p);return p.get(0);}
    private static int color(int x,int y,int channel){var p=BufferUtils.createByteBuffer(4);GL11.glReadPixels(x,y,1,1,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,p);return p.get(channel)&255;}
    private static int shader(int type,String text){int id=GL20.glCreateShader(type);GL20.glShaderSource(id,text);GL20.glCompileShader(id);
        require(GL20.glGetShaderi(id,GL20.GL_COMPILE_STATUS)!=0,GL20.glGetShaderInfoLog(id));return id;}
    public static void main(String[] args){
        System.load(args[0]);require(!NativeBridge.beginEffectDepth(),"no OpenGL context stays dormant");
        require(GLFW.glfwInit(),"GLFW starts");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);
        long window=GLFW.glfwCreateWindow(32,32,"AAA depth regression",0,0);require(window!=0,"hidden GL fixture");
        GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
        int fbo=GL30.glGenFramebuffers(),color=GL11.glGenTextures(),depth=GL11.glGenTextures(),vao=GL30.glGenVertexArrays();
        int vs=shader(GL20.GL_VERTEX_SHADER,"#version 330 core\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.-1.,-.4,1);}");
        int fs=shader(GL20.GL_FRAGMENT_SHADER,"#version 330 core\nout vec4 color;void main(){if(any(lessThan(gl_FragCoord.xy,vec2(8)))||any(greaterThan(gl_FragCoord.xy,vec2(24))))discard;color=vec4(0,.25,1,0);}");
        int program=GL20.glCreateProgram();GL20.glAttachShader(program,vs);GL20.glAttachShader(program,fs);GL20.glLinkProgram(program);
        require(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)!=0,"native geometry representative shader links");
        try(var target=new ParticleDepthTarget()){
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);
            for(int id:new int[]{color,depth}){GL11.glBindTexture(GL11.GL_TEXTURE_2D,id);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,id==color?GL11.GL_RGBA8:GL30.GL_DEPTH_COMPONENT32F,32,32,0,
                    id==color?GL11.GL_RGBA:GL11.GL_DEPTH_COMPONENT,id==color?GL11.GL_UNSIGNED_BYTE:GL11.GL_FLOAT,(ByteBuffer)null);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,id==color?GL30.GL_COLOR_ATTACHMENT0:GL30.GL_DEPTH_ATTACHMENT,GL11.GL_TEXTURE_2D,id,0);}
            require(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)==GL30.GL_FRAMEBUFFER_COMPLETE,"world FBO complete");
            GL11.glViewport(0,0,32,32);GL11.glDepthMask(true);GL11.glColorMask(true,true,true,true);
            GL11.glClearDepth(1);GL11.glClearColor(0,0,0,0);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT|GL11.GL_DEPTH_BUFFER_BIT);
            // Actual captured world has transparent empty pixels. Original FX
            // and post-processed halo contribute RGB only around the particle.
            GL11.glEnable(GL11.GL_SCISSOR_TEST);GL11.glScissor(4,4,24,24);
            GL11.glClearColor(.1f,.2f,.4f,0);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDepthMask(false);GL11.glDisable(GL11.GL_DEPTH_TEST);
            Runnable original=()->{
                // Actual native Effekseer can repeatedly request no Z write. The
                // real JNI detours keep the auxiliary geometry pass writable.
                GL11.glDepthMask(false);GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDepthFunc(GL11.GL_ALWAYS);
                require(GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK) && GL11.glIsEnabled(GL11.GL_DEPTH_TEST),"native depth overrides active only inside scope");
                require(GL11.glGetInteger(GL11.GL_DEPTH_FUNC)==GL11.GL_LEQUAL,"native particle replay uses depth test");
                require(!NativeBridge.beginEffectDepth(),"nested native scope cannot steal ownership");
                GL20.glUseProgram(program);GL30.glBindVertexArray(vao);GL11.glDisable(GL11.GL_BLEND);GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);
            };
            target.beginFrame();target.capture(32,32,original);
            require(target.captured(),"original additive geometry captured");
            require(!GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK) && !GL11.glIsEnabled(GL11.GL_DEPTH_TEST),"capture restores world depth state");
            require(depth(16,16)>.9999,"replay does not touch original world depth");
            int before=color(16,16,2);target.merge(fbo);
            require(Math.abs(depth(16,16)-.3)<.001,"zero-alpha blue geometry acquires real finite depth");
            require(depth(0,0)>.9999,"empty pixels remain depth-free");
            require(color(16,16,2)==before && color(0,0,3)==0,"merge preserves original world RGB and alpha");
            require(depth(4,16)<.31,"post-process edge propagation has finite geometry depth");
            GL11.glDepthMask(true);GL11.glClearDepth(.2);GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);target.merge(fbo);
            require(Math.abs(depth(16,16)-.2)<.001,"foreground MC geometry retains closer depth");
            target.beginFrame();GL11.glClearDepth(1);GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);target.merge(fbo);
            require(depth(16,16)>.9999,"next effect-free frame cannot retain old particle depth");
            GL11.glDepthMask(false);
            try{target.capture(32,32,()->{throw new IllegalStateException("fixture");});}catch(IllegalStateException expected){}
            GL11.glDepthMask(false);require(!GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK),"exception releases native override");
            target.capture(16,16,original);require(target.captured(),"target resize supported");
            require(GL11.glGetError()==GL11.GL_NO_ERROR,"GL geometry capture/merge valid");
        }finally{
            GL20.glDeleteProgram(program);GL20.glDeleteShader(vs);GL20.glDeleteShader(fs);GL30.glDeleteVertexArrays(vao);
            GL30.glDeleteFramebuffers(fbo);GL11.glDeleteTextures(color);GL11.glDeleteTextures(depth);GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();
        }
        System.out.println(checks+" native AAA geometry depth/coverage checks passed (no game launched)");
    }
}
