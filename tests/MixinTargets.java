import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
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
    static List<byte[]> readMixins(Path location) throws Exception {
        var classes = new ArrayList<byte[]>();
        if (Files.isDirectory(location)) {
            try (var files = Files.list(location)) {
                for (var path : files.filter(p -> p.toString().endsWith(".class")).toList())
                    classes.add(Files.readAllBytes(path));
            }
        } else {
            try (var jar = new JarFile(location.toFile())) {
                for (var entry : jar.stream()
                                     .filter(e
                                             -> e.getName().startsWith("dev/sekirobridge/mixin/") &&
                                                    e.getName().endsWith(".class"))
                                     .toList())
                    classes.add(jar.getInputStream(entry).readAllBytes());
            }
        }
        require(!classes.isEmpty(), "Mixin classes exist " + location);
        return classes;
    }
    static JsonObject readReferences(Path location) throws Exception {
        if (Files.isDirectory(location))
            return new JsonObject();
        try (var jar = new JarFile(location.toFile())) {
            var configEntry = jar.getJarEntry("sekirobridge.mixins.json");
            require(configEntry != null, "Production mixin config exists");
            var config = JsonParser
                             .parseString(new String(jar.getInputStream(configEntry).readAllBytes(),
                                                     StandardCharsets.UTF_8))
                             .getAsJsonObject();
            var refmapEntry = jar.getJarEntry(config.get("refmap").getAsString());
            require(refmapEntry != null, "Production reference map exists");
            return JsonParser
                .parseString(
                    new String(jar.getInputStream(refmapEntry).readAllBytes(), StandardCharsets.UTF_8))
                .getAsJsonObject()
                .getAsJsonObject("mappings");
        }
    }
    static String remap(JsonObject references, String mixin, String reference) {
        if (!references.has(mixin))
            return reference;
        var members = references.getAsJsonObject(mixin);
        return members.has(reference) ? members.get(reference).getAsString() : reference;
    }
    static String member(String reference) {
        return reference.startsWith("L") ? reference.substring(reference.indexOf(';') + 1) : reference;
    }
    static List<AnnotationNode> annotations(MethodNode m) {
        var list = new ArrayList<AnnotationNode>();
        if (m.visibleAnnotations != null)
            list.addAll(m.visibleAnnotations);
        if (m.invisibleAnnotations != null)
            list.addAll(m.invisibleAnnotations);
        return list;
    }
    static void verifyCallback(MethodNode handler, MethodNode target, String context) {
        require((handler.access & Opcodes.ACC_STATIC) == (target.access & Opcodes.ACC_STATIC),
                "Callback static modifier " + context);
        require(Type.getReturnType(handler.desc).equals(Type.VOID_TYPE), "Callback returns void " + context);
        Type[] arguments = Type.getArgumentTypes(handler.desc);
        Type[] targetArguments = Type.getArgumentTypes(target.desc);
        Type callback = Type.getObjectType("org/spongepowered/asm/mixin/injection/callback/" +
                                           (Type.getReturnType(target.desc).equals(Type.VOID_TYPE)
                                                ? "CallbackInfo"
                                                : "CallbackInfoReturnable"));
        require(arguments.length > 0 && arguments[arguments.length - 1].equals(callback),
                "Callback info matches target return type " + context);
        require(arguments.length == 1 || arguments.length == targetArguments.length + 1,
                "Callback argument count " + context);
        if (arguments.length > 1)
            for (int i = 0; i < targetArguments.length; i++)
                require(arguments[i].equals(targetArguments[i]), "Callback argument " + i + " " + context);
    }
    public static void main(String[] args) throws Exception {
        var references = readReferences(Path.of(args[1]));
        try (var game = new JarFile(args[0])) {
            for (byte[] bytes : readMixins(Path.of(args[1]))) {
                var c = read(bytes);
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
                            String mapped = member(remap(references, c.name, name));
                            require(target.methods.stream().anyMatch(
                                        m
                                        -> (m.name.equals(mapped) || (m.name + m.desc).equals(mapped)) &&
                                               m.desc.equals(method.desc)),
                                    "Invoker/shadow descriptor " + c.name + " " + name + method.desc);
                        }
                        if (!a.desc.endsWith("/Inject;"))
                            continue;
                        @SuppressWarnings("unchecked") var selectors = (List<String>)value(a, "method");
                        for (String selector : selectors) {
                            String mappedSelector = member(remap(references, c.name, selector));
                            var methods = target.methods.stream()
                                              .filter(m
                                                      -> (m.name + m.desc).equals(mappedSelector) ||
                                                             m.name.equals(mappedSelector))
                                              .toList();
                            require(!methods.isEmpty(), "Injection selector " + c.name + " -> " + selector);
                            for (var selected : methods)
                                verifyCallback(method, selected,
                                               c.name + " -> " + selected.name + selected.desc);
                            @SuppressWarnings("unchecked") var ats = (List<AnnotationNode>)value(a, "at");
                            for (var at : ats)
                                if ("INVOKE".equals(value(at, "value"))) {
                                    String callee = remap(references, c.name, (String)value(at, "target"));
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
        }
        System.out.println(checks + " mapped Minecraft mixin target checks passed (no client launched)");
    }
}
