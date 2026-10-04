package dev.sekirobridge.mixin;
import dev.sekirobridge.BridgeClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(InputUtil.class)
public abstract class InputUtilMixin {
    @Inject(method = "isKeyPressed", at = @At("HEAD"), cancellable = true)
    private static void modifiers(long window, int key, CallbackInfoReturnable<Boolean> cir) {
        if (!BridgeClient.active() || window != MinecraftClient.getInstance().getWindow().getHandle())
            return;
        var s = BridgeClient.state();
        if (key == GLFW.GLFW_KEY_LEFT_SHIFT || key == GLFW.GLFW_KEY_RIGHT_SHIFT)
            cir.setReturnValue(MinecraftClient.getInstance().currentScreen != null ? s.key(16) : s.key(18));
        else if (key == GLFW.GLFW_KEY_LEFT_CONTROL || key == GLFW.GLFW_KEY_RIGHT_CONTROL)
            cir.setReturnValue(s.key(17));
        else if (key == GLFW.GLFW_KEY_LEFT_ALT || key == GLFW.GLFW_KEY_RIGHT_ALT)
            cir.setReturnValue(s.key(18));
    }
}
