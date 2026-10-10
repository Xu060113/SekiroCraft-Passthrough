package dev.sekirobridge.compat;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.IClassBytecodeProvider;
import org.spongepowered.asm.service.MixinService;

/** Check optional bytecode without initializing a third-party render class. */
public final class ForgeCompatPlugin implements IMixinConfigPlugin {
    public void onLoad(String mixinPackage) {}
    public String getRefMapperConfig() { return null; }
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return targetExists(MixinService.getService().getBytecodeProvider(), targetClassName);
    }
    static boolean targetExists(IClassBytecodeProvider bytecode, String targetClassName) {
        try {
            // ModLauncher rejects runTransformers=false. Its default lookup reads
            // supported bytecode without defining or initializing the target class.
            bytecode.getClassNode(targetClassName);
            return true;
        } catch (java.io.IOException | ClassNotFoundException e) { return false; }
    }
    public void acceptTargets(Set<String> mine, Set<String> others) {}
    public List<String> getMixins() { return null; }
    public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
    public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
}
