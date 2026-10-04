package dev.sekirobridge.mixin;
import net.minecraft.client.Keyboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(Keyboard.class)
public interface KeyboardInvoker {
    @Invoker("onKey") void bridgeKey(long window, int key, int scan, int action, int modifiers);
    @Invoker("onChar") void bridgeChar(long window, int codepoint, int modifiers);
}
