package dev.sekirobridge;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
public final class ProtocolSelfTest {
    private static int checks;
    private static void require(boolean value, String message) {
        checks++;
        if (!value)
            throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 3)
            throw new IllegalArgumentException("JNI DLL, C++ packet fixture, output native frame metadata");
        System.load(Path.of(args[0]).toAbsolutePath().toString());
        require(NativeBridge.clockMs() > 0, "Windows shared boot clock");
        ByteBuffer bytes = Protocol.direct(Protocol.CONTROL_BYTES);
        bytes.put(Files.readAllBytes(Path.of(args[1]))).flip();
        var s = Protocol.decode(bytes);
        require(s.valid() && s.sequence() == 1 && s.epoch() == 123 && s.command() == 77, "C++ control ABI");
        require(s.px() == 12.5f && s.py() == -4 && s.pz() == 8, "position offsets");
        require(s.width() == 1280 && s.height() == 720 && s.textSequence() == 2 && s.text()[0] == 0x4e2d &&
                    s.text()[1] == 0x6587,
                "dimensions and UTF32 offsets");
        require(s.mcX(2) == 2 && s.mcY(2) == 130 && s.mcZ(2) == -2, "coordinate handedness and offset");
        require(Math.abs(Math.abs(s.yaw()) - 180) < .01 && s.pitch() == 0, "camera direction");
        int oldFlags=bytes.getInt(24);
        bytes.putInt(24,Protocol.SCENE|Protocol.FOCUS|Protocol.NATIVE_CINEMATIC);
        require(Protocol.decode(bytes).valid() && !Protocol.decode(bytes).active(s.tickMs()),
                "cinematic keeps a valid connection but suppresses presentation");
        bytes.putInt(24,Protocol.SCENE|Protocol.FOCUS);
        require(Protocol.decode(bytes).active(s.tickMs()),"native movie exit resumes normal activity");
        bytes.putInt(24,oldFlags);
        require(!Protocol.fresh(5, 6) && Protocol.fresh(100, 90) && !Protocol.fresh(1000, 90),
                "freshness matches host");
        bytes.putFloat(44, Float.NaN);
        require(!Protocol.decode(bytes).valid(), "non-finite pose rejected");
        bytes.putFloat(44, 0);
        boolean truncated = false;
        try {
            Protocol.decode(Protocol.direct(10));
        } catch (IllegalArgumentException e) {
            truncated = true;
        }
        require(truncated, "truncated packet rejected");
        ByteBuffer meta = Protocol.metadata(s, 9, 4, 4);
        byte[] output = new byte[Protocol.META_BYTES];
        meta.get(output);
        Files.write(Path.of(args[2]), output);
        require(NativeBridge.open("../invalid") == 0, "channel validation through JNI");
        long handle = NativeBridge.open("java-fixture-" + ProcessHandle.current().pid());
        require(handle != 0, "Java opens real Windows mapping");
        require(!NativeBridge.control(handle, ByteBuffer.allocate(Protocol.CONTROL_BYTES)),
                "non-direct Java buffer rejected");
        require(!NativeBridge.control(handle, Protocol.direct(10)), "small Java buffer rejected");
        require(!NativeBridge.publish(handle, meta, ByteBuffer.allocate(4 * 4 * 12)),
                "non-direct frame rejected");
        require(!NativeBridge.physics(handle, ByteBuffer.allocate(98392)), "non-direct physics rejected");
        require(!NativeBridge.physics(handle, Protocol.direct(64)), "truncated physics rejected");
        ByteBuffer physics = Protocol.direct(98392);
        physics.putLong(NativeBridge.clockMs()).putLong(NativeBridge.clockMs()).putLong(123);
        physics.putInt(1).putInt(4097);
        physics.putFloat(0).putFloat(0).putFloat(0).putFloat(.3f).putFloat(1.8f).putInt(0).putLong(1);
        physics.putFloat(-10).putFloat(-10).putFloat(-10).putFloat(10).putFloat(10).putFloat(10);
        require(!NativeBridge.physics(handle, physics), "shape count overflow rejected");
        physics.putInt(28, 0);
        require(NativeBridge.physics(handle, physics), "valid empty shape snapshot accepted");
        require(!NativeBridge.input(handle,ByteBuffer.allocate(4136)),"non-direct input rejected");
        require(!NativeBridge.input(handle,Protocol.direct(4135)),"truncated event ring rejected");
        require(!NativeBridge.terrain(handle,Protocol.direct(447)),"truncated terrain rejected");
        require(!NativeBridge.player(handle,ByteBuffer.allocate(104)),"non-direct player rejected");
        require(!NativeBridge.player(handle,Protocol.direct(103)),"truncated player rejected");
        var player=Protocol.direct(104);
        player.putLong(1).putLong(NativeBridge.clockMs()).putLong(s.tickMs()).putLong(s.epoch()).putInt(1).putFloat(180);
        player.putFloat(0).putFloat(0).putFloat(0).putFloat(0).putFloat(1.62f).putFloat(0);
        player.putFloat(0).putFloat(0).putFloat(1).putFloat(1.1f).putFloat(16f/9).putFloat(.05f).putFloat(500);
        player.putFloat(0).putFloat(0).putFloat(0);
        require(NativeBridge.player(handle,player),"valid MC player/camera accepted");
        player.putFloat(52,Float.NaN);require(!NativeBridge.player(handle,player),"nonfinite eye rejected");
        var rendered=s.withCamera(1,2,3,1,0,0);
        var actual=Protocol.metadata(rendered,10,4,4,12345);
        require(actual.getLong(8)==12345 && actual.getFloat(48)==1 && actual.getFloat(60)==1,
            "actual captured pose and capture timestamp, not current host camera");
        require(InputForwarder.glfwKey(116)==294 && InputForwarder.glfwKey(32)==32 &&
                InputForwarder.glfwKey(69)==69,"MC F5, jump and inventory mappings");
        require(s.withFov((float)Math.toRadians(7)).valid(),"MC spyglass projection can cross the bridge");
        NativeBridge.close(handle);
        System.out.println(checks + " Java/JNI protocol checks passed");
    }
}
