package dev.sekirobridge.mixin;
import dev.sekirobridge.BridgeClient;
import dev.sekirobridge.InputForwarder;
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
        int mods=InputForwarder.replayMods;
        if (key == GLFW.GLFW_KEY_LEFT_SHIFT || key == GLFW.GLFW_KEY_RIGHT_SHIFT)
            cir.setReturnValue(mods>=0 ? (mods&1)!=0 : s.key(16));
        else if (key == GLFW.GLFW_KEY_LEFT_CONTROL || key == GLFW.GLFW_KEY_RIGHT_CONTROL)
            cir.setReturnValue(mods>=0 ? (mods&2)!=0 : s.key(17));
        else if (key == GLFW.GLFW_KEY_LEFT_ALT || key == GLFW.GLFW_KEY_RIGHT_ALT)
            cir.setReturnValue(mods>=0 ? (mods&4)!=0 : s.key(18));
        else cir.setReturnValue(BridgeClient.keyHeld(key));
    }
}
