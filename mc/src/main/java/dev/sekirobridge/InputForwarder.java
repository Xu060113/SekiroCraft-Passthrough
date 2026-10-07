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
    public static boolean guiTrace;
    private static int tracedClicks;
    private record GuiProbe(java.util.UUID player,int syncId,long after){}
    private static GuiProbe pendingProbe;
    static void traceGuiResult(){
        var probe=pendingProbe;if(probe==null || NativeBridge.clockMs()<probe.after())return;
        pendingProbe=null;var server=MinecraftClient.getInstance().getServer();if(server==null)return;
        server.execute(()->{var p=server.getPlayerManager().getPlayer(probe.player());if(p!=null)
            BridgeClient.LOG.info("GUI server after-click expectedSyncId={} syncId={} revision={} cursorCount={}",probe.syncId(),
                p.currentScreenHandler.syncId,p.currentScreenHandler.getRevision(),p.currentScreenHandler.getCursorStack().getCount());});
    }
    private final int[] pressed=new int[256];
    private final ByteBuffer events=Protocol.direct(Protocol.INPUT_BYTES);
    private final Target target;
    private int buttons;
    private long epoch,eventSequence,dx,dy,textSequence,pointerEventTick;
    private boolean initialized,inputEnabled;
    private Object pointerScreen;
    private long pointerGeometry;
    private float pointerX,pointerY;
    private boolean pointerValid;
    private Object inputScreen;
    private long inputGeometry;
    // The target keeps replay ordering independent from the GLFW window and lets
    // the same production replay code be exercised without launching either game.
    interface Target {
        boolean screenOpen();
        default Object screenIdentity(){return screenOpen()?this:null;}
        default long cursorGeometry(){return 0;}
        default long guiGeneration(){return screenOpen()?1:0;}
        default void cancelPointer(){}
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
        public long guiGeneration(){return GuiIdentity.current();}
        public long cursorGeometry(){return GuiIdentity.current();}
        public void cancelPointer(){var mouse=(InputInvoker)client().mouse;mouse.bridgeActiveButton(-1);mouse.bridgePressTime(0);
            for(int b=0;b<Protocol.MOUSE_BUTTONS;b++)KeyBinding.setKeyPressed(InputUtil.Type.MOUSE.createFromCode(b),false);}
        public void cursor(float x,float y){var c=client();
            ((InputInvoker)c.mouse).bridgeCursor(c.getWindow().getHandle(),
                x*c.getWindow().getWidth(),y*c.getWindow().getHeight());}
        public void key(int key,int action,int mods){var c=client();
            ((KeyboardInvoker)c.keyboard).bridgeKey(c.getWindow().getHandle(),key,0,action,mods);}
        public void button(int button,int action,int mods){var c=client();
            if(guiTrace && action==1 && c.currentScreen!=null && tracedClicks++<64){
                double x=c.mouse.getX()*c.getWindow().getScaledWidth()/c.getWindow().getWidth();
                double y=c.mouse.getY()*c.getWindow().getScaledHeight()/c.getWindow().getHeight();
                var slot=c.currentScreen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen<?>?
                    ((dev.sekirobridge.mixin.HandledScreenAccess)c.currentScreen).bridgeSlotAt(x,y):null;
                BridgeClient.LOG.info("GUI click generation={} screen={} size={}x{} point={},{} slot={} button={} mods={}",
                    GuiIdentity.current(),c.currentScreen.getClass().getSimpleName(),c.getWindow().getScaledWidth(),
                    c.getWindow().getScaledHeight(),x,y,slot==null?-1:slot.id,button,mods);
                if(c.getServer()!=null && c.player!=null)
                    pendingProbe=new GuiProbe(c.player.getUuid(),c.player.currentScreenHandler.syncId,NativeBridge.clockMs()+150);
            }
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
            case 20 -> GLFW.GLFW_KEY_CAPS_LOCK;
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
    private static boolean forwardedKey(int vk){return vk>=8 && vk<256 && !(vk>=117 && vk<=120) && !(vk>=160 && vk<=165);}
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
        if(code<0 || code>=Protocol.MOUSE_BUTTONS || (action!=0 && action!=1))return;
        int bit=1<<code;if(((buttons&bit)!=0)==(action!=0))return;
        buttons=action!=0?buttons|bit:buttons&~bit;
        target.button(code,action,mods);
    }
    private void cursor(float x,float y){
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
        boolean input=(s.flags()&Protocol.EDIT)!=0 && (s.flags()&(Protocol.MENU|Protocol.NATIVE_UI|Protocol.NATIVE_CINEMATIC))==0;
        boolean lookInput=input;
        boolean nativeAction=(s.flags()&Protocol.NATIVE_ACTION)!=0 && !target.screenOpen();
        if(nativeAction && (s.flags()&Protocol.NATIVE_GRAPPLE)==0)input=false;
        try{replaying=true;
            Object screen=target.screenIdentity();long geometry=target.cursorGeometry();
            if(screen!=inputScreen || geometry!=inputGeometry){
                target.cancelPointer();buttons=0;pointerValid=false;
                inputScreen=screen;inputGeometry=geometry;
            }
            long first=Math.max(eventSequence,next-128);
            if(!input)releaseHeld();
            else if(!inputEnabled){
                // Restore held movement/modifier levels after focus or F8. Do not
                // turn a sampled button/key level into a second click/action.
                // A queued real key edge owns its state: pre-seeding that key
                // would swallow a fresh DOWN (for example R on the resume tick).
                boolean[] queuedKeys=new boolean[256];
                for(long i=first;i<next;++i){
                    int at=Protocol.INPUT_HEADER+(int)(i%128)*Protocol.INPUT_EVENT;
                    int kind=events.getInt(at),code=events.getInt(at+4),action=events.getInt(at+8);
                    if(kind==1 && code>=0 && code<256 && action>=0 && action<=2)queuedKeys[code]=true;
                }
                for(int vk=8;vk<256;++vk)if(!queuedKeys[vk] && forwardedKey(vk) && s.key(vk) && (target.screenOpen() || vk!='M')){
                    int key=glfwKey(vk);if(key!=GLFW.GLFW_KEY_UNKNOWN)pressed[vk]=key;}
            }
            inputEnabled=input;
            if(next-eventSequence>128)releaseHeld();
            for(long i=first;i<next;++i){
                int at=Protocol.INPUT_HEADER+(int)(i%128)*Protocol.INPUT_EVENT,kind=events.getInt(at),code=events.getInt(at+4),action=events.getInt(at+8);
                int mods=events.getInt(at+12);replayMods=mods;
                if(!input)continue;
                long stamp=events.getLong(at+32),generation=events.getLong(at+40);
                boolean pointerMatches=target.screenOpen() && generation!=0 && generation==target.guiGeneration() && Protocol.fresh(now,stamp);
                if(kind==1){if(!target.screenOpen() && code=='M')continue;key(code,action,mods);}
                else if(kind==2){pointerEventTick=Math.max(pointerEventTick,stamp);
                    if(target.screenOpen()){
                        float x=events.getFloat(at+16),y=events.getFloat(at+20);
                        if(!pointerMatches){if(action==0)button(code,0,mods);continue;}
                        // Outside the drawn overlay is outside every MC widget.
                        if(action!=0 && (x<0 || x>1 || y<0 || y>1))continue;
                        cursor(x,y);
                    }button(code,action,mods);}
                else if(kind==3){pointerEventTick=Math.max(pointerEventTick,stamp);
                    if(target.screenOpen()){
                        if(!pointerMatches)continue;
                        float x=events.getFloat(at+16),y=events.getFloat(at+20);
                        if(x<0 || x>1 || y<0 || y>1)continue;cursor(x,y);}
                    target.scroll(events.getInt(at+24)/120.0);}
            }
            eventSequence=next;replayMods=-1;
            int mods=(pressed[16]!=0?1:0)|(pressed[17]!=0?2:0)|(pressed[18]!=0?4:0);
            // Control and the event ring use separate IPC locks. An older Control
            // snapshot must never undo newer edges, and sampled DOWN must never
            // replay a click that was already fully consumed from the event ring.
            if(s.tickMs()>tick){
                for(int vk=8;vk<256;++vk)if(!s.key(vk))key(vk,0,mods);
                for(int b=0;b<Protocol.MOUSE_BUTTONS;++b)if((s.buttons()&(1<<b))==0)button(b,0,mods);
            }
            if(!target.screenOpen())
                for(int vk=8;vk<256;++vk){int key=glfwKey(vk);
                    if(key!=GLFW.GLFW_KEY_UNKNOWN && forwardedKey(vk))target.level(key,pressed[vk]!=0);}
            if(input && target.screenOpen() && events.getLong(48)==target.guiGeneration() &&
                Protocol.fresh(now,events.getLong(56)) && events.getLong(56)>pointerEventTick)
                cursor(events.getFloat(40),events.getFloat(44));
            if(!target.screenOpen())pointerValid=false;
            long mx=nx-dx,my=ny-dy;dx=nx;dy=ny;
            // Native translation never owns Minecraft's camera. Continue consuming
            // look deltas throughout a grapple, including its exit handshake.
            if(lookInput && !target.screenOpen() && Math.abs(mx)<5000 && Math.abs(my)<5000)target.motion(mx,my);
            if(s.textSequence()>=textSequence)
                for(long i=Math.max(textSequence,s.textSequence()-8);i<s.textSequence();++i)
                    if(input && target.screenOpen()){int cp=s.text()[(int)(i%8)];
                        if(Character.isValidCodePoint(cp))target.character(cp,mods);}
            textSequence=s.textSequence();
        }finally{replaying=false;replayMods=-1;}
    }
    private void releaseHeld(){for(int vk=8;vk<256;++vk)key(vk,0,0);for(int i=0;i<Protocol.MOUSE_BUTTONS;++i)button(i,0,0);}
    void release(){if(!initialized)return;
        try{replaying=true;releaseHeld();}finally{replaying=false;replayMods=-1;}initialized=false;inputEnabled=false;pointerValid=false;pointerScreen=null;}
}
