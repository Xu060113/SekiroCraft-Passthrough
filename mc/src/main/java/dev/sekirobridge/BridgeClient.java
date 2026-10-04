package dev.sekirobridge;

import com.mojang.brigadier.Command;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.Perspective;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Properties;

public final class BridgeClient implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("sekirobridge");
    private static long handle;
    private static final ByteBuffer control = Protocol.direct(Protocol.CONTROL_BYTES);
    private static volatile Protocol.State state;
    private static Protocol.State renderPose;
    private static boolean wasConnected;
    private static float frameFov=Float.NaN;
    private static volatile boolean armed;
    private static Perspective previousPerspective;
    private static Object worldIdentity;
    public static final FrameExporter FRAMES = new FrameExporter();
    private static final InputForwarder INPUT = new InputForwarder();
    private static final PlayerSync PLAYERS = new PlayerSync();
    private static final PhysicsExporter PHYSICS = new PhysicsExporter();
    public static Protocol.State state() { return state; }
    public static Protocol.State renderPose(){return renderPose;}
    public static void renderPose(Protocol.State pose){renderPose=pose;}
    public static float projectionFov(double degrees){
        float f=(float)Math.toRadians(Math.max(2,Math.min(170,degrees)));
        if(!Float.isFinite(frameFov))frameFov=f;
        return f;
    }
    public static float frameFov(){return Float.isFinite(frameFov)?frameFov:state.fov();}
    public static long handle() { return handle; }
    public static boolean keyHeld(int key){return INPUT.held(key);}
    public static boolean armed() { return armed && handle != 0; }
    public static boolean active() {
        return connected() && (state.flags()&Protocol.FOCUS)!=0;
    }
    public static boolean connected(){
        return handle != 0 && armed && state != null && state.valid() &&
            Protocol.fresh(NativeBridge.clockMs(),state.tickMs()) && (state.flags()&Protocol.SCENE)!=0 &&
            (state.capabilities()&256)!=0 && MinecraftClient.getInstance().player!=null &&
            MinecraftClient.getInstance().world != null && MinecraftClient.getInstance().getServer() != null;
    }
    public static void poll() {
        var client = MinecraftClient.getInstance();
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
        if (!active()) {
            INPUT.release();
            FRAMES.discard();
        }
        boolean connected=connected();
        if(!connected && wasConnected)PLAYERS.reset();
        wasConnected=connected;
        if(connected)NativeTerrain.poll(state);
        if (handle != 0)
            NativeBridge.status(handle, connected() ? (1 | 8 | (client.currentScreen != null ? 2 : 0) |
                (client.player != null && client.player.getAbilities().flying &&
                 (state.capabilities() & 128) != 0 ? 4 : 0)) : 0,
                                state != null ? state.epoch() : 0);
    }
    public static void renderBegin() {
        poll();
        renderPose=null;
        frameFov=Float.NaN;
        if (connected())
            PLAYERS.client(state);
        if(active())INPUT.update(state);
    }
    private static void disarm() {
        armed = false;
        wasConnected=false;
        PLAYERS.reset();renderPose=null;
        if (previousPerspective != null) {
            MinecraftClient.getInstance().options.setPerspective(previousPerspective);
            previousPerspective = null;
        }
    }
    @Override
    public void onInitializeClient() {
        var client = MinecraftClient.getInstance();
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
            handle = NativeBridge.open(p.getProperty("channel", "default"));
            if (handle == 0)
                throw new IllegalStateException("Shared memory channel could not be opened");
            LOG.info("Bridge ready, protocol v1; dormant until /sekirobridge on in a dedicated " +
                     "single-player world");
        } catch (Exception | UnsatisfiedLinkError e) {
            LOG.error("Bridge disabled; Minecraft remains usable", e);
            handle = 0;
        }
        ClientCommandRegistrationCallback.EVENT.register(
            (dispatcher, access)
                -> dispatcher.register(
                    literal("sekirobridge")
                        .then(literal("on").executes(ctx -> {
                            if (handle == 0 || client.getServer() == null || client.world == null) {
                                ctx.getSource().sendError(Text.literal(
                                    "Bridge requires Windows JNI and a local single-player world."));
                                return 0;
                            }
                            worldIdentity = client.world;
                            if (!armed)
                                previousPerspective = client.options.getPerspective();
                            armed = true;
                            client.options.setPerspective(Perspective.FIRST_PERSON);
                            ctx.getSource().sendFeedback(
                                Text.literal("Minecraft player controls armed: WASD / E inventory / Space jump / F5 view. " +
                                    "Requires the paired MC camera adapter; F8 pauses input; /sekirobridge off releases it."));
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(literal("off").executes(ctx -> {
                            disarm();
                            INPUT.release();
                            FRAMES.discard();
                            ctx.getSource().sendFeedback(
                                Text.literal("Bridge off; original controls restored."));
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(literal("status").executes(ctx -> {
                            ctx.getSource().sendFeedback(
                                Text.literal("JNI=" + (handle != 0) + " armed=" + armed +
                                             " active=" + active() + " frames=" + FRAMES.published));
                            return Command.SINGLE_SUCCESS;
                        }))));
        ClientTickEvents.START_CLIENT_TICK.register(c -> {
            poll();
            if (active()) {
                PLAYERS.client(state);
                INPUT.update(state);
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(c -> {
            if (active()) {
                PLAYERS.client(state);
                PHYSICS.update(state);
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> PLAYERS.server(server, connected() ? state : null));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> PLAYERS.server(server, null));
        ClientLifecycleEvents.CLIENT_STOPPING.register(c -> {
            disarm();
            INPUT.release();
            FRAMES.close();
            if (handle != 0) {
                NativeBridge.status(handle, 0, 0);
                NativeBridge.close(handle);
                handle = 0;
            }
        });
    }
}
