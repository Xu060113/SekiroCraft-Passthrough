package dev.sekirobridge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarFile;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;

/** Compare compiled bridge composition with the actual vanilla crouch branch. */
public final class CrouchModel {
    private static int checks;
    public static final class Part {
        public float pitch,yaw,roll,pivotX,pivotY,pivotZ;
        public void copyTransform(Part p){pitch=p.pitch;yaw=p.yaw;roll=p.roll;pivotX=p.pivotX;pivotY=p.pivotY;pivotZ=p.pivotZ;}
    }
    public static class Model {
        public boolean sneaking;
        public Part head=new Part(),hat=new Part(),body=new Part(),rightArm=new Part(),leftArm=new Part(),
            rightLeg=new Part(),leftLeg=new Part(),jacket=new Part(),rightSleeve=new Part(),leftSleeve=new Part(),
            rightPants=new Part(),leftPants=new Part();
        public Model(){rightArm.pivotY=leftArm.pivotY=2;rightLeg.pivotY=leftLeg.pivotY=12;}
    }
    private static void check(boolean ok,String reason){checks++;if(!ok)throw new AssertionError(reason);}
    private static boolean same(Part a,Part b){
        return Math.abs(a.pitch-b.pitch)<1e-5 && Math.abs(a.pivotY-b.pivotY)<1e-5 &&
            Math.abs(a.pivotZ-b.pivotZ)<1e-5 && a.yaw==b.yaw && a.roll==b.roll && a.pivotX==b.pivotX;
    }
    public static void main(String[] args)throws Exception {
        String prefix="dev/sekirobridge/CrouchModel$",fixture="dev/sekirobridge/CrouchBranchFixture";
        var aliases=Map.of("net/minecraft/client/render/entity/model/BipedEntityModel",prefix+"Model",
            "net/minecraft/client/render/entity/model/PlayerEntityModel",prefix+"Model",
            "net/minecraft/client/model/ModelPart",prefix+"Part");
        var remapper=new Remapper(){@Override public String map(String name){return aliases.getOrDefault(name,name);}};
        MethodNode angles;
        try(var game=new JarFile(Path.of(args[0]).toFile())){
            var cls=new ClassNode();new ClassReader(game.getInputStream(game.getJarEntry(
                "net/minecraft/client/render/entity/model/BipedEntityModel.class"))).accept(cls,0);
            angles=cls.methods.stream().filter(m->m.name.equals("setAngles") &&
                m.desc.startsWith("(Lnet/minecraft/entity/LivingEntity;")).findFirst().orElseThrow();
        }
        var writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_6,Opcodes.ACC_PUBLIC,fixture,null,prefix+"Model",null);
        var init=writer.visitMethod(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);
        init.visitCode();init.visitVarInsn(Opcodes.ALOAD,0);init.visitMethodInsn(Opcodes.INVOKESPECIAL,prefix+"Model","<init>","()V",false);
        init.visitInsn(Opcodes.RETURN);init.visitMaxs(0,0);init.visitEnd();
        var method=writer.visitMethod(Opcodes.ACC_PUBLIC,"vanilla","()V",null,null);
        var remapped=new MethodRemapper(method,remapper);
        var labels=new HashMap<LabelNode,LabelNode>();
        for(var n:angles.instructions)if(n instanceof LabelNode l)labels.put(l,new LabelNode());
        FieldInsnNode flag=null;
        for(var n:angles.instructions)if(n instanceof FieldInsnNode f && f.name.equals("sneaking") && f.getOpcode()==Opcodes.GETFIELD){flag=f;break;}
        if(flag==null)throw new AssertionError("Vanilla crouch branch missing");
        var branch=(JumpInsnNode)flag.getNext();
        // The crouching branch jumps past the standing branch to a shared join.
        LabelNode join=null;
        for(var n=branch.getNext();n!=branch.label;n=n.getNext())
            if(n instanceof JumpInsnNode j && j.getOpcode()==Opcodes.GOTO)join=j.label;
        if(join==null)throw new AssertionError("Vanilla crouch join missing");
        remapped.visitCode();
        for(var n=flag.getPrevious();n!=join;n=n.getNext())
            if(!(n instanceof FrameNode) && !(n instanceof LineNumberNode))n.clone(labels).accept(remapped);
        labels.get(join).accept(remapped);remapped.visitInsn(Opcodes.RETURN);remapped.visitMaxs(0,0);remapped.visitEnd();writer.visitEnd();
        var helper=new ClassWriter(0);
        new ClassReader(Files.readAllBytes(Path.of(args[1],"dev/sekirobridge/CrouchModelPose.class")))
            .accept(new ClassRemapper(helper,remapper),0);
        var loader=new ClassLoader(CrouchModel.class.getClassLoader()){
            Class<?> define(String name,byte[] data){return defineClass(name,data,0,data.length);}
        };
        var vanilla=loader.define(fixture.replace('/','.'),writer.toByteArray());
        var apply=loader.define("dev.sekirobridge.CrouchModelPose",helper.toByteArray()).getMethod("apply",Model.class);
        Model expected=(Model)vanilla.getConstructor().newInstance();expected.sneaking=true;
        vanilla.getMethod("vanilla").invoke(expected);
        Model composed=new Model();apply.invoke(null,composed);
        for(String name:new String[]{"head","body","rightArm","leftArm","rightLeg","leftLeg"})
            check(same((Part)Model.class.getField(name).get(expected),(Part)Model.class.getField(name).get(composed)),
                "bridge offsets match actual vanilla crouch for "+name);
        check(same(composed.hat,composed.head) && same(composed.jacket,composed.body),"head and jacket skin follows crouch");
        check(same(composed.rightPants,composed.rightLeg) && same(composed.leftPants,composed.leftLeg),"pants skin follows crouch");
        check(same(composed.rightSleeve,composed.rightArm) && same(composed.leftSleeve,composed.leftArm),"sleeves follow weapon/crouch blend");
        Model armed=new Model();armed.body.pitch=.3f;armed.rightArm.pitch=-1;armed.leftArm.pitch=-.8f;
        armed.rightArm.yaw=.9f;armed.leftLeg.pitch=.4f;armed.rightLeg.pivotZ=1.5f;
        apply.invoke(null,armed);
        check(Math.abs(armed.body.pitch-.8f)<1e-5 && Math.abs(armed.rightArm.pitch+.6f)<1e-5,
            "weapon rotation remains additive rather than reset to vanilla");
        check(armed.rightArm.yaw==.9f && armed.leftLeg.pitch==.4f && armed.rightLeg.pivotZ==5.5f,
            "weapon yaw, running legs and animated pivot survive crouch composition");
        // A reused player model gets fresh standing transforms on the next setAngles.
        Model standing=(Model)vanilla.getConstructor().newInstance();standing.sneaking=false;
        vanilla.getMethod("vanilla").invoke(standing);
        check(standing.body.pitch==0 && standing.rightLeg.pivotZ==0 && standing.body.pivotY==0,
            "release returns to the actual vanilla standing branch without crouch accumulation");
        System.out.println(checks+" actual vanilla crouch model and weapon-layer checks passed");
    }
}
