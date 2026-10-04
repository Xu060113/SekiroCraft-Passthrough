package dev.sekirobridge.mixin;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(Mouse.class)
public interface InputInvoker {
    @Invoker("onMouseButton") void bridgeButton(long window, int button, int action, int mods);
    @Invoker("onCursorPos") void bridgeCursor(long window, double x, double y);
    @Invoker("onMouseScroll") void bridgeScroll(long window, double horizontal, double vertical);
}
