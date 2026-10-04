package dev.sekirobridge.mixin;
import dev.sekirobridge.BridgeClient;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {
    @Inject(method="render(Lnet/minecraft/client/gui/DrawContext;F)V",at=@At("RETURN"))
    private void combatHud(net.minecraft.client.gui.DrawContext context,float tickDelta,CallbackInfo ci){
        dev.sekirobridge.CombatHud.render(context);
    }
    // Vanilla vignette replaces destination alpha across the whole screen.
    // Its black RGB would make an otherwise transparent HUD cover Sekiro.
    @Inject(method =
                "renderVignetteOverlay(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/entity/Entity;)V",
            at = @At("HEAD"), cancellable = true)
    private void
    hideVignette(CallbackInfo ci) {
        if (BridgeClient.active())
            ci.cancel();
    }
}
