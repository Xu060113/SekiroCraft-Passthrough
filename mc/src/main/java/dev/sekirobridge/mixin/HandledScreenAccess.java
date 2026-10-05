package dev.sekirobridge.mixin;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(HandledScreen.class)
public interface HandledScreenAccess {
    @Invoker("getSlotAt") Slot bridgeSlotAt(double x,double y);
}
