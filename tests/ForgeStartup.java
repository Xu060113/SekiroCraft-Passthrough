import dev.sekirobridge.BridgeClient;
import dev.sekirobridge.NativeBridge;
import dev.sekirobridge.ForgeWindowPacing;

/** Exercise the real client entry points while JNI is deliberately not loaded. */
public final class ForgeStartup {
    private static int checks;
    private static void require(boolean ok, String reason) {
        checks++;
        if (!ok) throw new AssertionError(reason);
    }
    private static void requireUnloaded() {
        try {
            NativeBridge.clockMs();
            throw new AssertionError("Test requires an unloaded JNI library");
        } catch (UnsatisfiedLinkError expected) {
            checks++;
        }
    }
    public static void main(String[] args) {
        requireUnloaded();
        require(BridgeClient.handle() == 0 && !BridgeClient.armed(), "startup is dormant");
        // Initial loading and failed initialization both leave the handle at zero.
        for (int frame = 0; frame < 20; frame++) {
            BridgeClient.poll();
            BridgeClient.renderBegin();
            ForgeWindowPacing.update();
            require(!ForgeWindowPacing.suppressesVsync(),"window optimization stays dormant before JNI/session initialization");
            BridgeClient.clientTick(false);
            BridgeClient.clientTick(true);
            BridgeClient.serverTick(null);
            require(BridgeClient.handle() == 0 && BridgeClient.state() == null,
                    "early loading frame cannot start a native session");
            require(!BridgeClient.active() && !BridgeClient.loading() && !BridgeClient.ownsNativePlayer(),
                    "unavailable JNI cannot enable rendering or player ownership");
        }
        requireUnloaded();
        System.out.println(checks + " Forge pre-JNI startup checks passed (no game launched)");
    }
}
