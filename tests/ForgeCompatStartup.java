package dev.sekirobridge.compat;

import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import java.io.IOException;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.launch.MixinLaunchPluginLegacy;
import org.spongepowered.asm.service.IClassBytecodeProvider;

/** Exercise production optional-target lookup through the real ModLauncher provider. */
public final class ForgeCompatStartup {
    private static int checks;
    private static void require(boolean ok, String message) {
        checks++; if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        String target = "com.guhao.vix.client.pipeline.PostEffectPipelines";
        byte[] bytes;
        if (args.length > 0 && !args[0].isBlank()) {
            try (var jar = new JarFile(args[0])) {
                var entry = jar.getJarEntry(target.replace('.', '/') + ".class");
                require(entry != null, "installed VIX pipeline exists");
                try (var stream = jar.getInputStream(entry)) { bytes = stream.readAllBytes(); }
            }
        } else {
            var writer = new ClassWriter(0);
            writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, target.replace('.', '/'), null, "java/lang/Object", null);
            writer.visitEnd(); bytes = writer.toByteArray();
        }
        final byte[] targetBytes = bytes;
        var provider = new MixinLaunchPluginLegacy();
        int[] reads = {0};
        ILaunchPluginService.ITransformerLoader loader = name -> {
            reads[0]++;
            if (!name.equals(target)) throw new ClassNotFoundException(name);
            return targetBytes;
        };
        // Supply bytecode as ModLauncher does, without launching or initializing a game class.
        var loaderField = MixinLaunchPluginLegacy.class.getDeclaredField("transformerLoader");
        loaderField.setAccessible(true); loaderField.set(provider, loader);
        try {
            provider.getClassNode(target, false);
            throw new AssertionError("old unsupported lookup must reproduce the reported crash");
        } catch (IllegalArgumentException expected) {
            require(expected.getMessage().contains("untransformed bytecode"), "reported ModLauncher failure reproduced");
        }
        require(reads[0] == 0, "old failure occurs before optional class reading");
        for (int i = 0; i < 10; i++) {
            require(ForgeCompatPlugin.targetExists(provider, target), "production lookup accepts installed VIX without loading it");
            require(!ForgeCompatPlugin.targetExists(provider, "dev.sekirobridge.fixture.AbsentVix"), "missing optional target is skipped");
        }
        require(reads[0] == 20, "supported lookup uses the real ModLauncher bytecode loader");
        var unreadable = new IClassBytecodeProvider() {
            public ClassNode getClassNode(String name) throws IOException { throw new IOException("fixture read failure"); }
            public ClassNode getClassNode(String name, boolean transform) throws IOException { throw new IOException("fixture read failure"); }
        };
        require(!ForgeCompatPlugin.targetExists(unreadable, target), "unreadable optional target is skipped");
        require(ForgeCompatPlugin.class.getClassLoader().getResource(target.replace('.', '/') + ".class") == null,
                "VIX class is not loaded onto the fixture classpath");
        System.out.println(checks + " Forge optional-target ModLauncher startup checks passed (no game launched)");
        System.out.println("Mixin provider: " + MixinLaunchPluginLegacy.class.getProtectionDomain().getCodeSource().getLocation());
    }
}
