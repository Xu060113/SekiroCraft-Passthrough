package dev.sekirobridge;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;

/** Dedicated servers must not load any Minecraft client/JNI classes. */
@Mod("sekirobridge")
public final class ForgeBridgeMod {
    public ForgeBridgeMod() {
        DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> ForgeClientEvents::register);
    }
}
