package dev.sekirobridge;

public final class NativeLoadSelfTest {
    private static int checks;
    private static void check(boolean ok,String reason){++checks;if(!ok)throw new AssertionError(reason);}
    private static Protocol.State pose(long sequence,long tick,long epoch,int flags,float y){
        return new Protocol.State(sequence,tick,epoch,flags,256,0,y,0,0,y+1.6f,0,0,0,1,
            1,1.7f,.1f,1000,128,1,1280,720,new byte[32],0,0,0,0,0,0,new int[8],0);
    }
    public static void main(String[] args){
        var gate=new NativeLoadGate();
        var playable=pose(1,100,7,Protocol.SCENE,0);
        gate.update(playable,100,true,true);
        check(!gate.holding() && gate.revision()==0,"initial usable scene is immediate");
        gate.update(pose(2,120,7,0,-900),120,true,false);
        check(gate.holding(),"loss of scene suspends an owned player");
        long before=gate.revision();
        for(long t=170;t<10000;t+=50)gate.update(null,t,true,false);
        check(gate.holding() && gate.revision()==before,"long load cannot expire into MC void simulation");
        gate.update(pose(3,10000,7,Protocol.SCENE,-900),10000,true,true);
        gate.update(pose(4,10050,7,Protocol.SCENE,10),10050,true,true);
        gate.update(pose(5,10100,7,Protocol.SCENE,10),10100,true,true);
        check(gate.holding(),"transient unloading pose cannot be used as a respawn point");
        gate.update(pose(6,10200,7,Protocol.SCENE,10),10200,true,true);
        check(!gate.holding() && gate.revision()==before+1,"stable new pose requests exactly one client/server reseed");
        gate.update(pose(7,10250,7,Protocol.SCENE,10),10250,true,true);
        check(gate.revision()==before+1,"ordinary ticks cannot repeatedly snap movement to native pose");
        gate.update(pose(8,10300,7,Protocol.SCENE,10),10700,true,false);
        check(gate.holding(),"expired native heartbeat is held even with a cached Scene flag");
        var same=pose(9,11000,7,Protocol.SCENE,12);
        for(int i=0;i<100;i++)gate.update(same,11000+i*2,true,true);
        check(gate.holding(),"one repeated snapshot cannot prove that the native scene has resumed");
        gate.update(pose(10,11300,8,Protocol.SCENE,12),11300,true,true);
        gate.update(pose(11,11400,8,Protocol.SCENE,12),11400,true,true);
        check(gate.holding(),"new epoch restarts stability sampling");
        gate.update(pose(12,11500,8,Protocol.SCENE,12),11500,true,true);
        check(!gate.holding() && gate.revision()==2,"map/session replacement also reseeds after stable samples");
        gate.update(null,12000,true,false);
        gate.update(pose(13,12100,8,Protocol.SCENE|Protocol.NATIVE_CINEMATIC,12),12100,true,true);
        gate.update(pose(14,13000,8,Protocol.SCENE|Protocol.NATIVE_CINEMATIC,12),13000,true,true);
        check(gate.holding(),"a movie frame is not a playable map resume");
        gate.update(pose(15,13100,8,Protocol.SCENE,12),13100,true,true);
        gate.update(pose(16,13200,8,Protocol.SCENE,12),13200,true,true);
        gate.update(pose(17,13300,8,Protocol.SCENE,12),13300,true,true);
        check(!gate.holding() && gate.revision()==3,"movie completion can release a preceding map load");
        gate.update(null,14000,true,false);gate.update(null,14100,false,false);
        check(!gate.holding(),"explicit off or leaving the MC world releases the hold");
        gate.update(null,15000,false,false);
        check(!gate.holding(),"ordinary MC without a previously acquired native player is never held");
        gate.update(playable,16000,true,true);
        check(!gate.holding(),"rearming does not inherit an old loading hold");
        var clientWorld=new Object();var serverWorld=new Object();var player=java.util.UUID.randomUUID();
        var scope=new NativeVoidPolicy.Scope(7,player,clientWorld,serverWorld);
        check(scope.owns(true,7,serverWorld,player,true,false),"native ownership protects during absent Scene frames");
        check(!scope.owns(false,7,serverWorld,player,true,false),"explicit off restores MC void rules");
        check(!scope.owns(true,7,new Object(),player,true,false),"load protection cannot leak into a different world");
        check(!scope.owns(true,7,serverWorld,java.util.UUID.randomUUID(),true,false),"load protection cannot leak into another player");
        System.out.println(checks+" native map-load lifecycle checks passed (no game launched)");
    }
}
