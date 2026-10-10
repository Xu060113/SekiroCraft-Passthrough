package dev.sekirobridge;

import com.mojang.brigadier.Command;
import static net.minecraft.server.command.CommandManager.literal;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.Perspective;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Properties;

public final class BridgeClient {
    public static final Logger LOG = LoggerFactory.getLogger("sekirobridge");
    private static volatile long handle;
    private static final ByteBuffer control = Protocol.direct(Protocol.CONTROL_BYTES);
    private static volatile Protocol.State state;
    private static Protocol.State renderPose;
    private static boolean wasConnected;
    private static volatile boolean nativeOwned;
    private static final NativeLoadGate LOAD = new NativeLoadGate();
    private static float frameFov=Float.NaN;
    private static volatile boolean armed;
    private static Perspective previousPerspective;
    private static Object worldIdentity;
    public static final FrameExporter FRAMES = new FrameExporter();
    private static final InputForwarder INPUT = new InputForwarder();
    private static final PlayerSync PLAYERS = new PlayerSync();
    private static final PhysicsExporter PHYSICS = new PhysicsExporter();
    private static HudPreferences hudPreferences;
    public static boolean postureHudVisible(){return hudPreferences==null || hudPreferences.visible();}
    private static int setPostureHud(net.minecraft.server.command.ServerCommandSource source,boolean visible){
        if(hudPreferences==null){source.sendError(Text.literal("界面设置尚未初始化。"));return 0;}
        try{hudPreferences.setVisible(visible);}
        catch(java.io.IOException | IllegalArgumentException e){
            LOG.warn("Could not save posture HUD preference",e);
            source.sendError(Text.literal("无法保存姿态条设置，请检查 MC 游戏目录是否可写。"));return 0;
        }
        feedback(source,Text.literal("MC 姿态条（耐力条）已"+(visible?"显示":"隐藏")+"，设置已保存。"));
        return Command.SINGLE_SUCCESS;
    }
    public static Protocol.State state() { return state; }
    public static Protocol.State renderPose(){return renderPose;}
    public static void renderPose(Protocol.State pose){renderPose=pose;}
    public static float projectionFov(double degrees){
        float f=(float)Math.toRadians(Math.max(2,Math.min(170,degrees)));
        if(!Float.isFinite(frameFov))frameFov=f;
        return f;
    }
    public static float frameFov(){return Float.isFinite(frameFov)?frameFov:state.fov();}
    private static void showHud(){
        var client=MinecraftClient.getInstance();
        client.options.hudHidden=false;
        client.gameRenderer.setRenderHand(true);
    }
    public static long handle() { return handle; }
    public static boolean keyHeld(int key){return INPUT.held(key);}
    public static boolean armed() { return armed && handle != 0; }
    public static boolean cinematic(){var s=state;return s!=null && (s.flags()&Protocol.NATIVE_CINEMATIC)!=0;}
    public static boolean ownsNativePlayer(){
        if(!armed() || !nativeOwned)return false;
        var c=MinecraftClient.getInstance();
        return c.world!=null && c.world==worldIdentity && c.player!=null && c.getServer()!=null;
    }
    public static boolean loading(){var s=state;
        // Server ticks may interleave between publishing a packet and updating
        // the gate. The unavailable packet itself must already imply a hold.
        return ownsNativePlayer() && (LOAD.holding() || s==null || !sceneAvailable() || !Protocol.fresh(NativeBridge.clockMs(),s.tickMs()));
    }
    static long loadRevision(){return LOAD.revision();}
    public static boolean active() {
        return connected() && state.active(NativeBridge.clockMs());
    }
    public static boolean connected(){
        return !loading() && sceneAvailable();
    }
    private static boolean sceneAvailable(){
        return handle != 0 && armed && state != null && state.valid() &&
            NativeBridge.clockMs()>=state.tickMs() && NativeBridge.clockMs()-state.tickMs()<=1500 &&
            (state.flags()&Protocol.SCENE)!=0 &&
            (state.capabilities()&256)!=0 && MinecraftClient.getInstance().player!=null &&
            (state.capabilities()&Protocol.CAMERA_ROLL_CAPABILITY)!=0 &&
            MinecraftClient.getInstance().world != null && MinecraftClient.getInstance().getServer() != null;
    }
    public static void poll() {
        // Forge renders its loading overlay before FMLClientSetupEvent's queued
        // work loads JNI. Also stay dormant if that initialization failed.
        if (handle == 0) return;
        var client = MinecraftClient.getInstance();
        boolean wasLoading=loading();
        if (client.world != worldIdentity) {
            worldIdentity = client.world;
            disarm();
            INPUT.release();
            FRAMES.discard();
        }
        if (handle != 0 && NativeBridge.control(handle, control)) {
            var next = Protocol.decode(control);
            if (next.valid()) {
                if (state != null && next.epoch() != state.epoch()) {
                    INPUT.release();
                    FRAMES.discard();
                }
                state = next;
            }
        }
        boolean available=sceneAvailable();
        if(available)nativeOwned=true;
        LOAD.update(state,NativeBridge.clockMs(),ownsNativePlayer(),available && Protocol.fresh(NativeBridge.clockMs(),state.tickMs()));
        if(loading()!=wasLoading)LOG.info("Native scene load {}: revision={} epoch={}",loading()?"held":"resumed",LOAD.revision(),state==null?0:state.epoch());
        if (!active()) {
            INPUT.release();
            FRAMES.discard();
        }
        boolean connected=connected();
        NativeVoidProtection.refresh();
        if(!connected && wasConnected && !ownsNativePlayer())PLAYERS.reset();
        wasConnected=connected;
        if(connected)NativeTerrain.poll(state);
        ProjectileTerrain.poll();
        AudioBridge.tick();
        CombatBridge.poll();
        InputForwarder.traceGuiResult();
        DeathSync.tick();
        if (handle != 0)
            NativeBridge.status(handle, connected() ? (1 | 8 | (client.currentScreen != null ? 2 : 0) |
                (client.player != null && client.player.getAbilities().flying &&
                 (state.capabilities() & 128) != 0 ? 4 : 0)) : 0,
                                state != null ? state.epoch() : 0);
    }
    public static void renderBegin() {
        if (handle == 0) return;
        poll();
        ForgeWindowPacing.update();
        renderPose=null;
        frameFov=Float.NaN;
        if (ownsNativePlayer())
            PLAYERS.client(state);
        if(active())INPUT.update(state);
    }
    private static void disarm() {
        armed = false;
        nativeOwned=false;LOAD.reset();
        NativeVoidProtection.clear();
        wasConnected=false;
        PLAYERS.reset();renderPose=null;
        AudioBridge.release();CombatBridge.resetClient();
        if (previousPerspective != null) {
            MinecraftClient.getInstance().options.setPerspective(previousPerspective);
            previousPerspective = null;
        }
        ForgeWindowPacing.update();
    }
    public static void initialize() {
        var client = MinecraftClient.getInstance();
        hudPreferences=new HudPreferences(client.runDirectory.toPath().resolve("sekirobridge/bridge.properties"));
        try{hudPreferences.load();}
        catch(java.io.IOException | IllegalArgumentException e){LOG.warn("Could not read HUD settings; using visible posture HUD",e);}
        try{ForgeWindowPacing.initialize(client.runDirectory.toPath().resolve("sekirobridge/bridge.properties"));}
        catch(java.io.IOException | IllegalArgumentException e){LOG.warn("Could not read performance settings; using default window pacing",e);}
        try {
            var root = client.runDirectory.toPath().resolve("sekirobridge");
            Files.createDirectories(root);
            Properties p = new Properties();
            var config = root.resolve("bridge.properties");
            if (Files.exists(config)) {
                try (var input = Files.newInputStream(config)) {
                    p.load(input);
                }
            } else {
                p.setProperty("channel", "default");
                try (var output = Files.newOutputStream(config)) {
                    p.store(output, "Matches sekirobridge.ini channel; Windows x64 only");
                }
            }
            NativeBridge.load(root);
            InputForwarder.guiTrace=Boolean.parseBoolean(p.getProperty("gui_trace","false"));
            long opened = NativeBridge.open(p.getProperty("channel", "default"));
            if (opened == 0)
                throw new IllegalStateException("Shared memory channel could not be opened");
            handle = opened;
            LOG.info("Bridge ready, protocol v{}; dormant until /sekirobridge on in a dedicated " +
                     "single-player world", NativeBridge.abiVersion());
        } catch (Exception | UnsatisfiedLinkError e) {
            LOG.error("Bridge disabled; Minecraft remains usable", e);
            handle = 0;
        }
    }
    private static void feedback(net.minecraft.server.command.ServerCommandSource source,Text message){
        source.sendFeedback(()->message,false);
    }
    public static void registerCommands(RegisterClientCommandsEvent event){
        var client=MinecraftClient.getInstance();
        event.getDispatcher().register(
                    literal("sekirobridge")
                        .then(literal("on").executes(ctx -> {
                            if (handle == 0 || client.getServer() == null || client.world == null) {
                                ctx.getSource().sendError(Text.literal(
                                    "Bridge requires Windows JNI and a local single-player world."));
                                return 0;
                            }
                            worldIdentity = client.world;
                            if(state!=null && state.valid() && (state.flags()&Protocol.SCENE)!=0 &&
                                (state.capabilities()&256)!=0 && (state.capabilities()&Protocol.CAMERA_ROLL_CAPABILITY)==0){
                                ctx.getSource().sendError(Text.literal("只狼端 DLL 版本过旧：此特效版须同时更新配套 dinput8.dll。"));return 0;
                            }
                            if (!armed)
                                previousPerspective = client.options.getPerspective();
                            armed = true;
                            ForgeWindowPacing.update();
                            client.options.setPerspective(Perspective.FIRST_PERSON);
                            showHud();
                            feedback(ctx.getSource(),
                                Text.literal("Minecraft player controls armed: WASD / E inventory / Space jump / F5 view. " +
                                    "Requires the paired MC camera adapter; F8 pauses input; /sekirobridge off releases it."));
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(literal("off").executes(ctx -> {
                            disarm();
                            INPUT.release();
                            FRAMES.discard();
                            feedback(ctx.getSource(),
                                Text.literal("Bridge off; original controls restored."));
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(literal("hud").executes(ctx -> {
                            showHud();
                            feedback(ctx.getSource(),Text.literal("Minecraft HUD and first-person hand rendering enabled. F1 toggles HUD visibility."));
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(literal("stamina")
                            .executes(ctx->{feedback(ctx.getSource(),Text.literal("MC 姿态条（耐力条）："+
                                (postureHudVisible()?"显示":"隐藏")+"。使用 /sekirobridge stamina on、off 或 toggle。"));return Command.SINGLE_SUCCESS;})
                            .then(literal("on").executes(ctx->setPostureHud(ctx.getSource(),true)))
                            .then(literal("off").executes(ctx->setPostureHud(ctx.getSource(),false)))
                            .then(literal("toggle").executes(ctx->setPostureHud(ctx.getSource(),!postureHudVisible()))))
                        .then(literal("performance")
                            .executes(ctx->{feedback(ctx.getSource(),Text.literal("桥接窗口性能优化："+
                                (ForgeWindowPacing.enabled()?"开启":"关闭")+"。使用 /sekirobridge performance on 或 off。"));return Command.SINGLE_SUCCESS;})
                            .then(literal("on").executes(ctx->setPerformance(ctx.getSource(),true)))
                            .then(literal("off").executes(ctx->setPerformance(ctx.getSource(),false))))
                        .then(literal("status").executes(ctx -> {
                            feedback(ctx.getSource(),
                                Text.literal("JNI=" + (handle != 0) + " armed=" + armed +
                                             " active=" + active() + " loading="+loading()+" terrainReady=" + NativeTerrain.ready() +
                                             " hudHidden="+client.options.hudHidden+" view="+client.options.getPerspective()+
                                             " postureHud="+postureHudVisible()+" sneak="+(client.player!=null && client.player.isSneaking())+
                                             " performance="+ForgeWindowPacing.enabled()+" bridgeVsyncOff="+ForgeWindowPacing.suppressesVsync()+
                                             " pose="+(client.player==null?"none":client.player.getPose())+
                                             " frames=" + FRAMES.published+" audio="+AudioBridge.status()+" combat="+CombatBridge.status()));
                            return Command.SINGLE_SUCCESS;
                       })));
    }
    private static int setPerformance(net.minecraft.server.command.ServerCommandSource source,boolean enabled){
        try{ForgeWindowPacing.setEnabled(enabled);}
        catch(java.io.IOException | IllegalArgumentException e){LOG.warn("Could not save performance preference",e);
            source.sendError(Text.literal("无法保存桥接性能设置，请检查 MC 游戏目录是否可写。"));return 0;}
        feedback(source,Text.literal("桥接窗口性能优化已"+(enabled?"开启":"关闭")+"，设置已保存。开启时关闭 MC 窗口垂直同步，解除桥接后恢复。"));
        return Command.SINGLE_SUCCESS;
    }
    public static void clientTick(boolean end){
        if(handle==0)return;
        if(!end){
            poll();
            if(ownsNativePlayer())PLAYERS.client(state);
            if(active())INPUT.update(state);
        }else if(active()){
            PLAYERS.client(state);
            PHYSICS.update(state);
        }
    }
    public static void serverTick(net.minecraft.server.MinecraftServer server){
        if(handle==0)return;
        CombatBridge.server(server);PLAYERS.server(server,ownsNativePlayer()?state:null);
    }
    public static void serverStopping(net.minecraft.server.MinecraftServer server){
        NativeVoidProtection.clear();CombatBridge.release();PLAYERS.server(server,null);
    }
    public static void shutdown(){
        ForgeEffectDepth.close();
        disarm();INPUT.release();FRAMES.close();
        if(handle!=0){
            NativeBridge.status(handle,0,0);NativeBridge.close(handle);handle=0;
        }
    }
}
