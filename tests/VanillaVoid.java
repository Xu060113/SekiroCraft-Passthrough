package dev.sekirobridge;

import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;

/** Replay vanilla's actual height/damage bytecode and the compiled mixin callback.
 * No Minecraft world, registry, client, server or game window is launched. */
public final class VanillaVoid {
    private static final String PREFIX="dev/sekirobridge/VanillaVoid$";
    private static final String VICTIM="dev/sekirobridge/VoidVictim";
    private static int checks;
    public static class World {public int bottom=-64;public int getBottomY(){return bottom;}}
    public static class Source {public final String type;Source(String type){this.type=type;}}
    public static class Sources {public Source outOfWorld(){return new Source("out_of_world");}}
    public static class Shell {
        public World world;public UUID id=UUID.randomUUID();public double y;
        public boolean player,nativeActor;public float hp=20;public int hits;public String lastSource;
        public double getY(){return y;}
        public World getWorld(){return world;}
        public Sources getDamageSources(){return new Sources();}
        public boolean damage(Source source,float amount){hits++;lastSource=source.type;hp=Math.max(0,hp-amount);return true;}
    }
    public static class Callback {
        public boolean cancelled;
        public void cancel(){cancelled=true;}
        public boolean isCancelled(){return cancelled;}
    }
    public static class Bridge {
        static NativeVoidPolicy.Scope scope;static boolean connected;static long epoch;
        public static boolean blocks(Shell entity){return scope!=null &&
            scope.owns(connected,epoch,entity.world,entity.id,entity.player,entity.nativeActor);}
    }
    private static final class Loader extends ClassLoader {
        Loader(){super(VanillaVoid.class.getClassLoader());}
        Class<?> load(byte[] bytes){return defineClass(VICTIM.replace('/','.'),bytes,0,bytes.length);}
    }
    private static void require(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static byte[] entry(ZipFile jar,String path)throws Exception {
        var entry=jar.getEntry(path);require(entry!=null,"vanilla class exists: "+path);
        return jar.getInputStream(entry).readAllBytes();
    }
    public static void main(String[] args)throws Exception {
        byte[] entity,living;
        try(var jar=new ZipFile(Path.of(args[0]).toFile())){
            entity=entry(jar,"net/minecraft/entity/Entity.class");
            living=entry(jar,"net/minecraft/entity/LivingEntity.class");
        }
        byte[] mixin=Files.readAllBytes(Path.of(args[1],"NativeVoidMixin.class"));
        var remapper=new Remapper(){@Override public String map(String name){return switch(name){
            case "net/minecraft/entity/Entity","net/minecraft/entity/LivingEntity",
                 "dev/sekirobridge/mixin/NativeVoidMixin" -> VICTIM;
            case "net/minecraft/world/World" -> PREFIX+"World";
            case "net/minecraft/entity/damage/DamageSources" -> PREFIX+"Sources";
            case "net/minecraft/entity/damage/DamageSource" -> PREFIX+"Source";
            case "org/spongepowered/asm/mixin/injection/callback/CallbackInfo" -> PREFIX+"Callback";
            default -> name;
        };}};
        var writer=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,VICTIM,null,PREFIX+"Shell",null);
        var constructor=writer.visitMethod(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);
        constructor.visitCode();constructor.visitVarInsn(Opcodes.ALOAD,0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL,PREFIX+"Shell","<init>","()V",false);
        constructor.visitInsn(Opcodes.RETURN);constructor.visitMaxs(0,0);constructor.visitEnd();
        int[] methods={0,0,0};
        for(boolean guarded:new boolean[]{false,true})new ClassReader(entity).accept(new ClassVisitor(Opcodes.ASM9){
            @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions){
                if(!name.equals("attemptTickInVoid"))return null;methods[0]++;
                var output=writer.visitMethod(Opcodes.ACC_PUBLIC,guarded?"attemptTickInVoid":"originalVoid",descriptor,null,null);
                return new MethodRemapper(output,remapper){@Override public void visitCode(){
                    super.visitCode();if(!guarded)return;
                    output.visitTypeInsn(Opcodes.NEW,PREFIX+"Callback");output.visitInsn(Opcodes.DUP);
                    output.visitMethodInsn(Opcodes.INVOKESPECIAL,PREFIX+"Callback","<init>","()V",false);
                    output.visitVarInsn(Opcodes.ASTORE,1);output.visitVarInsn(Opcodes.ALOAD,0);output.visitVarInsn(Opcodes.ALOAD,1);
                    output.visitMethodInsn(Opcodes.INVOKESPECIAL,VICTIM,"bridgeNativeVoid","(L"+PREFIX+"Callback;)V",false);
                    output.visitVarInsn(Opcodes.ALOAD,1);
                    output.visitMethodInsn(Opcodes.INVOKEVIRTUAL,PREFIX+"Callback","isCancelled","()Z",false);
                    var original=new Label();output.visitJumpInsn(Opcodes.IFEQ,original);output.visitInsn(Opcodes.RETURN);output.visitLabel(original);
                }};
            }
        },ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        new ClassReader(living).accept(new ClassVisitor(Opcodes.ASM9){
            @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions){
                if(!name.equals("tickInVoid"))return null;methods[1]++;
                return new MethodRemapper(writer.visitMethod(access,name,remapper.mapMethodDesc(descriptor),null,null),remapper);
            }
        },ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        new ClassReader(mixin).accept(new ClassVisitor(Opcodes.ASM9){
            @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions){
                if(!name.equals("bridgeNativeVoid"))return null;methods[2]++;
                return new MethodRemapper(writer.visitMethod(access,name,remapper.mapMethodDesc(descriptor),null,null),remapper){
                    @Override public AnnotationVisitor visitAnnotation(String descriptor,boolean visible){return null;}
                    @Override public void visitMethodInsn(int opcode,String owner,String name,String descriptor,boolean isInterface){
                        if(owner.equals("dev/sekirobridge/NativeVoidProtection") && name.equals("blocks"))
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC,PREFIX+"Bridge","blocks","(L"+PREFIX+"Shell;)Z",false);
                        else super.visitMethodInsn(opcode,owner,name,descriptor,isInterface);
                    }
                };
            }
        },ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        require(Arrays.equals(methods,new int[]{2,1,1}),"exact original void methods and compiled mixin callback exist");
        writer.visitEnd();var type=new Loader().load(writer.toByteArray());
        var original=type.getMethod("originalVoid");var guarded=type.getMethod("attemptTickInVoid");
        var clientWorld=new World();var serverWorld=new World();UUID owner=UUID.randomUUID();
        Bridge.scope=new NativeVoidPolicy.Scope(123,owner,clientWorld,serverWorld);Bridge.epoch=123;Bridge.connected=true;
        var victim=(Shell)type.getConstructor().newInstance();victim.world=serverWorld;victim.player=true;victim.id=owner;
        victim.y=-500+128; // Native canyon height with the configured y_offset.
        original.invoke(victim);
        require(victim.hp==16 && victim.hits==1 && "out_of_world".equals(victim.lastSource),"reproduce false canyon void damage with original vanilla bytecode");
        var oldLedger=new HealthLedger();oldLedger.synchronize(1,1,0,0,false);
        oldLedger.synchronize(victim.hp/20,1,0,0,false);
        require(Math.abs(oldLedger.damage-.2)<1e-6,"original false MC void damage becomes native health debit");
        victim.hp=20;victim.hits=0;
        for(int i=0;i<100;i++)guarded.invoke(victim);
        require(victim.hp==20 && victim.hits==0,"server player at deep canyon stays free of MC void damage");
        var ledger=new HealthLedger();ledger.synchronize(1,1,0,0,false);ledger.synchronize(victim.hp/20,1,0,0,false);
        require(ledger.damage==0,"protected canyon cannot publish a phantom native health debit");
        victim.world=clientWorld;guarded.invoke(victim);
        require(victim.hp==20 && victim.hits==0,"client player uses the same guard");
        victim.player=false;victim.nativeActor=true;victim.id=UUID.randomUUID();victim.world=serverWorld;guarded.invoke(victim);
        require(victim.hp==20 && victim.hits==0,"native enemy proxy below build height does not forward a phantom attack");
        victim.player=true;victim.nativeActor=false;guarded.invoke(victim);
        require(victim.hp==16,"another player is not granted native ownership");
        victim.id=owner;victim.hp=20;victim.world=new World();guarded.invoke(victim);
        require(victim.hp==16,"another dimension retains ordinary void damage");
        victim.world=serverWorld;victim.hp=20;Bridge.connected=false;guarded.invoke(victim);
        require(victim.hp==16,"disabled or disconnected bridge retains vanilla void damage");
        Bridge.connected=true;Bridge.epoch=456;victim.hp=20;guarded.invoke(victim);
        require(victim.hp==16,"old session cannot protect a new map or respawn epoch");
        Bridge.epoch=123;victim.player=false;victim.nativeActor=false;victim.hp=20;guarded.invoke(victim);
        require(victim.hp==16,"ordinary MC entities retain their own damage rules");
        victim.player=true;victim.hp=20;victim.y=-128;guarded.invoke(victim);
        require(victim.hp==20,"vanilla threshold boundary is unchanged");
        Bridge.connected=false;victim.y=-129;guarded.invoke(victim);
        require(victim.hp==16,"unbridged coordinate below threshold still takes vanilla damage");
        Bridge.connected=true;victim.hp=20;victim.damage(new Source("mob_attack"),6);
        require(victim.hp==14,"real incoming attacks remain damageable");
        victim.damage(new Source("fall"),4);
        require(victim.hp==10,"MC fall damage is not globally suppressed");
        victim.damage(new Source("generic_kill"),Float.MAX_VALUE);
        require(victim.hp==0,"native death settlement remains effective");
        require(!new NativeVoidPolicy.Scope(0,owner,clientWorld,serverWorld).owns(true,0,serverWorld,owner,true,false),"invalid epoch cannot confer ownership");
        require(!Bridge.scope.owns(true,123,null,owner,true,false),"missing world cannot confer ownership");
        require(!new NativeVoidPolicy.Scope(123,null,clientWorld,serverWorld).owns(true,123,serverWorld,owner,true,false),"missing player identity cannot protect a player");
        Bridge.scope=null;victim.hp=20;guarded.invoke(victim);
        require(victim.hp==16,"cleared world/session restores the original height test");
        System.out.println(checks+" original vanilla void, compiled mixin and health feedback checks passed (no game launched)");
    }
}
