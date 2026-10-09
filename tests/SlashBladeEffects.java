import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.*;
import java.util.jar.*;
import dev.sekirobridge.SwordEffectCapture;

/** Verify compatibility against the installed third-party bytecode, without loading its mod. */
public final class SlashBladeEffects {
    private static int checks;
    private static void require(boolean pass, String message) {
        checks++; if (!pass) throw new AssertionError(message);
    }
    private static ClassNode read(JarFile jar, String name) throws Exception {
        var entry = jar.getJarEntry(name + ".class");
        require(entry != null, "installed class exists: " + name);
        var result = new ClassNode();
        new ClassReader(jar.getInputStream(entry).readAllBytes()).accept(result, 0);
        return result;
    }
    public static void main(String[] args) throws Exception {
        try (var jar = new JarFile(args[0])) {
            var state = read(jar, "mods/flammpfeil/slashblade/client/renderer/util/BladeRenderState");
            var names = new HashSet<String>();
            for (var method : state.methods) for (var insn : method.instructions)
                if (insn instanceof InvokeDynamicInsnNode concat)
                    for (var argument : concat.bsmArgs)
                        if (argument instanceof String s && s.startsWith("slashblade_")) names.add(s.replace("\u0001", "fixture"));
            for (var prefix : new String[]{"slashblade_blend_luminous_", "slashblade_blend_luminous_depth_write_",
                    "slashblade_blend_write_color_", "slashblade_charge_effect_"}) {
                require(names.stream().anyMatch(s -> s.startsWith(prefix)), "actual installed layer prefix: " + prefix);
                require(names.stream().filter(s -> s.startsWith(prefix)).allMatch(SwordEffectCapture::supports),
                    "capture selects installed additive layer: " + prefix);
            }
            var luminous = state.methods.stream().filter(m -> m.name.startsWith("lambda$getSlashBladeBlendLuminous$")).findFirst().orElseThrow();
            boolean colorOnly = false, additive = false, emissive = false;
            for (var insn : luminous.instructions) if (insn instanceof FieldInsnNode field) {
                colorOnly |= field.name.equals("field_25643");
                additive |= field.name.equals("LIGHTNING_ADDITIVE_TRANSPARENCY");
                emissive |= field.name.equals("field_38344");
            }
            require(colorOnly && additive && emissive, "installed sword glow uses color-only emissive/additive state");
            for (var renderer : new String[]{"DriveRenderer", "SlashEffectRenderer"}) {
                var node = read(jar, "mods/flammpfeil/slashblade/client/renderer/entity/" + renderer);
                boolean usesGlow = false;
                for (var method : node.methods) for (var insn : method.instructions)
                    if (insn instanceof MethodInsnNode call) usesGlow |= call.name.equals("renderOverridedLuminous");
                require(usesGlow, "actual " + renderer + " invokes the captured glow path");
            }
            var reverse = read(jar, "mods/flammpfeil/slashblade/client/renderer/entity/JudgementCutRenderer");
            boolean usesReverse = false;
            for (var method : reverse.methods) for (var insn : method.instructions)
                if (insn instanceof MethodInsnNode call) usesReverse |= call.name.equals("renderOverridedReverseLuminous");
            require(usesReverse && !SwordEffectCapture.supports("slashblade_blend_reverse_luminous_fixture"),
                "subtractive judgement cut is explicitly outside this additive compatibility path");
        }
        System.out.println(checks + " installed SlashBlade effect-path checks passed; no game launched");
    }
}
