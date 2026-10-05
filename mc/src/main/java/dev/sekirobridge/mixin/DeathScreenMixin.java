package dev.sekirobridge.mixin;
import dev.sekirobridge.DeathSync;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.List;

@Mixin(DeathScreen.class)
public abstract class DeathScreenMixin {
    @Shadow @Final private List<ButtonWidget> buttons;
    @Inject(method="tick",at=@At("TAIL"))
    private void bridgeDeathButton(CallbackInfo ci){
        if(DeathSync.nativeDead() && !buttons.isEmpty())buttons.get(0).setMessage(Text.literal("复活只狼并同步 MC（或按 R）"));
    }
    @Inject(method="mouseClicked",at=@At("HEAD"),cancellable=true)
    private void bridgeResurrection(double x,double y,int button,CallbackInfoReturnable<Boolean> cir){
        if(button!=0 || !DeathSync.nativeDead() || buttons.isEmpty())return;
        var b=buttons.get(0);
        if(b.active && x>=b.getX() && x<b.getX()+b.getWidth() && y>=b.getY() && y<b.getY()+b.getHeight()){
            DeathSync.requestNativeRespawn();cir.setReturnValue(true);
        }
    }
}
