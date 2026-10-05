package dev.sekirobridge;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Replays the production InputForwarder against a recording window callback target. */
public final class InputForwarderSelfTest {
    private static int checks;
    private static void check(boolean condition,String name){
        ++checks;if(!condition)throw new AssertionError(name);
    }
    private static final class Target implements InputForwarder.Target {
        boolean screen=true;
        Object identity=new Object();long geometry;int active=-1,drags;
        final List<String> calls=new ArrayList<>();
        final Map<Integer,Boolean> levels=new HashMap<>();
        float x,y;
        public boolean screenOpen(){return screen;}
        public Object screenIdentity(){return screen?identity:null;}
        public long cursorGeometry(){return geometry;}
        // Vanilla Mouse.onCursorPos enters mouseDragged whenever activeButton is
        // set, including a callback with identical coordinates.
        public void cursor(float x,float y){if(active>=0)++drags;this.x=x;this.y=y;calls.add("cursor:"+x+":"+y);}
        public void key(int key,int action,int mods){calls.add("key:"+key+":"+action+":"+mods);}
        public void button(int button,int action,int mods){
            active=action==1?button:-1;
            calls.add("button:"+button+":"+action+":"+mods+":"+x+":"+y);
            check(InputForwarder.replaying,"all button callbacks have bridge ownership");
            if(action==1)check(InputForwarder.replayMods==mods,"press uses event-time modifiers");
        }
        public void scroll(double amount){calls.add("scroll:"+amount);}
        public void motion(long x,long y){if(x!=0 || y!=0)calls.add("motion:"+x+":"+y);}
        public void character(int codepoint,int mods){calls.add("char:"+codepoint+":"+mods);}
        public void level(int key,boolean held){levels.put(key,held);}
        long presses(int button){return calls.stream().filter(s->s.startsWith("button:"+button+":1:")).count();}
        long releases(int button){return calls.stream().filter(s->s.startsWith("button:"+button+":0:")).count();}
    }
    private static Protocol.State control(long tick,int flags,int buttons,int...keys){
        var bytes=Protocol.direct(Protocol.CONTROL_BYTES);
        bytes.putLong(0,tick).putLong(8,tick).putLong(16,7).putInt(24,flags|Protocol.SCENE|Protocol.FOCUS);
        bytes.putFloat(132,.9f).putFloat(136,.8f).putInt(144,buttons);
        for(int key:keys)bytes.put(100+key/8,(byte)(bytes.get(100+key/8)|(1<<(key%8))));
        return Protocol.decode(bytes);
    }
    private static ByteBuffer packet(long tick,long sequence){
        return Protocol.direct(4136).putLong(0,tick).putLong(8,7).putLong(16,sequence);
    }
    private static void event(ByteBuffer packet,long sequence,int kind,int code,int action,int mods,float x,float y){
        int at=40+(int)(sequence%128)*32;
        packet.putInt(at,kind).putInt(at+4,code).putInt(at+8,action).putInt(at+12,mods);
        packet.putFloat(at+16,x).putFloat(at+20,y);
    }
    public static void main(String[] args){run();}
    public static void run(){
        checks=0;
        var target=new Target();var input=new InputForwarder(target);
        input.update(control(100,Protocol.EDIT,0),packet(100,0),100);target.calls.clear();

        // A complete click reaches InputPacket while Control still reports the
        // middle of that click. The old implementation synthesised a second DOWN.
        var click=packet(110,2);
        event(click,0,2,0,1,0,.25f,.3f);event(click,1,2,0,0,0,.26f,.31f);
        input.update(control(100,Protocol.EDIT,1),click,110);
        check(target.presses(0)==1 && target.releases(0)==1,"stale control cannot duplicate a completed inventory click");
        check(target.calls.equals(List.of("cursor:0.25:0.3","button:0:1:0:0.25:0.3",
            "cursor:0.26:0.31","button:0:0:0:0.26:0.31")),"click coordinates precede their edges without an old cursor rollback");
        target.calls.clear();input.update(control(110,Protocol.EDIT,1),click,110);
        check(target.calls.isEmpty(),"re-reading the same packets cannot click or drag again");

        var down=packet(120,3);event(down,2,2,0,1,0,.4f,.5f);
        input.update(control(110,Protocol.EDIT,0),down,120);
        check(target.presses(0)==1 && target.releases(0)==0,"new drag press is not released by an older snapshot");
        target.calls.clear();input.update(control(121,Protocol.EDIT,1),packet(121,3),121);
        check(target.calls.equals(List.of("cursor:0.9:0.8")),"newer cursor snapshots continue dragging");
        var up=packet(122,4);event(up,3,2,0,0,0,.5f,.6f);
        target.calls.clear();input.update(control(121,Protocol.EDIT,1),up,122);
        check(target.releases(0)==1 && target.presses(0)==0,"release is not turned back into a press");

        var shiftClick=packet(130,8);
        event(shiftClick,4,1,16,1,1,0,0);event(shiftClick,5,2,0,1,1,.6f,.7f);
        event(shiftClick,6,2,0,0,1,.6f,.7f);event(shiftClick,7,1,16,0,0,0,0);
        target.calls.clear();input.update(control(129,Protocol.EDIT,0),shiftClick,130);
        check(target.calls.contains("button:0:1:1:0.6:0.7"),"shift click retains modifier when sampled shift is released");

        target.screen=false;var right=packet(140,10);
        event(right,8,2,1,1,0,0,0);event(right,9,2,1,0,0,0,0);
        target.calls.clear();input.update(control(139,Protocol.EDIT,2),right,140);
        check(target.presses(1)==1 && target.releases(1)==1,"right click reaches the use-button callback once");
        target.calls.clear();input.update(control(140,Protocol.EDIT,2),right,140);
        check(target.presses(1)==0,"snapshot use-button level never queues a duplicate use action");

        var movement=packet(150,11);event(movement,10,1,'W',1,0,0,0);
        input.update(control(149,Protocol.EDIT,0),movement,150);
        check(input.held('W') && Boolean.TRUE.equals(target.levels.get((int)'W')),"movement follows newer key events despite stale control");
        target.screen=true;input.update(control(151,Protocol.EDIT,0,'W'),packet(151,11),151);
        target.levels.clear();target.screen=false;
        input.update(control(152,Protocol.EDIT,0,'W'),packet(152,11),152);
        check(Boolean.TRUE.equals(target.levels.get((int)'W')),"closing inventory restores held movement levels without a key action");

        var heldRight=packet(160,12);event(heldRight,11,2,1,1,0,0,0);
        input.update(control(160,Protocol.EDIT,2,'W'),heldRight,160);target.calls.clear();
        input.update(control(161,0,2,'W'),packet(161,12),161);
        check(target.releases(1)==1 && !input.held('W'),"F8 input pause releases held keys and buttons");
        target.calls.clear();input.update(control(162,Protocol.EDIT,2,'W'),packet(162,12),162);
        check(input.held('W') && target.presses(1)==0,"input resume restores held movement without manufacturing a click");

        var lostUp=packet(170,13);event(lostUp,12,2,0,1,0,0,0);
        input.update(control(170,Protocol.EDIT,1,'W'),lostUp,170);target.calls.clear();
        input.update(control(171,Protocol.EDIT,0,'W'),lostUp,171);
        check(target.releases(0)==1 && target.presses(0)==0,"strictly newer physical snapshot may repair a lost release only");
        input.release();
        check(!input.held('W') && !InputForwarder.replaying && InputForwarder.replayMods==-1,"release leaves no owned key or callback state");

        input.update(control(180,Protocol.EDIT,0),packet(180,13),180);target.calls.clear();
        var repeat=packet(190,14);event(repeat,13,1,'W',2,0,0,0);
        input.update(control(189,Protocol.EDIT,0),repeat,190);
        check(input.held('W') && target.calls.contains("key:87:2:0"),"a native repeat recovers a DOWN lost during initial focus handoff");
        input.release();
        target.screen=true;input.update(control(200,Protocol.EDIT,0),packet(200,14),200);target.calls.clear();target.drags=0;
        var stationary=packet(210,16);
        event(stationary,14,2,0,1,0,.5f,.5f);event(stationary,15,2,0,0,0,.5f,.5f);
        input.update(control(209,Protocol.EDIT,0),stationary,210);
        check(target.presses(0)==1 && target.releases(0)==1 && target.drags==0,
            "a stationary click releases without a synthetic quick-craft drag");
        var hold=packet(220,17);event(hold,16,2,0,1,0,.9f,.8f);
        input.update(control(220,Protocol.EDIT,1),hold,220);target.calls.clear();target.drags=0;
        for(long stamp=221;stamp<226;++stamp)input.update(control(stamp,Protocol.EDIT,1),packet(stamp,17),stamp);
        check(target.drags==0 && target.calls.isEmpty(),"held stationary pointer snapshots do not alter inventory drag slots");
        var moved=control(226,Protocol.EDIT,1);moved=new Protocol.State(moved.sequence(),moved.tickMs(),moved.epoch(),moved.flags(),moved.capabilities(),
            moved.px(),moved.py(),moved.pz(),moved.ex(),moved.ey(),moved.ez(),moved.fx(),moved.fy(),moved.fz(),moved.fov(),moved.aspect(),
            moved.near(),moved.far(),moved.yOffset(),moved.scale(),moved.width(),moved.height(),moved.keys(),.8f,.7f,moved.wheel(),
            moved.buttons(),moved.command(),moved.textSequence(),moved.text(),moved.captureYaw());
        input.update(moved,packet(226,17),226);
        check(target.drags==1,"real pointer movement continues vanilla inventory dragging");
        input.release();target.calls.clear();input.update(control(230,Protocol.EDIT,0),packet(230,17),230);
        target.calls.clear();target.identity=new Object();input.update(control(231,Protocol.EDIT,0),packet(231,17),231);
        check(target.calls.size()==1,"new screen receives pointer even at unchanged coordinates");
        target.calls.clear();target.geometry=1;input.update(control(232,Protocol.EDIT,0),packet(232,17),232);
        check(target.calls.size()==1,"resized window refreshes pointer scaling");
        input.release();
        target.screen=false;input.update(control(240,Protocol.EDIT,0),packet(240,17),240);
        var nativeKeys=packet(250,19);event(nativeKeys,17,1,'G',1,0,0,0);event(nativeKeys,18,1,'R',1,0,0,0);
        input.update(control(250,Protocol.EDIT,0,'G','R'),nativeKeys,250);
        check(!input.held('G') && !input.held('R'),"native grapple and finisher keys never trigger Minecraft gameplay bindings");
        var action=packet(260,20);event(action,19,1,'W',1,0,0,0);
        input.update(control(260,Protocol.EDIT|Protocol.NATIVE_ACTION,0,'W'),action,260);
        check(!input.held('W'),"native root motion owns movement during the handoff");
        input.release();target.screen=true;input.update(control(270,Protocol.EDIT|Protocol.NATIVE_DEAD,0),packet(270,20),270);
        var deathClick=packet(280,22);event(deathClick,20,2,0,1,0,.5f,.5f);event(deathClick,21,2,0,0,0,.5f,.5f);
        target.calls.clear();input.update(control(280,Protocol.EDIT|Protocol.NATIVE_DEAD,0),deathClick,280);
        check(target.presses(0)==1 && target.releases(0)==1,"native death retains ordered death-screen mouse clicks");
        input.release();
        System.out.println("PASS "+checks+" production input replay checks");
    }
}
