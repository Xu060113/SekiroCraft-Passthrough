import dev.sekirobridge.NativeBridge;
import dev.sekirobridge.Protocol;
import java.nio.file.Path;
public final class JniPeer {
    public static void main(String[] args) throws Exception {
        System.load(Path.of(args[0]).toAbsolutePath().toString());
        long handle = NativeBridge.open(args[1]);
        if (handle == 0)
            throw new AssertionError("open");
        var b = Protocol.direct(Protocol.CONTROL_BYTES);
        long deadline = System.currentTimeMillis() + 5000;
        Protocol.State state = null;
        while (System.currentTimeMillis() < deadline) {
            if (NativeBridge.control(handle, b)) {
                state = Protocol.decode(b);
                if (state.active(NativeBridge.clockMs()))
                    break;
            }
            Thread.sleep(5);
        }
        if (state == null || state.command() != 77 || state.text()[0] != 0x4e2d)
            throw new AssertionError("C++ control not received");
        var pixels = Protocol.direct(4 * 4 * 12);
        for (int i = 0; i < pixels.capacity(); ++i)
            pixels.put((byte)0x6b);
        pixels.flip();
        var meta = Protocol.metadata(state, 1, 4, 4);
        boolean published = false;
        while (System.currentTimeMillis() < deadline) {
            if (NativeBridge.publish(handle, meta, pixels)) {
                published = true;
                break;
            }
            Thread.sleep(5);
        }
        if (!published)
            throw new AssertionError("publish");
        NativeBridge.status(handle, 3, state.epoch());
        var physics = Protocol.direct(6208);
        physics.putLong(NativeBridge.clockMs()).putLong(state.tickMs()).putLong(state.epoch());
        physics.putInt(7).putInt(1);
        physics.putFloat(0).putFloat(0).putFloat(0).putFloat(.3f).putFloat(1.8f).putInt(0).putLong(99);
        physics.putFloat(-1).putFloat(0).putFloat(-1).putFloat(1).putFloat(.5f).putFloat(1);
        if (!NativeBridge.physics(handle, physics)) throw new AssertionError("physics publish");
        Thread.sleep(200);
        NativeBridge.close(handle);
        System.out.println("Java peer published real frame planes and collision/flight state");
    }
}
