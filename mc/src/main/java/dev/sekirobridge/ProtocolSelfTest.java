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
        NativeBridge.close(handle);
        System.out.println(checks + " Java/JNI protocol checks passed");
    }
}
