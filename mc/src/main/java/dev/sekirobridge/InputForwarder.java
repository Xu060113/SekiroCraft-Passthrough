package dev.sekirobridge;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import dev.sekirobridge.mixin.InputInvoker;
import dev.sekirobridge.mixin.KeyboardInvoker;
import org.lwjgl.glfw.GLFW;
import java.nio.ByteBuffer;

public final class InputForwarder {
    public static boolean replaying;
    public static int replayMods=-1;
    private final int[] pressed=new int[256];
    private final ByteBuffer events=Protocol.direct(4136);
    private int buttons;
    private long epoch,eventSequence,dx,dy,textSequence;
    private boolean initialized;
    boolean held(int key){for(int vk:pressed)if(vk!=0 && vk==key)return true;return false;}
    static int glfwKey(int vk) {
        if(vk>=48 && vk<=57 || vk>=65 && vk<=90)return vk;
        if(vk>=112 && vk<=123)return GLFW.GLFW_KEY_F1+vk-112;
        return switch(vk){
            case 8 -> GLFW.GLFW_KEY_BACKSPACE; case 9 -> GLFW.GLFW_KEY_TAB;
            case 13 -> GLFW.GLFW_KEY_ENTER; case 16,160 -> GLFW.GLFW_KEY_LEFT_SHIFT;
            case 161 -> GLFW.GLFW_KEY_RIGHT_SHIFT; case 17,162 -> GLFW.GLFW_KEY_LEFT_CONTROL;
            case 163 -> GLFW.GLFW_KEY_RIGHT_CONTROL; case 18,164 -> GLFW.GLFW_KEY_LEFT_ALT;
            case 165 -> GLFW.GLFW_KEY_RIGHT_ALT; case 27 -> GLFW.GLFW_KEY_ESCAPE;
            case 32 -> GLFW.GLFW_KEY_SPACE; case 33 -> GLFW.GLFW_KEY_PAGE_UP;
            case 34 -> GLFW.GLFW_KEY_PAGE_DOWN; case 35 -> GLFW.GLFW_KEY_END;
            case 36 -> GLFW.GLFW_KEY_HOME; case 37 -> GLFW.GLFW_KEY_LEFT;
            case 38 -> GLFW.GLFW_KEY_UP; case 39 -> GLFW.GLFW_KEY_RIGHT;
            case 40 -> GLFW.GLFW_KEY_DOWN; case 46 -> GLFW.GLFW_KEY_DELETE;
            case 186 -> GLFW.GLFW_KEY_SEMICOLON; case 187 -> GLFW.GLFW_KEY_EQUAL;
            case 188 -> GLFW.GLFW_KEY_COMMA; case 189 -> GLFW.GLFW_KEY_MINUS;
            case 190 -> GLFW.GLFW_KEY_PERIOD; case 191 -> GLFW.GLFW_KEY_SLASH;
            case 192 -> GLFW.GLFW_KEY_GRAVE_ACCENT; case 219 -> GLFW.GLFW_KEY_LEFT_BRACKET;
            case 220 -> GLFW.GLFW_KEY_BACKSLASH; case 221 -> GLFW.GLFW_KEY_RIGHT_BRACKET;
            case 222 -> GLFW.GLFW_KEY_APOSTROPHE; default -> GLFW.GLFW_KEY_UNKNOWN;
        };
    }
    private void cursor(float x,float y){var c=MinecraftClient.getInstance();
        ((InputInvoker)c.mouse).bridgeCursor(c.getWindow().getHandle(),
            Math.max(0,Math.min(1,x))*c.getWindow().getWidth(),Math.max(0,Math.min(1,y))*c.getWindow().getHeight());}
    private void key(int vk,int action,int mods){
        if(vk<8 || vk>=256 || vk>=118 && vk<=120 || vk>=160 && vk<=165)return;
        int key=glfwKey(vk);if(key==GLFW.GLFW_KEY_UNKNOWN)return;
        var c=MinecraftClient.getInstance();
        if(action==0){if(pressed[vk]==0)return;key=pressed[vk];pressed[vk]=0;}
        else if(action==1){if(pressed[vk]!=0)return;pressed[vk]=key;}
        else if(pressed[vk]==0)return;
        ((KeyboardInvoker)c.keyboard).bridgeKey(c.getWindow().getHandle(),key,0,action,mods);
    }
    private void button(int code,int action,int mods){
        int bit=1<<code;if(((buttons&bit)!=0)==(action!=0))return;
        buttons=action!=0?buttons|bit:buttons&~bit;
        var c=MinecraftClient.getInstance();
        ((InputInvoker)c.mouse).bridgeButton(c.getWindow().getHandle(),code,action,mods);
    }
    void update(Protocol.State s){
        var c=MinecraftClient.getInstance();
        if(!NativeBridge.input(BridgeClient.handle(),events) || events.getLong(8)!=s.epoch() ||
            !Protocol.fresh(NativeBridge.clockMs(),events.getLong(0)))return;
        long next=events.getLong(16),nx=events.getLong(24),ny=events.getLong(32);
        if(!initialized || epoch!=s.epoch()){
            release();initialized=true;epoch=s.epoch();eventSequence=next;dx=nx;dy=ny;textSequence=s.textSequence();}
        boolean input=(s.flags()&Protocol.EDIT)!=0 && (s.flags()&Protocol.MENU)==0;
        try{replaying=true;
            long first=Math.max(eventSequence,next-128);
            if(next-eventSequence>128)releaseHeld();
            for(long i=first;i<next;++i){
                int at=40+(int)(i%128)*32,kind=events.getInt(at),code=events.getInt(at+4),action=events.getInt(at+8);
                int mods=events.getInt(at+12);replayMods=mods;
                if(!input)continue;
                if(kind==1)key(code,action,mods);
                else if(kind==2){if(c.currentScreen!=null)cursor(events.getFloat(at+16),events.getFloat(at+20));button(code,action,mods);}
                else if(kind==3){if(c.currentScreen!=null)cursor(events.getFloat(at+16),events.getFloat(at+20));
                    ((InputInvoker)c.mouse).bridgeScroll(c.getWindow().getHandle(),0,events.getInt(at+24)/120.0);}
            }
            eventSequence=next;replayMods=-1;
            int mods=(s.key(16)?1:0)|(s.key(17)?2:0)|(s.key(18)?4:0);
            for(int vk=8;vk<256;++vk)key(vk,input && s.key(vk)?1:0,mods);
            if(c.currentScreen==null)
                for(int vk=8;vk<256;++vk){int key=glfwKey(vk);
                    if(key!=GLFW.GLFW_KEY_UNKNOWN && !(vk>=160 && vk<=165) && !(vk>=118 && vk<=120))
                        KeyBinding.setKeyPressed(InputUtil.Type.KEYSYM.createFromCode(key),pressed[vk]!=0);}
            for(int b=0;b<3;++b)button(b,input && (s.buttons()&(1<<b))!=0?1:0,mods);
            if(input && c.currentScreen!=null)cursor(s.mouseX(),s.mouseY());
            long mx=nx-dx,my=ny-dy;dx=nx;dy=ny;
            if(input && c.currentScreen==null && Math.abs(mx)<5000 && Math.abs(my)<5000)
                ((InputInvoker)c.mouse).bridgeCursor(c.getWindow().getHandle(),c.mouse.getX()+mx,c.mouse.getY()+my);
            if(s.textSequence()>=textSequence)
                for(long i=Math.max(textSequence,s.textSequence()-8);i<s.textSequence();++i)
                    if(input && c.currentScreen!=null){int cp=s.text()[(int)(i%8)];
                        if(Character.isValidCodePoint(cp))((KeyboardInvoker)c.keyboard).bridgeChar(c.getWindow().getHandle(),cp,mods);}
            textSequence=s.textSequence();
        }finally{replaying=false;replayMods=-1;}
    }
    private void releaseHeld(){for(int vk=8;vk<256;++vk)key(vk,0,0);for(int i=0;i<3;++i)button(i,0,0);}
    void release(){if(!initialized)return;
        try{replaying=true;releaseHeld();}finally{replaying=false;replayMods=-1;}initialized=false;}
}
