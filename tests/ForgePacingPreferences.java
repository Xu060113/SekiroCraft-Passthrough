package dev.sekirobridge;

import java.nio.file.*;
import java.util.Properties;

/** Performance/HUD settings share a profile without overwriting channel or tracing. */
public final class ForgePacingPreferences {
    private static int checks;
    private static void require(boolean ok,String reason){checks++;if(!ok)throw new AssertionError(reason);}
    private static Properties read(Path path)throws Exception{
        var p=new Properties();try(var input=Files.newInputStream(path)){p.load(input);}return p;
    }
    public static void main(String[] args)throws Exception{
        var directory=Files.createTempDirectory("forge-pacing-check-");
        var path=directory.resolve("bridge.properties");
        try {
            ForgeWindowPacing.initialize(path);
            require(ForgeWindowPacing.enabled() && !Files.exists(path),"default pacing needs no option-file write");
            ForgeWindowPacing.update();
            require(!ForgeWindowPacing.suppressesVsync(),"standalone/dormant client keeps normal window settings");
            var p=new Properties();p.setProperty("channel","custom-channel");p.setProperty("gui_trace","true");
            try(var output=Files.newOutputStream(path)){p.store(output,"fixture");}
            var hud=new HudPreferences(path);hud.setVisible(false);
            ForgeWindowPacing.setEnabled(false);
            p=read(path);
            require("custom-channel".equals(p.getProperty("channel")) && "true".equals(p.getProperty("gui_trace")),"performance save preserves transport and tracing");
            require("false".equals(p.getProperty("show_posture_hud")),"performance save preserves HUD choice");
            ForgeWindowPacing.setEnabled(true);hud.setVisible(true);
            ForgeWindowPacing.initialize(path);
            require(ForgeWindowPacing.enabled() && "true".equals(read(path).getProperty("bridge_disable_vsync")),"HUD save preserves performance choice and reload");
            ForgeWindowPacing.setEnabled(false);ForgeWindowPacing.initialize(path);
            require(!ForgeWindowPacing.enabled() && !ForgeWindowPacing.suppressesVsync(),"disabled preference survives restart without native session");
            try{NativeBridge.clockMs();throw new AssertionError("fixture must leave JNI unloaded");}
            catch(UnsatisfiedLinkError expected){checks++;}
            try(var files=Files.list(directory)){require(files.count()==1,"atomic saves leave no temporary files");}
        } finally {Files.deleteIfExists(path);Files.deleteIfExists(directory);}
        System.out.println(checks+" Forge window pacing/profile coexistence checks passed (no game launched)");
    }
}
