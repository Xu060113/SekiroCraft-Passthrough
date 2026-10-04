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
        var input=Protocol.direct(4136);var terrain=Protocol.direct(448);
        while(System.currentTimeMillis()<deadline && (!NativeBridge.input(handle,input) || !NativeBridge.terrain(handle,terrain)))Thread.sleep(5);
        if(input.getLong(16)!=2 || input.getLong(24)!=17 || input.getLong(32)!=-9 ||
           input.getInt(40)!=2 || input.getInt(48)!=1 || input.getInt(80)!=0 ||
           input.getFloat(56)!=.25f || input.getFloat(88)!=.26f || input.getInt(52)!=1)
            throw new AssertionError("quick down/up, individual coordinates and Shift modifier survive one poll");
        if(terrain.get(404)!=1 || terrain.getFloat(200)!=3.5f || terrain.getFloat(24)!=12)
            throw new AssertionError("native terrain grid ABI");
        var player=Protocol.direct(104);
        player.putLong(77).putLong(NativeBridge.clockMs()).putLong(state.tickMs()).putLong(state.epoch());
        player.putInt(5).putFloat(180).putFloat(12).putFloat(4).putFloat(8);
        player.putFloat(12).putFloat(5.62f).putFloat(8).putFloat(0).putFloat(0).putFloat(1);
        player.putFloat(1.1f).putFloat(16f/9).putFloat(.05f).putFloat(500);
        player.putFloat(4).putFloat(0).putFloat(0);
        if(!NativeBridge.player(handle,player))throw new AssertionError("MC player feedback");
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
