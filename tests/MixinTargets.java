import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
public final class MixinTargets {
    static int checks;
    static void require(boolean b, String what) {
        checks++;
        if (!b)
            throw new AssertionError(what);
    }
    static Object value(AnnotationNode a, String key) {
        if (a.values == null)
            return null;
        for (int i = 0; i < a.values.size(); i += 2)
            if (a.values.get(i).equals(key))
                return a.values.get(i + 1);
        return null;
    }
    static ClassNode read(byte[] bytes) {
        ClassNode c = new ClassNode();
        new ClassReader(bytes).accept(c, 0);
        return c;
    }
    static List<AnnotationNode> annotations(MethodNode m) {
        var list = new ArrayList<AnnotationNode>();
        if (m.visibleAnnotations != null)
            list.addAll(m.visibleAnnotations);
        if (m.invisibleAnnotations != null)
            list.addAll(m.invisibleAnnotations);
        return list;
    }
    public static void main(String[] args) throws Exception {
        try (var game = new JarFile(args[0])) {
            var mixins = Files.list(Path.of(args[1]));
            for (Path path : mixins.filter(p -> p.toString().endsWith(".class")).toList()) {
                var c = read(Files.readAllBytes(path));
                AnnotationNode annotation = null;
                if (c.invisibleAnnotations != null)
                    for (var a : c.invisibleAnnotations)
                        if (a.desc.endsWith("/Mixin;"))
                            annotation = a;
                if (c.visibleAnnotations != null)
                    for (var a : c.visibleAnnotations)
                        if (a.desc.endsWith("/Mixin;"))
                            annotation = a;
                require(annotation != null, "Mixin target annotation " + c.name);
                @SuppressWarnings("unchecked") var targets = (List<Type>)value(annotation, "value");
                require(targets.size() == 1, "one target " + c.name);
                var targetEntry = game.getJarEntry(targets.get(0).getInternalName() + ".class");
                require(targetEntry != null, "mapped target exists");
                var target = read(game.getInputStream(targetEntry).readAllBytes());
                for (var method : c.methods)
                    for (var a : annotations(method)) {
                        if (a.desc.endsWith("/Invoker;") || a.desc.endsWith("/Shadow;")) {
                            String name =
                                a.desc.endsWith("/Invoker;") ? (String)value(a, "value") : method.name;
                            require(target.methods.stream().anyMatch(
                                        m -> m.name.equals(name) && m.desc.equals(method.desc)),
                                    "Invoker/shadow descriptor " + c.name + " " + name + method.desc);
                        }
                        if (!a.desc.endsWith("/Inject;"))
                            continue;
                        @SuppressWarnings("unchecked") var selectors = (List<String>)value(a, "method");
                        for (String selector : selectors) {
                            var methods =
                                target.methods.stream()
                                    .filter(
                                        m -> (m.name + m.desc).equals(selector) || m.name.equals(selector))
                                    .toList();
                            require(!methods.isEmpty(), "Injection selector " + c.name + " -> " + selector);
                            @SuppressWarnings("unchecked") var ats = (List<AnnotationNode>)value(a, "at");
                            for (var at : ats)
                                if ("INVOKE".equals(value(at, "value"))) {
                                    String callee = (String)value(at, "target");
                                    boolean found = false;
                                    for (var m : methods)
                                        for (var insn : m.instructions)
                                            if (insn instanceof MethodInsnNode call &&
                                                ("L" + call.owner + ";" + call.name + call.desc)
                                                    .equals(callee))
                                                found = true;
                                    require(found, "Exact render-world callsite " + callee);
                                }
                        }
                    }
            }
            mixins.close();
        }
        System.out.println(checks + " mapped Minecraft mixin target checks passed (no client launched)");
    }
}
