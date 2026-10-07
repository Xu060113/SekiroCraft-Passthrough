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
        long generation=1;
        final List<String> calls=new ArrayList<>();
        final Map<Integer,Boolean> levels=new HashMap<>();
        float x,y;
        public boolean screenOpen(){return screen;}
        public Object screenIdentity(){return screen?identity:null;}
        public long cursorGeometry(){return geometry;}
        public long guiGeneration(){return screen?generation:0;}
        public void cancelPointer(){active=-1;}
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
        return Protocol.direct(Protocol.INPUT_BYTES).putLong(0,tick).putLong(8,7).putLong(16,sequence)
            .putFloat(40,.9f).putFloat(44,.8f).putLong(48,1).putLong(56,tick);
    }
    private static void event(ByteBuffer packet,long sequence,int kind,int code,int action,int mods,float x,float y){
        int at=Protocol.INPUT_HEADER+(int)(sequence%128)*Protocol.INPUT_EVENT;
        packet.putInt(at,kind).putInt(at+4,code).putInt(at+8,action).putInt(at+12,mods);
        packet.putFloat(at+16,x).putFloat(at+20,y);
        packet.putLong(at+32,packet.getLong(0)).putLong(at+40,1);
    }
    public static void main(String[] args){run();}
    public static void run(){
        checks=0;
        check(InputForwarder.glfwKey(20)==280,"installed gun mod's Caps Lock binding maps to the GLFW key code");
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
        input.update(moved,packet(226,17).putFloat(40,.8f).putFloat(44,.7f),226);
        check(target.drags==1,"real pointer movement continues vanilla inventory dragging");
        input.release();target.calls.clear();input.update(control(230,Protocol.EDIT,0),packet(230,17),230);
        target.calls.clear();target.identity=new Object();input.update(control(231,Protocol.EDIT,0),packet(231,17),231);
        check(target.calls.size()==1,"new screen receives pointer even at unchanged coordinates");
        target.calls.clear();target.geometry=1;input.update(control(232,Protocol.EDIT,0),packet(232,17),232);
        check(target.calls.size()==1,"resized window refreshes pointer scaling");
        input.release();
        target.screen=false;input.update(control(240,Protocol.EDIT,0),packet(240,17),240);
        var nativeKeys=packet(250,19);event(nativeKeys,17,1,'M',1,0,0,0);event(nativeKeys,18,1,'R',1,0,0,0);
        input.update(control(250,Protocol.EDIT,0,'M','R'),nativeKeys,250);
        check(!input.held('M') && input.held('R'),"only M grapple is reserved; R reaches Minecraft mod gameplay bindings");
        check(target.calls.contains("key:82:1:0") && Boolean.TRUE.equals(target.levels.get(82)),
            "R sends an actual key press callback and held level for gun mods");
        var action=packet(260,20);event(action,19,1,'W',1,0,0,0);
        action.putLong(24,5).putLong(32,2);target.calls.clear();
        input.update(control(260,Protocol.EDIT|Protocol.NATIVE_ACTION,0,'W'),action,260);
        check(!input.held('W'),"native root motion owns movement during the handoff");
        check(target.calls.contains("motion:5:2"),"native root motion never freezes MC mouse-look");
        var grapple=packet(265,21).putLong(24,10).putLong(32,6);event(grapple,20,1,'W',1,0,0,0);
        target.calls.clear();input.update(control(265,Protocol.EDIT|Protocol.NATIVE_ACTION|Protocol.NATIVE_GRAPPLE,0,'W',32,16,'G','M'),grapple,265);
        check(input.held('W') && input.held(32) && input.held(340),"MC still owns movement, jump and sprint keys during a grapple");
        check(input.held('G') && !input.held('M'),"G remains an ordinary MC key while only M is reserved for grapple");
        check(target.calls.contains("motion:5:4"),"grapple camera receives deltas throughout native translation");
        target.calls.clear();var grappleExit=packet(266,21).putLong(24,15).putLong(32,8);
        input.update(control(266,Protocol.EDIT,0,'W'),grappleExit,266);
        check(input.held('W') && target.calls.contains("motion:5:2"),"grapple exit retains MC movement and camera without reacquiring the window");
        input.release();target.screen=true;input.update(control(270,Protocol.EDIT|Protocol.NATIVE_DEAD,0),packet(270,20),270);
        var deathClick=packet(280,22);event(deathClick,20,2,0,1,0,.5f,.5f);event(deathClick,21,2,0,0,0,.5f,.5f);
        target.calls.clear();input.update(control(280,Protocol.EDIT|Protocol.NATIVE_DEAD,0),deathClick,280);
        check(target.presses(0)==1 && target.releases(0)==1,"native death retains ordered death-screen mouse clicks");
        input.release();
        input.release();target.calls.clear();target.screen=true;target.generation=2;
        input.update(control(300,Protocol.EDIT,0),packet(300,22),300);target.calls.clear();
        var staleScreen=packet(310,24);event(staleScreen,22,2,0,1,0,.25f,.25f);event(staleScreen,23,2,0,0,0,.25f,.25f);
        input.update(control(310,Protocol.EDIT,0),staleScreen,310);
        check(target.presses(0)==0 && target.calls.isEmpty(),"old rendered Screen cannot click or move a replacement Screen");
        var freshScreen=packet(320,26).putLong(48,2);event(freshScreen,24,2,0,1,0,.25f,.25f);event(freshScreen,25,2,0,0,0,.25f,.25f);
        freshScreen.putLong(64+24*48+40,2).putLong(64+25*48+40,2);
        input.update(control(320,Protocol.EDIT,0),freshScreen,320);
        check(target.presses(0)==1 && target.releases(0)==1,"new Screen receives clicks only from its displayed generation");
        target.calls.clear();var bar=packet(330,28).putLong(48,2);event(bar,26,2,0,1,0,-.1f,.5f);event(bar,27,2,0,0,0,-.1f,.5f);
        bar.putLong(64+26*48+40,2).putLong(64+27*48+40,2);input.update(control(330,Protocol.EDIT,0),bar,330);
        check(target.presses(0)==0,"a black bar does not clamp to an edge inventory slot");
        target.calls.clear();var nativeMenu=packet(340,30).putLong(48,2);event(nativeMenu,28,2,0,1,0,.5f,.5f);event(nativeMenu,29,2,0,0,0,.5f,.5f);
        nativeMenu.putLong(64+28*48+40,2).putLong(64+29*48+40,2);
        input.update(control(340,Protocol.EDIT|Protocol.NATIVE_UI,0),nativeMenu,340);
        check(target.presses(0)==0,"Sekiro UI ownership cannot click an underlying MC Screen");
        target.calls.clear();var movie=packet(350,32).putLong(48,2);
        event(movie,30,2,0,1,0,.5f,.5f);event(movie,31,2,0,0,0,.5f,.5f);
        movie.putLong(64+30*48+40,2).putLong(64+31*48+40,2);
        input.update(control(350,Protocol.EDIT|Protocol.NATIVE_CINEMATIC,0),movie,350);
        check(target.presses(0)==0,"movie input cannot click an underlying MC inventory");
        target.calls.clear();input.update(control(360,Protocol.EDIT,0),packet(360,32).putLong(48,2),360);
        check(target.presses(0)==0,"movie clicks are consumed and never replay on exit");
        input.release();target.screen=false;target.calls.clear();
        var extraInput=new InputForwarder(target);
        extraInput.update(control(1000,Protocol.EDIT,0),packet(1000,0),1000);target.calls.clear();
        var reload=packet(1010,2);event(reload,0,1,'R',1,0,0,0);event(reload,1,1,'R',0,0,0,0);
        extraInput.update(control(1009,Protocol.EDIT,0),reload,1010);
        check(target.calls.equals(List.of("key:82:1:0","key:82:0:0")),"a quick R tap reaches gun event listeners once, even without a sampled hold");
        target.calls.clear();extraInput.update(control(1010,Protocol.EDIT,0),reload,1010);
        check(target.calls.isEmpty(),"rereading a reload packet cannot reload twice");
        long next=2;
        for(int side=3;side<=4;side++){
            long stamp=1020+side;
            var sideClick=packet(stamp,next+2);
            event(sideClick,next,2,side,1,2,0,0);event(sideClick,next+1,2,side,0,2,0,0);next+=2;
            target.calls.clear();extraInput.update(control(stamp-1,Protocol.EDIT,1<<side),sideClick,stamp);
            check(target.presses(side)==1 && target.releases(side)==1,"both side buttons deliver press/release callbacks with event modifiers");
            target.calls.clear();extraInput.update(control(stamp,Protocol.EDIT,1<<side),sideClick,stamp);
            check(target.presses(side)==0 && target.releases(side)==0,"held side snapshot cannot repeat a completed action");
        }
        var sideDown=packet(1040,++next);event(sideDown,next-1,2,4,1,0,0,0);
        extraInput.update(control(1040,Protocol.EDIT,16),sideDown,1040);target.calls.clear();
        extraInput.update(control(1041,Protocol.EDIT,0),sideDown,1041);
        check(target.releases(4)==1 && target.presses(4)==0,"new physical snapshot repairs a lost side-button release only");
        var heldExtras=packet(1050,next+3);
        event(heldExtras,next++,2,3,1,0,0,0);event(heldExtras,next++,2,4,1,0,0,0);event(heldExtras,next++,1,'R',1,0,0,0);
        extraInput.update(control(1050,Protocol.EDIT,24,'R'),heldExtras,1050);target.calls.clear();
        extraInput.update(control(1051,Protocol.EDIT|Protocol.NATIVE_UI,24,'R'),packet(1051,next),1051);
        check(target.releases(3)==1 && target.releases(4)==1 && !extraInput.held('R'),"native menu handoff releases both side keys and R");
        target.calls.clear();extraInput.update(control(1052,Protocol.EDIT,24,'R'),packet(1052,next),1052);
        check(extraInput.held('R') && target.calls.stream().noneMatch(call->call.equals("key:82:1:0")) &&
            target.presses(3)==0 && target.presses(4)==0,"resume restores R's level without manufacturing reloads or side actions");
        extraInput.release();target.screen=true;target.generation=1;
        extraInput.update(control(1060,Protocol.EDIT,0),packet(1060,next),1060);target.calls.clear();
        var guiSide=packet(1070,next+2);event(guiSide,next++,2,3,1,0,.4f,.6f);event(guiSide,next++,2,3,0,0,.4f,.6f);
        extraInput.update(control(1069,Protocol.EDIT,0),guiSide,1070);
        check(target.presses(3)==1 && target.releases(3)==1 && target.x==.4f && target.y==.6f,
            "GUI side clicks retain displayed generation and coordinates");
        extraInput.release();target.screen=false;extraInput.update(control(1080,Protocol.EDIT,0),packet(1080,next),1080);
        target.calls.clear();var invalidSide=packet(1090,next+2);
        event(invalidSide,next++,2,5,1,0,0,0);event(invalidSide,next++,2,-1,1,0,0,0);
        extraInput.update(control(1089,Protocol.EDIT,0),invalidSide,1090);
        check(target.calls.isEmpty(),"invalid side-button codes cannot alias another held button");
        var finalHold=packet(1100,next+2);event(finalHold,next++,2,3,1,0,0,0);event(finalHold,next++,2,4,1,0,0,0);
        extraInput.update(control(1100,Protocol.EDIT,24),finalHold,1100);target.calls.clear();extraInput.release();
        check(target.releases(3)==1 && target.releases(4)==1,"disconnect releases every owned side button");
        var resumeTarget=new Target();resumeTarget.screen=false;
        var resumeInput=new InputForwarder(resumeTarget);
        resumeInput.update(control(2000,0,0),packet(2000,0),2000);resumeTarget.calls.clear();
        var resumeReload=packet(2010,1);event(resumeReload,0,1,'R',1,0,0,0);
        resumeInput.update(control(2010,Protocol.EDIT,0,'R'),resumeReload,2010);
        check(resumeTarget.calls.contains("key:82:1:0"),"R pressed on the resume frame is not swallowed by held-level restoration");
        check(resumeInput.held('R') && Boolean.TRUE.equals(resumeTarget.levels.get(82)),"resume-frame reload keeps its legitimate held state");
        resumeInput.release();
        System.out.println("PASS "+checks+" production input replay checks");
    }
}
