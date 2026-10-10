package dev.sekirobridge;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** Profile-local display preferences; does not change combat or vanilla HUD state. */
final class HudPreferences {
    static final String POSTURE_KEY="show_posture_hud";
    private final Path config;
    private volatile boolean visible=true;
    HudPreferences(Path config){this.config=config;}
    boolean visible(){return visible;}
    private Properties read()throws IOException {
        var properties=new Properties();
        if(Files.exists(config))try(var input=Files.newInputStream(config)){properties.load(input);}
        return properties;
    }
    void load()throws IOException {
        visible=!"false".equalsIgnoreCase(read().getProperty(POSTURE_KEY,"true").trim());
    }
    void setVisible(boolean show)throws IOException {
        var properties=read(); // Preserve channel, tracing and unknown options.
        properties.setProperty(POSTURE_KEY,Boolean.toString(show));
        var directory=config.toAbsolutePath().getParent();
        Files.createDirectories(directory);
        var temporary=Files.createTempFile(directory,"hud-preferences-",".tmp");
        try {
            try(var output=Files.newOutputStream(temporary)){properties.store(output,"Sekiro bridge client settings");}
            try{Files.move(temporary,config,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e){Files.move(temporary,config,StandardCopyOption.REPLACE_EXISTING);}
            visible=show;
        }finally{Files.deleteIfExists(temporary);}
    }
}
