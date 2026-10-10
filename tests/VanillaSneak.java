package dev.sekirobridge;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import net.minecraft.entity.EntityPose;
import net.minecraft.util.math.Box;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;

/** Replays the actual MC pose selection against sampled floor and real voxel boxes. */
public final class VanillaSneak {
    private static final String FIXTURE="dev/sekirobridge/PosePlayerFixture";
    private static int checks;
    public static final class Abilities {public boolean flying;}
    public static class Player {
        public final Abilities abilities=new Abilities();
        public boolean sneak,fallFlying,sleeping,swimming,riptide,spectator,vehicle;
        public EntityPose pose=EntityPose.STANDING;
        public final World world=new World();
        public World getWorld(){return world;}
        public boolean isFallFlying(){return fallFlying;}
        public boolean isSleeping(){return sleeping;}
        public boolean isSwimming(){return swimming;}
        public boolean isUsingRiptide(){return riptide;}
        public boolean isSneaking(){return sneak;}
        public boolean isSpectator(){return spectator;}
        public boolean hasVehicle(){return vehicle;}
        public void setPose(EntityPose value){pose=value;}
        public Box calculateBoundsForPose(EntityPose value){
            double height=value==EntityPose.CROUCHING?1.5:value==EntityPose.STANDING?1.8:.6;
            return new Box(-.3,0,-.3,.3,height,.3);
        }
    }
    public static final class World {
        public boolean scoped,owner=true;
        public Box nativeFloor=new Box(-1,-4,-1,1,.1,1);
        public List<Box> mcSolids=List.of();
        public boolean isSpaceEmpty(Player p,Box query){
            var test=(java.util.function.BooleanSupplier)()->{
                for(var solid:mcSolids)if(solid.intersects(query))return false;
                if(!owner)return true;
                var surface=new TerrainGeometry.Surface(1000,nativeFloor);
                boolean blocked=TerrainPoseProbe.active(p)?
                    TerrainGeometry.blocksPose(surface,p.calculateBoundsForPose(p.pose),query,1000,.6):nativeFloor.intersects(query);
                return !blocked;
            };
            return scoped?TerrainPoseProbe.test(p,test):test.getAsBoolean();
        }
    }
    private static void check(boolean ok,String reason){checks++;if(!ok)throw new AssertionError(reason);}
    private static ClassNode remap(byte[] bytes,Remapper remapper){
        var node=new ClassNode();new ClassReader(bytes).accept(new ClassRemapper(node,remapper),0);return node;
    }
    public static void main(String[] args)throws Exception {
        String prefix="dev/sekirobridge/VanillaSneak$";
        var aliases=Map.of("net/minecraft/entity/player/PlayerEntity",FIXTURE,
            "net/minecraft/entity/Entity",prefix+"Player","net/minecraft/world/World",prefix+"World",
            "net/minecraft/entity/player/PlayerAbilities",prefix+"Abilities");
        var remapper=new Remapper(){@Override public String map(String name){return aliases.getOrDefault(name,name);}};
        var writer=new ClassWriter(0);
        writer.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,FIXTURE,null,prefix+"Player",null);
        var init=writer.visitMethod(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);
        init.visitCode();init.visitVarInsn(Opcodes.ALOAD,0);init.visitMethodInsn(Opcodes.INVOKESPECIAL,prefix+"Player","<init>","()V",false);
        init.visitInsn(Opcodes.RETURN);init.visitMaxs(1,1);init.visitEnd();
        try(var game=new JarFile(Path.of(args[0]).toFile())){
            for(var entry:Map.of("net/minecraft/entity/player/PlayerEntity.class","updatePose",
                "net/minecraft/entity/Entity.class","wouldPoseNotCollide").entrySet()){
                var node=remap(game.getInputStream(game.getJarEntry(entry.getKey())).readAllBytes(),remapper);
                var method=node.methods.stream().filter(m->m.name.equals(entry.getValue())).findFirst().orElseThrow();
                method.accept(writer);
            }
        }
        writer.visitEnd();byte[] bytes=writer.toByteArray();
        var loader=new ClassLoader(VanillaSneak.class.getClassLoader()){
            Class<?> define(){return defineClass(FIXTURE.replace('/','.'),bytes,0,bytes.length);}
        };
        var type=loader.define();var player=(Player)type.getConstructor().newInstance();
        Method update=type.getDeclaredMethod("updatePose");update.setAccessible(true);
        player.sneak=true;update.invoke(player);
        check(player.pose==EntityPose.STANDING,"reproduce original floor overlap vetoing vanilla crouch");
        player.world.scoped=true;update.invoke(player);
        check(player.pose==EntityPose.CROUCHING,"vanilla selects crouch after the bounded floor probe fix");
        player.sneak=false;update.invoke(player);
        check(player.pose==EntityPose.STANDING,"releasing sneak restores standing");
        player.sneak=true;player.abilities.flying=true;update.invoke(player);
        check(player.pose==EntityPose.STANDING,"creative flight retains vanilla sneak-to-descend semantics");
        player.abilities.flying=false;player.fallFlying=true;update.invoke(player);
        check(player.pose==EntityPose.FALL_FLYING,"elytra pose is not replaced by forced crouching");
        player.fallFlying=false;player.world.mcSolids=List.of(new Box(-1,1.4,-1,1,2,1));update.invoke(player);
        check(player.pose==EntityPose.SWIMMING,"real MC low ceilings still enforce crawling instead of clipping");
        player.world.mcSolids=List.of();player.pose=EntityPose.STANDING;
        player.world.nativeFloor=new Box(-1,-4,-1,1,.7,1);update.invoke(player);
        check(player.pose==EntityPose.STANDING,"deep penetration remains blocked rather than exempting all terrain");
        player.world.nativeFloor=new Box(-1,-4,-1,1,.1,1);
        var feet=player.calculateBoundsForPose(EntityPose.STANDING);
        check(!TerrainPoseProbe.active(player) && player.world.nativeFloor.intersects(feet),
            "movement still sees the full sampled floor outside pose checks");
        var other=new Object();TerrainPoseProbe.test(player,()->{
            check(TerrainPoseProbe.active(player) && !TerrainPoseProbe.active(other),"pose scope is entity-specific");
            TerrainPoseProbe.test(other,()->{check(TerrainPoseProbe.active(other) && !TerrainPoseProbe.active(player),"nested scope selects the inner entity");return true;});
            check(TerrainPoseProbe.active(player),"nested scope restores the outer entity");return true;
        });
        check(!TerrainPoseProbe.active(player),"pose scope cannot leak into movement checks");
        try{TerrainPoseProbe.test(player,()->{throw new IllegalStateException("test");});}catch(IllegalStateException expected){}
        check(!TerrainPoseProbe.active(player),"exception restores ordinary collision checks");
        player.world.owner=false;player.pose=EntityPose.STANDING;update.invoke(player);
        check(player.pose==EntityPose.CROUCHING,"ordinary MC player follows vanilla crouch without native terrain");
        System.out.println(checks+" actual vanilla pose and scoped floor checks passed (no game launched)");
    }
}
