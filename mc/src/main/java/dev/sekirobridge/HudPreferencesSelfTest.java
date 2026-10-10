package dev.sekirobridge;

import java.nio.file.Files;
import java.util.Properties;

public final class HudPreferencesSelfTest {
    private static int checks;
    private static void check(boolean ok,String reason){checks++;if(!ok)throw new AssertionError(reason);}
    public static void main(String[] args)throws Exception {
        var directory=Files.createTempDirectory("sekiro-hud-preferences-");
        var file=directory.resolve("bridge.properties");
        try {
            var settings=new HudPreferences(file);settings.load();
            check(settings.visible(),"existing profiles default to visible posture bars");
            Files.writeString(file,"channel=custom-channel\ngui_trace=true\ncustom_option=keep\n");
            settings.setVisible(false);
            check(!settings.visible(),"hide takes effect immediately after saving");
            var reloaded=new HudPreferences(file);reloaded.load();
            check(!reloaded.visible(),"hidden setting survives restarting the client");
            var properties=new Properties();try(var input=Files.newInputStream(file)){properties.load(input);}
            check("custom-channel".equals(properties.getProperty("channel")) && "true".equals(properties.getProperty("gui_trace")) &&
                "keep".equals(properties.getProperty("custom_option")),"saving HUD preference preserves bridge channel and unknown options");
            reloaded.setVisible(true);settings.load();
            check(settings.visible(),"show persists across another instance reload");
            Files.writeString(file,"show_posture_hud=invalid\n");settings.load();
            check(settings.visible(),"invalid optional preference retains the compatible default");
            var blocked=new HudPreferences(directory);
            try{blocked.setVisible(false);throw new AssertionError("expected failed write");}catch(java.io.IOException expected){}
            check(blocked.visible(),"failed save does not silently change runtime visibility");
            try(var entries=Files.list(directory)){check(entries.count()==1,"no temporary preferences files remain");}
        }finally{Files.deleteIfExists(file);Files.deleteIfExists(directory);}
        System.out.println(checks+" persistent HUD preference checks passed");
    }
}
