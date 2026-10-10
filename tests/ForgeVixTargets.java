import java.util.jar.JarFile;
import java.util.List;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Verify the optional integration against the user's actual VIX artifact. */
public final class ForgeVixTargets {
    static int checks;
    static void require(boolean ok,String message) {
        checks++; if(!ok)throw new AssertionError(message);
    }
    static ClassNode read(JarFile jar,String name)throws Exception {
        var entry=jar.getJarEntry(name+".class");require(entry!=null,"class exists: "+name);
        var node=new ClassNode();new ClassReader(jar.getInputStream(entry).readAllBytes()).accept(node,0);return node;
    }
    static Object value(AnnotationNode annotation,String key) {
        if(annotation.values!=null)for(int i=0;i<annotation.values.size();i+=2)
            if(annotation.values.get(i).equals(key))return annotation.values.get(i+1);
        return null;
    }
    public static void main(String[] args)throws Exception {
        try(var vix=new JarFile(args[0]);var bridge=new JarFile(args[1])) {
            var pipeline=read(vix,"com/guhao/vix/client/pipeline/PostEffectPipelines");
            var render=pipeline.methods.stream().filter(m->m.name.equals("RenderPost") && m.desc.equals("()V")).findFirst().orElseThrow();
            require((render.access&(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC))==(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC),"original post processor is publicly callable");
            require(pipeline.fields.stream().anyMatch(f->f.name.equals("PostEffectQueue") && f.desc.equals("Ljava/util/Queue;")),"late effects remain in original queue");
            var hook=read(vix,"com/guhao/vix/mixin/MixinGameRenderer");
            boolean calls=false,late=false;
            for(var method:hook.methods) {
                for(var ins:method.instructions)if(ins instanceof MethodInsnNode call && call.owner.equals(pipeline.name) && call.name.equals(render.name))calls=true;
                if(method.visibleAnnotations!=null)for(var annotation:method.visibleAnnotations)
                    if(annotation.desc.endsWith("/Inject;")) {
                        @SuppressWarnings("unchecked") var ats=(List<AnnotationNode>)value(annotation,"at");
                        if(ats!=null)for(var at:ats) {
                            var target=value(at,"target");
                            if(target instanceof String text && text.endsWith("LevelRenderer;doEntityOutline()V"))late=true;
                        }
                    }
            }
            require(calls && late,"confirmed original late post pass after world/hand stage");
            var compat=read(bridge,"dev/sekirobridge/compat/VixPostPipelineMixin");
            require(compat.invisibleAnnotations.stream().anyMatch(a->a.desc.endsWith("/Pseudo;")),"VIX remains optional");
            boolean exactTarget=false,head=false;
            for(var annotation:compat.invisibleAnnotations)if(annotation.desc.endsWith("/Mixin;"))
                exactTarget=List.of("com.guhao.vix.client.pipeline.PostEffectPipelines").equals(value(annotation,"targets")) && Boolean.FALSE.equals(value(annotation,"remap"));
            for(var method:compat.methods)if(method.visibleAnnotations!=null)
                for(var annotation:method.visibleAnnotations)if(annotation.desc.endsWith("/Inject;")) {
                    @SuppressWarnings("unchecked") var ats=(List<AnnotationNode>)value(annotation,"at");
                    head=List.of("RenderPost()V").equals(value(annotation,"method")) && Boolean.TRUE.equals(value(annotation,"cancellable")) &&
                        Boolean.FALSE.equals(value(annotation,"remap")) && ats!=null && ats.stream().anyMatch(a->"HEAD".equals(value(a,"value")));
                }
            require(exactTarget && head,"production injection matches actual VIX public method");
        }
        if(args.length>2)try(var aaa=new JarFile(args[2]);var bridge=new JarFile(args[1])){
            var manager=read(aaa,"mod/chloeprime/aaaparticles/api/client/effekseer/EffekseerManager");
            var core=read(aaa,"Effekseer/swig/EffekseerManagerCore");
            for(String method:List.of("drawBack","drawFront"))require(manager.methods.stream().anyMatch(m->m.name.equals(method) && m.desc.equals("()V") && (m.access&Opcodes.ACC_PUBLIC)!=0),"original AAA geometry entry: "+method);
            require(manager.methods.stream().anyMatch(m->m.name.equals("getImpl") && m.desc.equals("()LEffekseer/swig/EffekseerManagerCore;")),"actual manager core accessor");
            require(core.methods.stream().anyMatch(m->m.name.equals("GetTotalInstanceCount") && m.desc.equals("()I")),"empty manager skip uses actual instance count API");
            var dispatcher=read(new JarFile(args[0]),"com/guhao/vix/client/aaaeffect/AAAEffectPostProcessDispatcher");
            for(String method:List.of("DrawBack","DrawFront")){
                for(String signature:List.of("()V","(I)V"))require(core.methods.stream().anyMatch(m->m.name.equals(method) && m.desc.equals(signature) && (m.access&Opcodes.ACC_PUBLIC)!=0),"native core geometry entry: "+method+signature);
                boolean bypass=false;
                for(var m:dispatcher.methods)for(var instruction:m.instructions)
                    if(instruction instanceof MethodInsnNode call && call.owner.equals(core.name) && call.name.equals(method) && call.desc.equals("(I)V"))bypass=true;
                require(bypass,"VIX directly calls masked native core: "+method);
            }
            var mixin=read(bridge,"dev/sekirobridge/compat/AaaParticleDepthMixin");
            require(mixin.invisibleAnnotations.stream().anyMatch(a->a.desc.endsWith("/Pseudo;")),"AAA native renderer remains optional");
            int hooks=0;
            for(var m:mixin.methods)if(m.visibleAnnotations!=null)for(var a:m.visibleAnnotations)if(a.desc.endsWith("/Inject;")){
                @SuppressWarnings("unchecked") var ats=(List<AnnotationNode>)value(a,"at");
                @SuppressWarnings("unchecked") var selectors=(List<String>)value(a,"method");
                require(Boolean.FALSE.equals(value(a,"remap")) && selectors.size()==1 && List.of("DrawBack()V","DrawFront()V","DrawBack(I)V","DrawFront(I)V").contains(selectors.get(0)) &&
                    ats!=null && ats.stream().allMatch(at->"RETURN".equals(value(at,"value"))),"actual AAA geometry RETURN hook");hooks++;
            }
            require(hooks==4,"masked and normal original AAA geometry stages captured");
        }
        System.out.println(checks+" installed VIX/AAA render target checks passed (no game launched)");
    }
}
