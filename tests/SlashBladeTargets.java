import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import java.util.function.Predicate;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;

/** Run the installed SlashBlade's actual target predicate without starting MC.
 * Only registry/config/entity accessors are replaced by non-world shells. The
 * selection branches, including RevengeAttacker's one-shot exception, are not
 * reimplemented. Also check the shipped proxy hierarchy in production names. */
public class SlashBladeTargets {
    static int checks;
    static void check(boolean ok,String what){++checks;if(!ok)throw new AssertionError(what);}
    public interface Monster {}
    public static class Entity {}
    public static class Team {}
    public static class Tag {}
    public static class Type {boolean blacklist;public boolean isIn(Tag tag){return blacklist;}}
    public static class Living extends Entity {
        final Set<String> tags=new HashSet<>();final Type type=new Type();boolean playerPassenger;
        public Set<String> getCommandTags(){return tags;}
        public boolean removeCommandTag(String s){return tags.remove(s);}
        public boolean hasPassengerDeep(Predicate<Entity> predicate){return playerPassenger && predicate.test(new Player());}
        public boolean isGlowing(){return false;}
        public Team getScoreboardTeam(){return null;}
        public Type getType(){return type;}
    }
    public static class Enemy extends Living implements Monster {}
    public static class Player extends Living {}
    public static class ArmorStand extends Living {public boolean standTarget(){return false;}}
    public static class Value {boolean value;public Object get(){return value;}}
    public static class Config {public static final Value PVP_ENABLE=new Value(),FRIENDLY_ENABLE=new Value();}
    public static class Tags {public static final Tag ATTACKABLE_BLACKLIST=new Tag();}
    static byte[] entry(JarFile jar,String name)throws Exception{
        var e=jar.getJarEntry(name);if(e==null)throw new AssertionError("Missing "+name);
        return jar.getInputStream(e).readAllBytes();
    }
    static List<String> interfaces(byte[] bytes){
        var result=new ArrayList<String>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9){
            public void visit(int version,int access,String name,String signature,String parent,String[] implemented){
                result.addAll(Arrays.asList(implemented));
            }
        },ClassReader.SKIP_CODE);
        return result;
    }
    public static class Loader extends ClassLoader {
        Loader(){super(SlashBladeTargets.class.getClassLoader());}
        Class<?> decision(byte[] code){return defineClass("SlashBladeDecision",code,0,code.length);}
    }
    public static void main(String[] args)throws Exception{
        try(var mod=new JarFile(args[0])){
            var enemy=entry(mod,"dev/sekirobridge/NativeEnemyProxy.class");
            var neutral=entry(mod,"dev/sekirobridge/NativeActorProxy.class");
            check(interfaces(enemy).contains("net/minecraft/class_1569"),"shipped enemy has Minecraft Monster marker");
            check(!interfaces(neutral).contains("net/minecraft/class_1569"),"neutral NPC is not classified as a monster");
            check(new ClassReader(enemy).getSuperName().equals("dev/sekirobridge/NativeActorProxy"),"enemy uses the existing bridge damage path");
        }
        if(args.length<2){System.out.println(checks+" production proxy checks; optional SlashBlade JAR not supplied");return;}
        byte[] original;
        try(var slash=new JarFile(args[1])){
            original=entry(slash,"mods/flammpfeil/slashblade/util/TargetSelector$AttackablePredicate.class");
        }
        var types=Map.ofEntries(
            Map.entry("mods/flammpfeil/slashblade/util/TargetSelector$AttackablePredicate","SlashBladeDecision"),
            Map.entry("net/minecraft/class_1297","SlashBladeTargets$Entity"),
            Map.entry("net/minecraft/class_1309","SlashBladeTargets$Living"),
            Map.entry("net/minecraft/class_1569","SlashBladeTargets$Monster"),
            Map.entry("net/minecraft/class_1657","SlashBladeTargets$Player"),
            Map.entry("net/minecraft/class_1531","SlashBladeTargets$ArmorStand"),
            Map.entry("net/minecraft/class_270","SlashBladeTargets$Team"),
            Map.entry("net/minecraft/class_1299","SlashBladeTargets$Type"),
            Map.entry("net/minecraft/class_6862","SlashBladeTargets$Tag"),
            Map.entry("mods/flammpfeil/slashblade/SlashBladeConfig","SlashBladeTargets$Config"),
            Map.entry("net/minecraftforge/common/ForgeConfigSpec$BooleanValue","SlashBladeTargets$Value"),
            Map.entry("mods/flammpfeil/slashblade/data/tag/SlashBladeEntityTypeTagProvider$EntityTypeTags","SlashBladeTargets$Tags"));
        var methods=Map.of("method_5752","getCommandTags","method_5738","removeCommandTag", "method_5703","hasPassengerDeep",
            "method_5851","isGlowing","method_5781","getScoreboardTeam","method_5864","getType",
            "method_20210","isIn","method_6912","standTarget");
        var remapper=new Remapper(){
            public String map(String type){return types.getOrDefault(type,type);}
            public String mapMethodName(String owner,String name,String descriptor){return methods.getOrDefault(name,name);}
        };
        var writer=new ClassWriter(0);new ClassReader(original).accept(new ClassRemapper(writer,remapper),0);
        var c=new Loader().decision(writer.toByteArray());var predicate=c.getConstructor().newInstance();
        var test=c.getMethod("test",Living.class);
        var freshEnemy=new Enemy();var neutral=new Living();
        check(!(boolean)test.invoke(predicate,neutral),"original bug: undamaged base proxy rejected when friendly fire is off");
        neutral.tags.add("RevengeAttacker");
        check((boolean)test.invoke(predicate,neutral),"reproduce previous-hit exception from original SlashBlade bytecode");
        check(!neutral.tags.contains("RevengeAttacker") && !(boolean)test.invoke(predicate,neutral),"exception is consumed, not reliable hostile classification");
        for(int i=0;i<3;++i)check((boolean)test.invoke(predicate,freshEnemy),"fresh enemy accepted without being damaged or given revenge tags");
        check((boolean)test.invoke(predicate,new Enemy()),"replacement enemy after load needs no warmup hit");
        check(!(boolean)test.invoke(predicate,new Player()),"PVP exclusion retained");
        freshEnemy.type.blacklist=true;check(!(boolean)test.invoke(predicate,freshEnemy),"SlashBlade blacklist retained");freshEnemy.type.blacklist=false;
        freshEnemy.playerPassenger=true;check(!(boolean)test.invoke(predicate,freshEnemy),"player passenger safety retained");
        check(!(boolean)test.invoke(predicate,new Living()),"friendly native NPC still rejected by area selector");
        System.out.println(checks+" proxy and original SlashBlade targeting checks passed (no game launched)");
    }
}
