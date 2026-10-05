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
        var input=Protocol.direct(Protocol.INPUT_BYTES);var terrain=Protocol.direct(448);
        while(System.currentTimeMillis()<deadline && (!NativeBridge.input(handle,input) || !NativeBridge.terrain(handle,terrain)))Thread.sleep(5);
        if(input.getLong(16)!=2 || input.getLong(24)!=17 || input.getLong(32)!=-9 ||
           input.getInt(64)!=2 || input.getInt(72)!=1 || input.getInt(120)!=0 ||
           input.getFloat(80)!=.25f || input.getFloat(128)!=.26f || input.getInt(76)!=1)
            throw new AssertionError("quick down/up, individual coordinates and Shift modifier survive one poll");
        if(terrain.get(404)!=1 || terrain.getFloat(200)!=3.5f || terrain.getFloat(24)!=12)
            throw new AssertionError("native terrain grid ABI");
        var combat=Protocol.direct(3672);
        while(System.currentTimeMillis()<deadline && !NativeBridge.combatState(handle,combat))Thread.sleep(5);
        if(combat.getLong(24)!=101 || combat.getInt(40)!=250 || combat.getLong(88)!=102 || combat.getFloat(96)!=12 || combat.getInt(116)!=6)
            throw new AssertionError("native actor / life ABI");
        if(NativeBridge.combatState(handle,Protocol.direct(3159)))throw new AssertionError("short combat destination accepted");
        var report=Protocol.direct(4160);
        report.putLong(0,NativeBridge.clockMs()).putLong(8,state.epoch()).putLong(16,101).putLong(24,103)
            .putDouble(32,.1).putDouble(40,.05).putLong(48,1).putInt(56,1)
            .putLong(64,1).putLong(72,102).putFloat(80,7).putLong(88,1);
        if(!NativeBridge.combatReport(handle,report))throw new AssertionError("combat injury report");
        report.putFloat(80,Float.NaN);
        if(NativeBridge.combatReport(handle,report))throw new AssertionError("invalid injury accepted");
        report.putFloat(80,7);
        var nativeAction=Protocol.direct(32);nativeAction.putLong(NativeBridge.clockMs()).putLong(state.epoch()).putLong(77).putInt(1).putInt(0);
        if(!NativeBridge.nativeAction(handle,nativeAction) || NativeBridge.nativeAction(handle,Protocol.direct(31)))throw new AssertionError("native resurrection action ABI");
        var rays=Protocol.direct(2080);rays.putLong(1).putLong(NativeBridge.clockMs()).putLong(state.epoch()).putInt(1).putInt(0)
            .putLong(201).putFloat(12).putFloat(4).putFloat(8).putFloat(0).putFloat(-2).putFloat(0);
        if(!NativeBridge.projectileRays(handle,rays))throw new AssertionError("native projectile request ABI");
        rays.putFloat(52,Float.NaN);
        if(NativeBridge.projectileRays(handle,rays))throw new AssertionError("nonfinite native projectile query accepted");
        var rayHits=Protocol.direct(2592);
        while(System.currentTimeMillis()<deadline && !NativeBridge.projectileHits(handle,rayHits))Thread.sleep(5);
        if(rayHits.getInt(24)!=1 || rayHits.getLong(32)!=201 || rayHits.getFloat(44)!=3 || rayHits.getFloat(56)!=1 || rayHits.getInt(64)!=1)
            throw new AssertionError("native projectile contact ABI");
        if(NativeBridge.projectileHits(handle,Protocol.direct(2591)))throw new AssertionError("short native projectile destination accepted");
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
        var physics = Protocol.direct(98392);
        physics.putLong(NativeBridge.clockMs()).putLong(state.tickMs()).putLong(state.epoch());
        physics.putInt(7).putInt(1);
        physics.putFloat(0).putFloat(0).putFloat(0).putFloat(.3f).putFloat(1.8f).putInt(0).putLong(99);
        physics.putFloat(-10).putFloat(-10).putFloat(-10).putFloat(10).putFloat(10).putFloat(10);
        physics.putFloat(-1).putFloat(0).putFloat(-1).putFloat(1).putFloat(.5f).putFloat(1);
        if (!NativeBridge.physics(handle, physics)) throw new AssertionError("physics publish");
        Thread.sleep(200);
        NativeBridge.close(handle);
        System.out.println("Java peer published frame, collision, health, creative state and actor injury");
    }
}
