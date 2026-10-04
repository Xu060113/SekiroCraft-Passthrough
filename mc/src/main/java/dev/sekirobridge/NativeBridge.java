package dev.sekirobridge;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class NativeBridge {
    private NativeBridge() {}
    public static void load(Path directory) throws Exception {
        if (!System.getProperty("os.name").startsWith("Windows") ||
            !System.getProperty("os.arch").equals("amd64"))
            throw new IllegalStateException("Windows x64 is required");
        byte[] bytes;
        try (var stream =
                 NativeBridge.class.getResourceAsStream("/native/windows-x64/sekirobridge-jni.dll")) {
            if (stream == null)
                throw new IllegalStateException("JNI DLL is missing; build the native target first");
            bytes = stream.readAllBytes();
        }
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Path dll = directory.resolve("native").resolve(sha).resolve("sekirobridge-jni.dll");
        Files.createDirectories(dll.getParent());
        if (!Files.exists(dll) || !MessageDigest.isEqual(bytes, Files.readAllBytes(dll)))
            Files.write(dll, bytes);
        System.load(dll.toAbsolutePath().toString());
    }
    public static native long open(String channel);
    public static native void close(long handle);
    public static native boolean control(long handle, ByteBuffer destination);
    public static native boolean publish(long handle, ByteBuffer metadata, ByteBuffer pixels);
    public static native boolean status(long handle, int flags, long epoch);
    public static native long clockMs();
    public static native boolean physics(long handle, ByteBuffer packet);
}
