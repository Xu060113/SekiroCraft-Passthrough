package dev.sekirobridge;

import java.nio.ByteBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

/** Replay original native particle geometry into a private color/depth target.
 * Color is coverage only: the original particle/post-process RGB stays intact.
 */
public final class ParticleDepthTarget implements AutoCloseable {
    private int fbo,color,depth,scene,program,vao,width,height;
    private boolean captured;
    public void beginFrame(){captured=false;}
    public boolean captured(){return captured;}
    private static final class State implements AutoCloseable {
        final int read=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING),draw=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        final int shader=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),array=GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        final int active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE),func=GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        final int srcRGB=GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB),dstRGB=GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),
            srcAlpha=GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA),dstAlpha=GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA),
            equationRGB=GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB),equationAlpha=GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        final boolean test=GL11.glIsEnabled(GL11.GL_DEPTH_TEST),write=GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK),
            blend=GL11.glIsEnabled(GL11.GL_BLEND),scissor=GL11.glIsEnabled(GL11.GL_SCISSOR_TEST),cull=GL11.glIsEnabled(GL11.GL_CULL_FACE);
        final int[] viewport=new int[4],textures=new int[8],samplers=new int[8];
        final int arrayBuffer=GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING),frontFace=GL11.glGetInteger(GL11.GL_FRONT_FACE),
            cullMode=GL11.glGetInteger(GL11.GL_CULL_FACE_MODE);
        final ByteBuffer mask=BufferUtils.createByteBuffer(4);
        final float[] clearColor=new float[4];
        final double clearDepth=GL11.glGetDouble(GL11.GL_DEPTH_CLEAR_VALUE);
        State(){GL11.glGetIntegerv(GL11.GL_VIEWPORT,viewport);GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK,mask);
            GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE,clearColor);
            for(int i=0;i<textures.length;++i){GL13.glActiveTexture(GL13.GL_TEXTURE0+i);textures[i]=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                samplers[i]=GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING,i);}
            GL13.glActiveTexture(active);}
        public void close(){
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,read);GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,draw);
            GL20.glUseProgram(shader);GL30.glBindVertexArray(array);GL11.glDepthFunc(func);GL11.glDepthMask(write);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,arrayBuffer);GL11.glFrontFace(frontFace);GL11.glCullFace(cullMode);
            GL14.glBlendFuncSeparate(srcRGB,dstRGB,srcAlpha,dstAlpha);GL20.glBlendEquationSeparate(equationRGB,equationAlpha);
            GL11.glClearColor(clearColor[0],clearColor[1],clearColor[2],clearColor[3]);GL11.glClearDepth(clearDepth);
            GL11.glColorMask(mask.get(0)!=0,mask.get(1)!=0,mask.get(2)!=0,mask.get(3)!=0);
            toggle(GL11.GL_DEPTH_TEST,test);toggle(GL11.GL_BLEND,blend);toggle(GL11.GL_SCISSOR_TEST,scissor);toggle(GL11.GL_CULL_FACE,cull);
            GL11.glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);
            for(int i=0;i<textures.length;++i){GL13.glActiveTexture(GL13.GL_TEXTURE0+i);GL11.glBindTexture(GL11.GL_TEXTURE_2D,textures[i]);GL33.glBindSampler(i,samplers[i]);}
            GL13.glActiveTexture(active);
        }
    }
    private static void toggle(int flag,boolean enabled){if(enabled)GL11.glEnable(flag);else GL11.glDisable(flag);}
    private static int texture(int w,int h,boolean depth){
        int id=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,id);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,depth?GL30.GL_DEPTH_COMPONENT32F:GL11.GL_RGBA8,w,h,0,
            depth?GL11.GL_DEPTH_COMPONENT:GL11.GL_RGBA,depth?GL11.GL_FLOAT:GL11.GL_UNSIGNED_BYTE,(ByteBuffer)null);return id;
    }
    private void resize(int w,int h){
        if(width==w && height==h)return;close();width=w;height=h;
        color=texture(w,h,false);depth=texture(w,h,true);scene=texture(w,h,false);fbo=GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,color,0);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_DEPTH_ATTACHMENT,GL11.GL_TEXTURE_2D,depth,0);
        GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
        if(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)!=GL30.GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("Particle depth target incomplete");
    }
    public void capture(int w,int h,Runnable originalGeometry){
        if(w<=0 || h<=0)return;
        try(var restore=new State()){
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            resize(w,h);GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);GL11.glViewport(0,0,w,h);
            if(!captured){GL11.glDisable(GL11.GL_SCISSOR_TEST);GL11.glColorMask(true,true,true,true);GL11.glDepthMask(true);
                GL11.glClearColor(0,0,0,0);GL11.glClearDepth(1);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT|GL11.GL_DEPTH_BUFFER_BIT);}
            if(!NativeBridge.beginEffectDepth())throw new IllegalStateException("Native particle depth scope unavailable");
            try{originalGeometry.run();captured=true;}finally{NativeBridge.endEffectDepth();}
        }
    }
    private static int shader(int type,String source){int id=GL20.glCreateShader(type);GL20.glShaderSource(id,source);GL20.glCompileShader(id);
        if(GL20.glGetShaderi(id,GL20.GL_COMPILE_STATUS)==0){String log=GL20.glGetShaderInfoLog(id);GL20.glDeleteShader(id);throw new IllegalStateException(log);}return id;}
    private void prepareMerge(){
        if(program!=0)return;
        int v=shader(GL20.GL_VERTEX_SHADER,"#version 330 core\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.0-1.0,0,1);}");
        int f=shader(GL20.GL_FRAGMENT_SHADER,"""
            #version 330 core
            uniform sampler2D Coverage;uniform sampler2D Depth;uniform sampler2D Scene;
            out vec4 unused;
            float coveredDepth(ivec2 p){ivec2 size=textureSize(Depth,0);p=clamp(p,ivec2(0),size-1);
                vec4 c=texelFetch(Coverage,p,0);float d=texelFetch(Depth,p,0).r;
                return max(c.a,max(c.r,max(c.g,c.b)))>0.005?d:1.0;}
            void main(){ivec2 p=ivec2(gl_FragCoord.xy);float d=coveredDepth(p);
                // Propagate finite particle depth into post-process glow/chroma
                // edges only where final world RGB actually contains a signal.
                vec3 rgb=texelFetch(Scene,p,0).rgb;
                if(d>=0.9999999 && max(rgb.r,max(rgb.g,rgb.b))>0.005){
                    for(int radius=1;radius<=16;radius*=4){
                        for(int x=-1;x<=1;x++)for(int y=-1;y<=1;y++)
                            d=min(d,coveredDepth(p+ivec2(x,y)*radius));
                    }
                }
                if(d>=0.9999999)discard;gl_FragDepth=d;unused=vec4(0);
            }
            """);
        program=GL20.glCreateProgram();GL20.glAttachShader(program,v);GL20.glAttachShader(program,f);GL20.glLinkProgram(program);
        GL20.glDeleteShader(v);GL20.glDeleteShader(f);
        if(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)==0)throw new IllegalStateException(GL20.glGetProgramInfoLog(program));
        vao=GL30.glGenVertexArrays();GL20.glUseProgram(program);
        GL20.glUniform1i(GL20.glGetUniformLocation(program,"Coverage"),0);GL20.glUniform1i(GL20.glGetUniformLocation(program,"Depth"),1);
        GL20.glUniform1i(GL20.glGetUniformLocation(program,"Scene"),2);
    }
    public void merge(int destination){
        if(!captured)return;
        try(var restore=new State()){
            prepareMerge();GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,destination);
            GL13.glActiveTexture(GL13.GL_TEXTURE2);GL11.glBindTexture(GL11.GL_TEXTURE_2D,scene);
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D,0,0,0,0,0,width,height);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,destination);GL11.glViewport(0,0,width,height);
            GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glDepthFunc(GL11.GL_LEQUAL);GL11.glDepthMask(true);GL11.glColorMask(false,false,false,false);
            GL11.glDisable(GL11.GL_BLEND);GL11.glDisable(GL11.GL_SCISSOR_TEST);GL11.glDisable(GL11.GL_CULL_FACE);
            GL20.glUseProgram(program);GL30.glBindVertexArray(vao);
            for(int i=0;i<3;++i)GL33.glBindSampler(i,0);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);GL11.glBindTexture(GL11.GL_TEXTURE_2D,color);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);GL11.glBindTexture(GL11.GL_TEXTURE_2D,depth);
            GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);
        }
    }
    public void close(){
        if(fbo!=0)GL30.glDeleteFramebuffers(fbo);for(int id:new int[]{color,depth,scene})if(id!=0)GL11.glDeleteTextures(id);
        if(program!=0)GL20.glDeleteProgram(program);if(vao!=0)GL30.glDeleteVertexArrays(vao);
        fbo=color=depth=scene=program=vao=width=height=0;captured=false;
    }
}
