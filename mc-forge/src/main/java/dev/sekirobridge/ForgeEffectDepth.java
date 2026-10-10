package dev.sekirobridge;

import java.lang.reflect.Method;
import java.util.HashMap;
import net.minecraft.client.MinecraftClient;

/** Optional AAA native geometry replay; never advances, spawns or replaces FX. */
public final class ForgeEffectDepth {
    private static final ParticleDepthTarget TARGET=new ParticleDepthTarget();
    private static final HashMap<String,Method> METHODS=new HashMap<>();
    private static boolean replaying,failed,collecting;
    private static Method instanceCount;
    private static long replays;
    private ForgeEffectDepth(){}
    public static void beginWorld(){TARGET.beginFrame();collecting=true;}
    public static void capture(Object core,String method,Integer layerMask){
        if(replaying || failed || !collecting || !BridgeClient.active())return;
        try{
            if(instanceCount==null)instanceCount=core.getClass().getMethod("GetTotalInstanceCount");
            if(((Number)instanceCount.invoke(core)).intValue()==0)return;
            String key=method+(layerMask==null?"()":"(I)");
            Method original=METHODS.get(key);
            if(original==null){original=layerMask==null?core.getClass().getMethod(method):core.getClass().getMethod(method,int.class);METHODS.put(key,original);}
            final Method draw=original;
            var framebuffer=MinecraftClient.getInstance().getFramebuffer();
            replaying=true;
            try{TARGET.capture(framebuffer.textureWidth,framebuffer.textureHeight,()->{
                try{if(layerMask==null)draw.invoke(core);else draw.invoke(core,layerMask);}
                catch(ReflectiveOperationException e){throw new IllegalStateException("AAA original geometry replay failed",e);}
            });}finally{replaying=false;}
            if(++replays==1)BridgeClient.LOG.info("AAA original particle geometry depth capture enabled");
        }catch(RuntimeException | ReflectiveOperationException e){
            failed=true;BridgeClient.LOG.error("AAA depth adapter disabled; original effects remain enabled",e);
        }
    }
    public static void mergeWorld(){
        collecting=false;
        if(failed || !BridgeClient.active())return;
        try{TARGET.merge(MinecraftClient.getInstance().getFramebuffer().fbo);}
        catch(RuntimeException e){failed=true;BridgeClient.LOG.error("AAA depth merge disabled",e);}
    }
    public static void close(){collecting=false;TARGET.close();METHODS.clear();instanceCount=null;}
}
