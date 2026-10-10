package dev.sekirobridge.mixin;

import dev.sekirobridge.ForgeWindowPacing;
import net.minecraft.client.util.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Window.class)
public abstract class ForgeWindowVsyncMixin {
    @Inject(method="setVsync",at=@At("HEAD"),cancellable=true)
    private void bridgeSwap(boolean requested,CallbackInfo ci){
        if(requested && ForgeWindowPacing.suppressesVsync()){
            // Fullscreen and MC settings can reapply VSync during a session.
            // Only the window swap changes; the user's saved option stays intact.
            ((Window)(Object)this).setVsync(false);
            ci.cancel();
        }
    }
}
