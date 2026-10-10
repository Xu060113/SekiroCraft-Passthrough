import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.*;
import java.security.MessageDigest;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import com.google.gson.*;

/** Inspect the real remapped artifact, without booting Minecraft or native gameplay. */
public final class ForgePort {
    static int checks;
    static void require(boolean ok,String reason){checks++;if(!ok)throw new AssertionError(reason);}
    static ClassNode read(JarFile jar,String name)throws Exception{
        var entry=jar.getJarEntry(name+".class");require(entry!=null,"class packaged: "+name);
        var node=new ClassNode();new ClassReader(jar.getInputStream(entry).readAllBytes()).accept(node,0);return node;
    }
    static boolean calls(ClassNode node,String owner,String name){
        for(var method:node.methods)for(var instruction:method.instructions)
            if(instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name))return true;
        return false;
    }
    static String text(JarFile jar,String path)throws Exception{
        var entry=jar.getJarEntry(path);require(entry!=null,"resource packaged: "+path);
        return new String(jar.getInputStream(entry).readAllBytes(),StandardCharsets.UTF_8);
    }
    public static void main(String[] args)throws Exception{
        try(var jar=new JarFile(args[0])){
            require(jar.getJarEntry("fabric.mod.json")==null,"Fabric metadata excluded");
            require(jar.getJarEntry("dev/sekirobridge/BridgeCommon.class")==null,"Fabric initializer excluded");
            require(jar.getJarEntry("dev/sekirobridge/mixin/InGameHudMixin.class")==null,"ForgeGui replaces vanilla HUD adapter");
            var metadata=text(jar,"META-INF/mods.toml");
            require(metadata.contains("javafml") && metadata.contains("modId=\"sekirobridge\""),"Forge loader metadata");
            require(metadata.contains("[1.20.1,1.20.2)") && metadata.contains("[47.4.10,48)"),"pinned MC/Forge ranges");
            require(!metadata.contains("${version}") && metadata.contains("forge-preview.6"),"version expanded");
            var pack=JsonParser.parseString(text(jar,"pack.mcmeta")).getAsJsonObject().getAsJsonObject("pack");
            require(pack.get("pack_format").getAsInt()==15,"MC 1.20.1 resource pack metadata");
            require("sekirobridge.mixins.json,sekirobridge-forge-compat.mixins.json".equals(jar.getManifest().getMainAttributes().getValue("MixinConfigs")),"Mixin bootstrap manifest");
            var compat=JsonParser.parseString(text(jar,"sekirobridge-forge-compat.mixins.json")).getAsJsonObject();
            require(compat.get("plugin").getAsString().equals("dev.sekirobridge.compat.ForgeCompatPlugin"),"optional mod bytecode guard");
            require(calls(read(jar,"dev/sekirobridge/compat/ForgeCompatPlugin"),"dev/sekirobridge/compat/ForgeCompatPlugin","targetExists"),"production plugin uses the ModLauncher-tested lookup");
            require(calls(read(jar,"dev/sekirobridge/compat/VixPostPipelineMixin"),"dev/sekirobridge/ForgePostEffects","deferLatePass"),"late VIX post pass cannot overwrite transparent HUD");
            var captureMixin=read(jar,"dev/sekirobridge/mixin/GameRendererMixin");
            var captureMethod=captureMixin.methods.stream().filter(m->m.name.equals("world")).findFirst().orElseThrow();
            var order=new ArrayList<String>();
            for(var i:captureMethod.instructions)if(i instanceof MethodInsnNode call)order.add(call.owner+"."+call.name);
            require(order.indexOf("dev/sekirobridge/ForgePostEffects.beforeWorldCapture")>=0 &&
                order.indexOf("dev/sekirobridge/ForgePostEffects.beforeWorldCapture")<order.indexOf("dev/sekirobridge/FrameExporter.world"),"original VIX effects finish before frame split");
            var config=JsonParser.parseString(text(jar,"sekirobridge.mixins.json")).getAsJsonObject();
            require(config.get("required").getAsBoolean(),"critical injections fail visibly");
            require(!config.has("mixins") || config.getAsJsonArray("mixins").isEmpty(),"no dedicated-server client injections");
            var client=config.getAsJsonArray("client");var names=new HashSet<String>();
            for(var entry:client){String name=entry.getAsString();require(names.add(name),"unique mixin: "+name);
                require(jar.getJarEntry("dev/sekirobridge/mixin/"+name+".class")!=null,"configured mixin exists: "+name);}
            require(names.containsAll(List.of("NativeActorQueryMixin","NativeVoidMixin","PlayerCrouchModelMixin","ForgeShutdownMixin")),"client and integrated-server gameplay coverage");
            var refmap=JsonParser.parseString(text(jar,config.get("refmap").getAsString())).getAsJsonObject();
            require(refmap.has("mappings") && !refmap.getAsJsonObject("mappings").entrySet().isEmpty(),"production reference map populated");
            var mod=read(jar,"dev/sekirobridge/ForgeBridgeMod");
            require(calls(mod,"net/minecraftforge/fml/DistExecutor","safeRunWhenOn"),"dedicated-server classloading guard");
            var combat=read(jar,"dev/sekirobridge/CombatBridge");
            require(calls(combat,"net/minecraftforge/registries/DeferredRegister","register"),"entity registration uses Forge lifetime");
            require(calls(combat,"net/minecraftforge/event/entity/EntityAttributeCreationEvent","put"),"attributes registered on mod bus");
            require(calls(combat,"net/minecraftforge/client/event/EntityRenderersEvent$RegisterRenderers","registerEntityRenderer"),"both invisible proxy renderers registered");
            var events=read(jar,"dev/sekirobridge/ForgeClientEvents");
            require(calls(events,"net/minecraftforge/fml/event/lifecycle/FMLClientSetupEvent","enqueueWork"),"client/JNI initialization scheduled on client thread");
            require(calls(events,"dev/sekirobridge/CombatHud","render"),"Forge HUD invokes posture display");
            require(calls(events,"net/minecraftforge/client/event/RenderGuiOverlayEvent$Pre","setCanceled"),"bridge vignette overlay cancellation");
            var bridge=read(jar,"dev/sekirobridge/BridgeClient");
            require(calls(bridge,"net/minecraftforge/client/event/RegisterClientCommandsEvent","getDispatcher"),"client-only commands use Forge dispatcher");
            require(calls(bridge,"dev/sekirobridge/NativeBridge","close"),"native session shutdown path");
            try(var runtime=new JarFile(args[3])){
                var collision=read(jar,"dev/sekirobridge/mixin/CollisionViewMixin");
                var getter=read(runtime,"net/minecraft/world/level/CollisionGetter");
                var soft=collision.methods.stream().filter(m->m.name.startsWith("bridge$")).findFirst().orElseThrow();
                String name=soft.name.substring("bridge$".length());
                require(name.equals("m_186434_") && getter.methods.stream().anyMatch(m->m.name.equals(name) && m.desc.equals(soft.desc)),"soft collision override uses real Forge SRG interface signature");
                require(calls(collision,"dev/sekirobridge/NativeTerrain","add"),"native terrain still participates in concrete world collision");
            }
            try(var game=new JarFile(args[2])){
                var renderer=read(game,"net/minecraft/client/render/GameRenderer");
                var world=renderer.methods.stream().filter(m->m.name.equals("renderWorld")).findFirst().orElseThrow();
                int position=0,lastStage=-1,stageCall=-1,handRead=-1,depthClear=-1;
                for(var instruction:world.instructions){
                    if(instruction instanceof FieldInsnNode field){
                        if(field.owner.equals("net/minecraftforge/client/event/RenderLevelStageEvent$Stage") && field.name.equals("AFTER_LEVEL"))lastStage=position;
                        if(field.owner.equals(renderer.name) && field.name.equals("renderHand") && field.getOpcode()==Opcodes.GETFIELD)handRead=position;
                    }
                    if(instruction instanceof MethodInsnNode call){
                        if(call.owner.equals("net/minecraftforge/client/ForgeHooksClient") && call.name.equals("dispatchRenderStage") && lastStage>=0)stageCall=position;
                        if(call.owner.equals("com/mojang/blaze3d/systems/RenderSystem") && call.name.equals("clear") && handRead>=0)depthClear=position;
                    }
                    position++;
                }
                require(lastStage>=0 && stageCall>lastStage,"Forge AFTER_LEVEL dispatch really exists");
                require(handRead>stageCall && depthClear>handRead,"capture follows all Forge effects and precedes hand depth clear");
                int cameraEvents=-1,worldDraw=-1,index=0;
                for(var instruction:world.instructions){
                    if(instruction instanceof MethodInsnNode call){
                        if(call.owner.equals("net/minecraftforge/client/ForgeHooksClient") && call.name.equals("onCameraSetup"))cameraEvents=index;
                        if(call.owner.equals("net/minecraft/client/render/WorldRenderer") && call.name.equals("render"))worldDraw=index;
                    }index++;
                }
                require(cameraEvents>=0 && worldDraw>cameraEvents,"final camera capture occurs after mod camera events");
            }
            var cameraMixin=read(jar,"dev/sekirobridge/mixin/ForgeWorldCameraMixin");
            require(calls(cameraMixin,"dev/sekirobridge/PlayerExporter","camera"),"final camera world view exported");
            var depthAdapter=read(jar,"dev/sekirobridge/ForgeEffectDepth");
            require(calls(depthAdapter,"dev/sekirobridge/ParticleDepthTarget","capture") &&
                calls(depthAdapter,"dev/sekirobridge/ParticleDepthTarget","merge"),"native geometry depth is merged before world capture");
            var nativeBytes=jar.getInputStream(jar.getJarEntry("native/windows-x64/sekirobridge-jni.dll")).readAllBytes();
            require(MessageDigest.isEqual(nativeBytes,Files.readAllBytes(Path.of(args[1]))),"exact paired JNI payload");
            for(var entry:jar.stream().filter(e->e.getName().endsWith(".class")).toList()){
                byte[] classBytes=jar.getInputStream(entry).readAllBytes();
                require(((classBytes[6]&255)<<8 | (classBytes[7]&255))<=61,"Java 17 bytecode: "+entry.getName());
                String constantPool=new String(classBytes,StandardCharsets.ISO_8859_1);
                require(!constantPool.contains("net/fabricmc/") && !constantPool.contains("net.fabricmc."),"no Fabric runtime reference: "+entry.getName());
            }
        }
        System.out.println(checks+" Forge artifact/lifecycle checks passed (no game launched)");
    }
}
