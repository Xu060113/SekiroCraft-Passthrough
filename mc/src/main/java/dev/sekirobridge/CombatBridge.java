package dev.sekirobridge;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

public final class CombatBridge {
    public static final EntityType<NativeActorProxy> TYPE=Registry.register(Registries.ENTITY_TYPE,
        new Identifier("sekirobridge","native_actor"),EntityType.Builder
            .<NativeActorProxy>create(NativeActorProxy::new,SpawnGroup.MISC).setDimensions(.6f,1.8f)
            .maxTrackingRange(32).trackingTickInterval(1).disableSaving().disableSummon().build("sekirobridge:native_actor"));
    private static final ByteBuffer incoming=Protocol.direct(CombatProtocol.STATE_BYTES);
    private static volatile CombatProtocol.State state;
    private static volatile byte[] outgoing;
    private static volatile boolean serverActive;
    private static final Map<Long,NativeActorProxy> proxies=new HashMap<>();
    private static final HealthLedger health=new HealthLedger();
    private static final NativeHurtFeedback hurtFeedback=new NativeHurtFeedback();
    private static final AtomicLong sessions=new AtomicLong(System.nanoTime()&Long.MAX_VALUE);
    private static long session,hero,epoch,command,ackCommand;
    private static ServerPlayerEntity owner;
    private static final ByteBuffer report=Protocol.direct(CombatProtocol.REPORT_BYTES);
    private CombatBridge(){}
    public static void registerEntities(){
        FabricDefaultAttributeRegistry.register(TYPE,MobEntity.createMobAttributes());
    }
    public static void initialize(){
        EntityRendererRegistry.register(TYPE,context -> new EntityRenderer<NativeActorProxy>(context){
            @Override public Identifier getTexture(NativeActorProxy actor){return new Identifier("minecraft","textures/misc/white.png");}
            @Override public void render(NativeActorProxy actor,float yaw,float delta,
                net.minecraft.client.util.math.MatrixStack matrices,net.minecraft.client.render.VertexConsumerProvider buffers,int light){}
        });
    }
    private static boolean usable(){var s=BridgeClient.state();var c=MinecraftClient.getInstance();
        return BridgeClient.armed() && s!=null && (s.capabilities()&1024)!=0 && Protocol.fresh(NativeBridge.clockMs(),s.tickMs()) &&
            c.world!=null && c.player!=null && c.getServer()!=null;
    }
    /** JNI stays on the client thread; the server publishes immutable report bytes. */
    public static void poll(){
        if(!usable()){state=null;return;}
        if(NativeBridge.combatState(BridgeClient.handle(),incoming)){
            var next=CombatProtocol.decode(incoming);
            if(next!=null && next.epoch()==BridgeClient.state().epoch() && Protocol.fresh(NativeBridge.clockMs(),next.tick()))state=next;
        }
        var bytes=outgoing;if(bytes!=null){var buffer=Protocol.direct(bytes.length);buffer.put(bytes);
            // The integrated server can pause while the visible peer is still alive.
            // Refresh only the transport heartbeat, never health totals or queued hits.
            buffer.putLong(0,NativeBridge.clockMs());
            NativeBridge.combatReport(BridgeClient.handle(),buffer);}
    }
    public static boolean serverActive(){return serverActive;}
    static CombatProtocol.State snapshot(){return state;}
    public static String status(){var s=state;return s==null?"waiting":("native HP="+s.hp()+"/"+s.maxHp()+" actors="+s.actors().size());}
    public static void hit(long actor,long expectedEpoch,float amount){
        if(!serverActive || expectedEpoch!=epoch || !Float.isFinite(amount) || amount<=0 || amount>10000)return;
        if(command-ackCommand>=CombatProtocol.SLOTS)return; // Never overwrite unacknowledged hits.
        int o=64+(int)(command%CombatProtocol.SLOTS)*24;
        report.putLong(o,++command).putLong(o+8,actor).putFloat(o+16,amount).putInt(o+20,0);
    }
    public static void server(MinecraftServer server){
        var s=state;var pose=BridgeClient.state();var client=MinecraftClient.getInstance();long now=NativeBridge.clockMs();
        if(!usable() || s==null || pose==null || s.epoch()!=pose.epoch() || !Protocol.fresh(now,s.tick()) || (s.flags()&1)==0){release();return;}
        var p=client.player==null?null:server.getPlayerManager().getPlayer(client.player.getUuid());
        if(p==null || p.getServerWorld().getRegistryKey()!=client.world.getRegistryKey()){release();return;}
        if(owner!=p || hero!=s.hero() || epoch!=s.epoch()){
            release();owner=p;hero=s.hero();epoch=s.epoch();session=sessions.incrementAndGet();
            for(int i=0;i<report.capacity();++i)report.put(i,(byte)0);
        }
        serverActive=true;
        boolean immune=p.isCreative() || p.isSpectator();
        ackCommand=s.ackSession()==session?Math.min(command,s.ackCommand()):0;
        double ad=s.ackSession()==session?s.ackDamage():0,ah=s.ackSession()==session?s.ackHeal():0;
        boolean nativeHurt=hurtFeedback.update((double)s.hp()/s.maxHp(),ad,ah,immune);
        float maximum=p.getMaxHealth();
        double ratio=health.synchronize(p.getHealth()/maximum,(double)s.hp()/s.maxHp(),ad,ah,immune);
        // Use vanilla's death path, rather than setting zero and bypassing onDeath.
        if(s.hp()==0 && p.isAlive() && !immune)p.damage(p.getDamageSources().genericKill(),Float.MAX_VALUE);
        else if(p.isAlive())p.setHealth((float)(ratio*maximum));
        if(nativeHurt && p.isAlive())p.networkHandler.sendPacket(
            new net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket(p,p.getDamageSources().generic()));
        var keep=new HashSet<Long>();
        if(BridgeClient.connected())for(var a:s.actors()){
            if(a.hp()==0)continue;keep.add(a.id());var proxy=proxies.get(a.id());
            if(proxy==null || proxy.isRemoved() || proxy.getWorld()!=p.getWorld()){
                if(proxy!=null)proxy.discard();proxy=TYPE.create(p.getServerWorld());if(proxy==null)continue;
                proxy.nativeId=a.id();proxy.epoch=epoch;proxies.put(a.id(),proxy);
                proxy.refreshPositionAndAngles(pose.mcX(a.x()),pose.mcY(a.y()),pose.mcZ(a.z()),0,0);
                p.getServerWorld().spawnEntity(proxy);
            }
            proxy.nativeFlags=a.flags();proxy.setHealth(Math.max(.001f,20f*a.hp()/a.maxHp()));
            proxy.refreshPositionAndAngles(pose.mcX(a.x()),pose.mcY(a.y()),pose.mcZ(a.z()),0,0);
        }
        proxies.entrySet().removeIf(entry -> {if(keep.contains(entry.getKey()))return false;entry.getValue().discard();return true;});
        report.putLong(0,now).putLong(8,epoch).putLong(16,hero).putLong(24,session)
            .putDouble(32,health.damage).putDouble(40,health.heal).putLong(48,command).putInt(56,immune?1:0).putInt(60,0);
        var bytes=new byte[report.capacity()];var view=report.duplicate();view.clear();view.get(bytes);outgoing=bytes;
    }
    /** Server thread only: removes ephemeral actors and discards its delta ledger. */
    public static void release(){serverActive=false;for(var proxy:proxies.values())proxy.discard();proxies.clear();
        owner=null;hero=epoch=command=ackCommand=0;health.reset();hurtFeedback.reset();outgoing=null;}
    public static void resetClient(){state=null;outgoing=null;}
}
