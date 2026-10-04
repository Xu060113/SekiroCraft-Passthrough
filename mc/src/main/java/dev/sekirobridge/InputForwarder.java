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
    private final Target target;
    private int buttons;
    private long epoch,eventSequence,dx,dy,textSequence,pointerEventTick;
    private boolean initialized,inputEnabled;
    private Object pointerScreen;
    private long pointerGeometry;
    private float pointerX,pointerY;
    private boolean pointerValid;
    // The target keeps replay ordering independent from the GLFW window and lets
    // the same production replay code be exercised without launching either game.
    interface Target {
        boolean screenOpen();
        default Object screenIdentity(){return screenOpen()?this:null;}
        default long cursorGeometry(){return 0;}
        void cursor(float x,float y);
        void key(int key,int action,int mods);
        void button(int button,int action,int mods);
        void scroll(double amount);
        void motion(long x,long y);
        void character(int codepoint,int mods);
        void level(int key,boolean held);
    }
    private static final class MinecraftTarget implements Target {
        private MinecraftClient client(){return MinecraftClient.getInstance();}
        public boolean screenOpen(){return client().currentScreen!=null;}
        public Object screenIdentity(){return client().currentScreen;}
        public long cursorGeometry(){var w=client().getWindow();return ((long)w.getWidth()<<32)|Integer.toUnsignedLong(w.getHeight());}
        public void cursor(float x,float y){var c=client();
            ((InputInvoker)c.mouse).bridgeCursor(c.getWindow().getHandle(),
                Math.max(0,Math.min(1,x))*c.getWindow().getWidth(),Math.max(0,Math.min(1,y))*c.getWindow().getHeight());}
        public void key(int key,int action,int mods){var c=client();
            ((KeyboardInvoker)c.keyboard).bridgeKey(c.getWindow().getHandle(),key,0,action,mods);}
        public void button(int button,int action,int mods){var c=client();
            ((InputInvoker)c.mouse).bridgeButton(c.getWindow().getHandle(),button,action,mods);}
        public void scroll(double amount){var c=client();((InputInvoker)c.mouse).bridgeScroll(c.getWindow().getHandle(),0,amount);}
        public void motion(long x,long y){var c=client();
            ((InputInvoker)c.mouse).bridgeCursor(c.getWindow().getHandle(),c.mouse.getX()+x,c.mouse.getY()+y);}
        public void character(int codepoint,int mods){var c=client();
            ((KeyboardInvoker)c.keyboard).bridgeChar(c.getWindow().getHandle(),codepoint,mods);}
        public void level(int key,boolean held){KeyBinding.setKeyPressed(InputUtil.Type.KEYSYM.createFromCode(key),held);}
    }
    InputForwarder(){this(new MinecraftTarget());}
    InputForwarder(Target target){this.target=target;}
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
    private static boolean forwardedKey(int vk){return vk>=8 && vk<256 && !(vk>=118 && vk<=120) && !(vk>=160 && vk<=165);}
    private void key(int vk,int action,int mods){
        if(!forwardedKey(vk))return;
        int key=glfwKey(vk);if(key==GLFW.GLFW_KEY_UNKNOWN)return;
        if(action==0){if(pressed[vk]==0)return;key=pressed[vk];pressed[vk]=0;}
        else if(action==1){if(pressed[vk]!=0)return;pressed[vk]=key;}
        else if(action==2)pressed[vk]=key; // A repeat can recover a DOWN lost during focus/overflow.
        else return;
        target.key(key,action,mods);
    }
    private void button(int code,int action,int mods){
        int bit=1<<code;if(((buttons&bit)!=0)==(action!=0))return;
        buttons=action!=0?buttons|bit:buttons&~bit;
        target.button(code,action,mods);
    }
    private void cursor(float x,float y){
        x=Math.max(0,Math.min(1,x));y=Math.max(0,Math.min(1,y));
        Object screen=target.screenIdentity();long geometry=target.cursorGeometry();
        // Mouse.onCursorPos invokes Screen.mouseDragged whenever activeButton is
        // held, even for zero movement. Replaying stationary snapshots or the same
        // release coordinate must not manufacture inventory quick-craft drags.
        if(pointerValid && screen==pointerScreen && geometry==pointerGeometry && x==pointerX && y==pointerY)return;
        pointerScreen=screen;pointerGeometry=geometry;pointerX=x;pointerY=y;pointerValid=true;
        target.cursor(x,y);
    }
    void update(Protocol.State s){
        if(NativeBridge.input(BridgeClient.handle(),events))update(s,events,NativeBridge.clockMs());
    }
    void update(Protocol.State s,ByteBuffer events,long now){
        long tick=events.getLong(0);
        if(events.getLong(8)!=s.epoch() || !Protocol.fresh(now,tick))return;
        long next=events.getLong(16),nx=events.getLong(24),ny=events.getLong(32);
        if(!initialized || epoch!=s.epoch()){
            release();initialized=true;epoch=s.epoch();eventSequence=next;dx=nx;dy=ny;textSequence=s.textSequence();pointerEventTick=0;}
        boolean input=(s.flags()&Protocol.EDIT)!=0 && (s.flags()&Protocol.MENU)==0;
        try{replaying=true;
            if(!input)releaseHeld();
            else if(!inputEnabled){
                // Restore held movement/modifier levels after focus or F8. Do not
                // turn a sampled button/key level into a second click/action.
                for(int vk=8;vk<256;++vk)if(forwardedKey(vk) && s.key(vk)){
                    int key=glfwKey(vk);if(key!=GLFW.GLFW_KEY_UNKNOWN)pressed[vk]=key;}
            }
            inputEnabled=input;
            long first=Math.max(eventSequence,next-128);
            if(next-eventSequence>128)releaseHeld();
            for(long i=first;i<next;++i){
                int at=40+(int)(i%128)*32,kind=events.getInt(at),code=events.getInt(at+4),action=events.getInt(at+8);
                int mods=events.getInt(at+12);replayMods=mods;
                if(!input)continue;
                if(kind==1)key(code,action,mods);
                else if(kind==2){pointerEventTick=tick;
                    if(target.screenOpen())cursor(events.getFloat(at+16),events.getFloat(at+20));button(code,action,mods);}
                else if(kind==3){pointerEventTick=tick;
                    if(target.screenOpen())cursor(events.getFloat(at+16),events.getFloat(at+20));
                    target.scroll(events.getInt(at+24)/120.0);}
            }
            eventSequence=next;replayMods=-1;
            int mods=(pressed[16]!=0?1:0)|(pressed[17]!=0?2:0)|(pressed[18]!=0?4:0);
            // Control and the event ring use separate IPC locks. An older Control
            // snapshot must never undo newer edges, and sampled DOWN must never
            // replay a click that was already fully consumed from the event ring.
            if(s.tickMs()>tick){
                for(int vk=8;vk<256;++vk)if(!s.key(vk))key(vk,0,mods);
                for(int b=0;b<3;++b)if((s.buttons()&(1<<b))==0)button(b,0,mods);
            }
            if(!target.screenOpen())
                for(int vk=8;vk<256;++vk){int key=glfwKey(vk);
                    if(key!=GLFW.GLFW_KEY_UNKNOWN && forwardedKey(vk))target.level(key,pressed[vk]!=0);}
            if(input && target.screenOpen() && s.tickMs()>=tick && s.tickMs()>pointerEventTick)
                cursor(s.mouseX(),s.mouseY());
            if(!target.screenOpen())pointerValid=false;
            long mx=nx-dx,my=ny-dy;dx=nx;dy=ny;
            if(input && !target.screenOpen() && Math.abs(mx)<5000 && Math.abs(my)<5000)target.motion(mx,my);
            if(s.textSequence()>=textSequence)
                for(long i=Math.max(textSequence,s.textSequence()-8);i<s.textSequence();++i)
                    if(input && target.screenOpen()){int cp=s.text()[(int)(i%8)];
                        if(Character.isValidCodePoint(cp))target.character(cp,mods);}
            textSequence=s.textSequence();
        }finally{replaying=false;replayMods=-1;}
    }
    private void releaseHeld(){for(int vk=8;vk<256;++vk)key(vk,0,0);for(int i=0;i<3;++i)button(i,0,0);}
    void release(){if(!initialized)return;
        try{replaying=true;releaseHeld();}finally{replaying=false;replayMods=-1;}initialized=false;inputEnabled=false;pointerValid=false;pointerScreen=null;}
}
