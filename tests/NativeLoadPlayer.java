package dev.sekirobridge;

import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;

/** Execute the compiled production PlayerSync with windowless client/server shells. */
public final class NativeLoadPlayer {
    private static final String PREFIX="dev/sekirobridge/NativeLoadPlayer$",SYNC="dev/sekirobridge/LoadSyncFixture";
    private static int checks;
    // Vec3d fields are accessed directly by the compiled class.
    public static final class Vec {
        public static final Vec ZERO=new Vec(0,0,0);
        public final double x,y,z;public Vec(double x,double y,double z){this.x=x;this.y=y;this.z=z;}
        public Vec add(Vec v){return new Vec(x+v.x,y+v.y,z+v.z);}
        public Vec multiply(double s){return new Vec(x*s,y*s,z*s);}
    }
    public static class Pos {public static Pos ofFloored(Vec v){return new Pos();}}
    public static class Box {public Box(Pos p){}public boolean intersects(Box b){return false;}}
    public static class BlockState {}
    public static class Block {public BlockState getDefaultState(){return new BlockState();}}
    public static class Blocks {public static final Block GRASS_BLOCK=new Block();}
    public static class World {
        public boolean isInBuildLimit(Pos p){return false;}
        public boolean isAir(Pos p){return true;}
        public boolean setBlockState(Pos p,BlockState s){return true;}
    }
    public enum Rotation {X_ROT,Y_ROT;public static final Set<Rotation> ROT=Set.of(X_ROT,Y_ROT);}
    public static class Handler {
        final Player player;public int teleports;Handler(Player p){player=p;}
        public void requestTeleport(double x,double y,double z,float yaw,float pitch,Set<Rotation> flags){
            teleports++;player.setPosition(x,y,z);
        }
    }
    public static class Player {
        public double prevX,prevY,prevZ,lastRenderX,lastRenderY,lastRenderZ;
        public float prevYaw,prevPitch,fallDistance;
        public boolean noClip,noGravity,alive=true;
        public Handler networkHandler=new Handler(this);
        public Vec pos=new Vec(5,70,6),velocity=Vec.ZERO;
        public final World world=new World();public UUID id=UUID.randomUUID();public int teleports,positions;
        private float yaw,pitch;
        public UUID getUuid(){return id;}public World getServerWorld(){return world;}
        public boolean hasNoGravity(){return noGravity;}public void setNoGravity(boolean v){noGravity=v;}
        public boolean isAlive(){return alive;}public void setVelocity(Vec v){velocity=v;}
        public Vec getPos(){return pos;}public double getX(){return pos.x;}public double getY(){return pos.y;}public double getZ(){return pos.z;}
        public void setPosition(double x,double y,double z){positions++;pos=new Vec(x,y,z);}
        public float getYaw(){return yaw;}public float getPitch(){return pitch;}
        public void setYaw(float v){yaw=v;}public void setPitch(float v){pitch=v;}
        public void teleport(World w,double x,double y,double z,float yaw,float pitch){teleports++;setPosition(x,y,z);this.yaw=yaw;this.pitch=pitch;}
        public Vec getEyePos(){return pos;}public Vec getRotationVec(float delta){return new Vec(0,0,1);}
        public Box getBoundingBox(){return new Box(new Pos());}
    }
    public static class Manager {public Player player;public Player getPlayer(UUID id){return player;}}
    public static class Server {public final Manager manager=new Manager();public Manager getPlayerManager(){return manager;}}
    public static class Client {public static final Client INSTANCE=new Client();public Player player;public static Client getInstance(){return INSTANCE;}}
    public static class Bridge {public static boolean held;public static long revision;public static boolean loading(){return held;}public static long loadRevision(){return revision;}}
    public static class Terrain {public static int seeds;public static void seed(Protocol.State s,Vec v){seeds++;}public static void clear(){}}
    private static final class Loader extends ClassLoader {
        Loader(){super(NativeLoadPlayer.class.getClassLoader());}
        Class<?> load(byte[] bytes){return defineClass(SYNC.replace('/','.'),bytes,0,bytes.length);}
    }
    private static void check(boolean ok,String reason){checks++;if(!ok)throw new AssertionError(reason);}
    private static Protocol.State pose(long seq,long epoch,int flags,float x,float y,float z){
        return new Protocol.State(seq,1000+seq,epoch,flags,256,x,y,z,x,y+1.6f,z,0,0,1,
            1,1.7f,.1f,1000,128,1,1280,720,new byte[32],0,0,0,0,0,0,new int[8],0);
    }
    public static void main(String[] args)throws Exception {
        var aliases=new HashMap<String,String>();
        aliases.put("dev/sekirobridge/PlayerSync",SYNC);
        for(var entry:Map.ofEntries(
            Map.entry("net/minecraft/client/MinecraftClient","Client"),
            Map.entry("net/minecraft/client/network/ClientPlayerEntity","Player"),
            Map.entry("net/minecraft/entity/Entity","Player"),
            Map.entry("net/minecraft/server/network/ServerPlayerEntity","Player"),
            Map.entry("net/minecraft/server/MinecraftServer","Server"),
            Map.entry("net/minecraft/server/PlayerManager","Manager"),
            Map.entry("net/minecraft/server/network/ServerPlayNetworkHandler","Handler"),
            Map.entry("net/minecraft/server/world/ServerWorld","World"),
            Map.entry("net/minecraft/world/World","World"),
            Map.entry("net/minecraft/util/math/Vec3d","Vec"),
            Map.entry("net/minecraft/util/math/Position","Vec"),
            Map.entry("net/minecraft/util/math/BlockPos","Pos"),
            Map.entry("net/minecraft/util/math/Box","Box"),
            Map.entry("net/minecraft/block/Blocks","Blocks"),
            Map.entry("net/minecraft/block/Block","Block"),
            Map.entry("net/minecraft/block/BlockState","BlockState"),
            Map.entry("net/minecraft/network/packet/s2c/play/PositionFlag","Rotation"),
            Map.entry("dev/sekirobridge/BridgeClient","Bridge"),
            Map.entry("dev/sekirobridge/NativeTerrain","Terrain")).entrySet())aliases.put(entry.getKey(),PREFIX+entry.getValue());
        var remapper=new Remapper(){@Override public String map(String name){return aliases.getOrDefault(name,name);}};
        var writer=new ClassWriter(0);
        new ClassReader(Files.readAllBytes(Path.of(args[0],"dev/sekirobridge/PlayerSync.class")))
            .accept(new ClassRemapper(writer,remapper),0);
        var type=new Loader().load(writer.toByteArray());var constructor=type.getDeclaredConstructor();constructor.setAccessible(true);
        var sync=constructor.newInstance();
        var client=type.getDeclaredMethod("client",Protocol.State.class);client.setAccessible(true);
        var serverTick=type.getDeclaredMethod("server",Server.class,Protocol.State.class);serverTick.setAccessible(true);
        var reset=type.getDeclaredMethod("reset");reset.setAccessible(true);
        var cp=new Player();var sp=new Player();sp.id=cp.id;Client.INSTANCE.player=cp;
        var server=new Server();server.manager.player=sp;var old=sp.getPos();
        var initial=pose(1,7,Protocol.SCENE,2,10,3);client.invoke(sync,initial);serverTick.invoke(sync,server,initial);
        check(cp.getY()==138 && sp.getY()==138,"initial native pose is mapped on both real production paths");
        int initialTeleports=sp.teleports,initialPositions=cp.positions;Bridge.held=true;
        cp.velocity=sp.velocity=new Vec(0,-10,0);cp.fallDistance=sp.fallDistance=60;
        for(int i=0;i<100;i++){
            var unloaded=pose(i+2,8,0,0,-999,0);client.invoke(sync,unloaded);serverTick.invoke(sync,server,unloaded);
        }
        check(cp.getY()==138 && sp.getY()==138,"unloaded or changed-epoch native coordinates cannot move the MC player into void");
        check(sp.teleports==initialTeleports && cp.positions==initialPositions,"loading cannot release/reacquire or spam native teleports");
        check(cp.noGravity && sp.noGravity && cp.velocity==Vec.ZERO && sp.velocity==Vec.ZERO,"client/server freeze gravity and velocity");
        check(cp.fallDistance==0 && sp.fallDistance==0,"loading cannot accumulate fall damage");
        Bridge.held=false;Bridge.revision=1;var resumed=pose(200,7,Protocol.SCENE,20,40,30);
        client.invoke(sync,resumed);serverTick.invoke(sync,server,resumed);
        check(cp.getX()==20 && sp.getX()==20 && cp.getY()==168 && sp.getY()==168,"same-epoch resume explicitly reseeds both sides to the destination");
        check(!cp.noGravity && !sp.noGravity,"resume restores normal gravity");
        check(cp.prevY==168 && cp.lastRenderY==168,"client interpolation does not bridge the old and new maps");
        check(Terrain.seeds==2 && sp.networkHandler.teleports==1,"new terrain and server correction occur exactly once");
        cp.setPosition(21,168,-30);sp.setPosition(21,168,-30);
        client.invoke(sync,resumed);serverTick.invoke(sync,server,resumed);
        check(cp.getX()==21 && sp.getX()==21,"walking after resume is no longer snapped back to native pose");
        cp.noGravity=true;Bridge.held=true;client.invoke(sync,resumed);serverTick.invoke(sync,server,resumed);
        Bridge.held=false;reset.invoke(sync);serverTick.invoke(sync,server,(Object)null);
        check(cp.noGravity && !sp.noGravity,"off restores each player's pre-hold gravity, including custom gravity state");
        check(sp.getX()==old.x && sp.getY()==old.y && sp.getZ()==old.z,"off releases the server player back to its original MC position");
        System.out.println(checks+" compiled PlayerSync client/server loading checks passed (no game launched)");
    }
}
