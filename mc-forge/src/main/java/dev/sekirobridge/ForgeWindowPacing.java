package dev.sekirobridge;

import java.io.IOException;
import java.nio.file.*;
import java.util.Properties;
import net.minecraft.client.MinecraftClient;

/** Keep the background MC swap from waiting for a second game's vertical sync. */
public final class ForgeWindowPacing {
    private static Path config;
    private static boolean enabled=true, applied;
    private ForgeWindowPacing() {}
    public static void initialize(Path path) throws IOException {
        config=path;
        enabled=!"false".equalsIgnoreCase(read().getProperty("bridge_disable_vsync","true").trim());
    }
    private static Properties read() throws IOException {
        var p=new Properties();
        if(config!=null && Files.exists(config))try(var stream=Files.newInputStream(config)){p.load(stream);}
        return p;
    }
    public static boolean enabled(){return enabled;}
    public static boolean suppressesVsync(){return enabled && BridgeClient.armed();}
    public static void update(){
        boolean suppress=suppressesVsync();
        if(suppress==applied)return;
        var client=MinecraftClient.getInstance();
        client.getWindow().setVsync(suppress?false:client.options.getEnableVsync().getValue());
        applied=suppress;
    }
    public static void setEnabled(boolean value) throws IOException {
        if(config==null)throw new IOException("Bridge settings are not initialized");
        var p=read();p.setProperty("bridge_disable_vsync",Boolean.toString(value));
        var directory=config.toAbsolutePath().getParent();Files.createDirectories(directory);
        var temporary=Files.createTempFile(directory,"performance-",".tmp");
        try {
            try(var output=Files.newOutputStream(temporary)){p.store(output,"Sekiro bridge client settings");}
            try {Files.move(temporary,config,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e){Files.move(temporary,config,StandardCopyOption.REPLACE_EXISTING);}
            enabled=value;update();
        } finally {Files.deleteIfExists(temporary);}
    }
}
