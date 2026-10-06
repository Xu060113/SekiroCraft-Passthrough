import java.util.zip.ZipFile;
import java.nio.file.Path;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;

/** Execute the unmodified vanilla shield decision bytecode in a non-world shell.
 * The mapped game's registry classes require loader transforms; copying only
 * this method avoids starting a client/server or duplicating its branch logic. */
public class VanillaShield {
    public static class Tag {}
    public static class Tags {public static final Tag BYPASSES_SHIELD=new Tag();}
    public static class Entity {}
    public static class Projectile extends Entity {public byte getPierceLevel(){return 1;}}
    public static class V {
        public final double x,y,z;
        public V(double x,double y,double z){this.x=x;this.y=y;this.z=z;}
        public V relativize(V other){return new V(other.x-x,other.y-y,other.z-z);}
        public V normalize(){double n=Math.sqrt(x*x+y*y+z*z);return n<1e-9?new V(0,0,0):new V(x/n,y/n,z/n);}
        public double dotProduct(V v){return x*v.x+y*v.y+z*v.z;}
    }
    public static class Source {
        V position;boolean bypass,piercing;
        public Source(V position){this.position=position;}
        public Entity getSource(){return piercing?new Projectile():null;}
        public boolean isIn(Tag tag){return bypass;}
        public V getPosition(){return position;}
    }
    public static class Shell {
        public boolean blocking=true;
        public boolean isBlocking(){return blocking;}
        public V getRotationVec(float tick){return new V(0,0,1);}
        public V getPos(){return new V(0,0,0);}
    }
    public static class Loader extends ClassLoader {
        Loader(){super(VanillaShield.class.getClassLoader());}
        Class<?> load(byte[] bytes){return defineClass("ShieldDecision",bytes,0,bytes.length);}
    }
    public static void main(String[] args)throws Exception {
        byte[] original;
        try(var jar=new ZipFile(Path.of(args[0]).toFile())){
            original=jar.getInputStream(jar.getEntry("net/minecraft/entity/LivingEntity.class")).readAllBytes();
        }
        var writer=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,"ShieldDecision",null,"VanillaShield$Shell",null);
        var ctor=writer.visitMethod(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);
        ctor.visitCode();ctor.visitVarInsn(Opcodes.ALOAD,0);ctor.visitMethodInsn(Opcodes.INVOKESPECIAL,"VanillaShield$Shell","<init>","()V",false);
        ctor.visitInsn(Opcodes.RETURN);ctor.visitMaxs(0,0);ctor.visitEnd();
        var remapper=new Remapper(){@Override public String map(String type){return switch(type){
            case "net/minecraft/entity/LivingEntity" -> "ShieldDecision";
            case "net/minecraft/entity/Entity" -> "VanillaShield$Entity";
            case "net/minecraft/entity/projectile/PersistentProjectileEntity" -> "VanillaShield$Projectile";
            case "net/minecraft/entity/damage/DamageSource" -> "VanillaShield$Source";
            case "net/minecraft/registry/tag/DamageTypeTags" -> "VanillaShield$Tags";
            case "net/minecraft/registry/tag/TagKey" -> "VanillaShield$Tag";
            case "net/minecraft/util/math/Vec3d" -> "VanillaShield$V";
            default -> type;
        };}};
        int[] found={0};
        new ClassReader(original).accept(new ClassVisitor(Opcodes.ASM9){
            @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions){
                if(!name.equals("blockedByShield"))return null;++found[0];
                return new MethodRemapper(writer.visitMethod(access,name,remapper.mapMethodDesc(descriptor),null,null),remapper);
            }
        },ClassReader.SKIP_FRAMES|ClassReader.SKIP_DEBUG);
        if(found[0]!=1)throw new AssertionError("exact vanilla shield method missing");
        writer.visitEnd();var decision=new Loader().load(writer.toByteArray());
        var player=(Shell)decision.getConstructor().newInstance();var method=decision.getMethod("blockedByShield",Source.class);
        var front=new Source(new V(0,0,4));var rear=new Source(new V(0,0,-4));
        if(!(boolean)method.invoke(player,front))throw new AssertionError("front attack not blocked");
        if((boolean)method.invoke(player,rear))throw new AssertionError("rear attack incorrectly blocked");
        if((boolean)method.invoke(player,new Source(null)))throw new AssertionError("unknown source grants full-body immunity");
        front.bypass=true;if((boolean)method.invoke(player,front))throw new AssertionError("unblockable attack blocked");front.bypass=false;
        front.piercing=true;if((boolean)method.invoke(player,front))throw new AssertionError("piercing arrow blocked");front.piercing=false;
        // Native +Z travel arrives from -Z, which flips to +Z in MC. The
        // trajectory fallback supplies that side even without a source entity.
        var projectileFront=new Source(new V(0,0,2));
        var projectileRear=new Source(new V(0,0,-2));
        if(!(boolean)method.invoke(player,projectileFront))throw new AssertionError("ownerless front projectile not blocked");
        if((boolean)method.invoke(player,projectileRear))throw new AssertionError("ownerless rear projectile incorrectly blocked");
        player.blocking=false;if((boolean)method.invoke(player,front))throw new AssertionError("released shield blocked");
        if((boolean)method.invoke(player,projectileFront))throw new AssertionError("released shield blocked projectile");
        System.out.println("9 original vanilla shield bytecode checks passed (no world/client/server launched)");
    }
}
