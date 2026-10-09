package dev.sekirobridge;

/** Loading is a temporary loss of native pose, not the end of player ownership. */
final class NativeLoadGate {
    private volatile boolean holding;
    private volatile long revision;
    private long sequence,epoch,stableAt;
    private int samples;
    private float x,y,z;
    boolean holding(){return holding;}
    long revision(){return revision;}
    void reset(){holding=false;sequence=epoch=stableAt=0;samples=0;}
    void update(Protocol.State pose,long now,boolean owned,boolean ready){
        if(!owned){reset();return;}
        if(!ready){holding=true;samples=0;stableAt=0;return;}
        if(!holding)return;
        // Movies have their own pose handoff. Do not resume a loading hold in one.
        if((pose.flags()&Protocol.NATIVE_CINEMATIC)!=0){samples=0;stableAt=0;return;}
        if(pose.sequence()==sequence && pose.epoch()==epoch)return;
        double dx=pose.px()-x,dy=pose.py()-y,dz=pose.pz()-z;
        boolean stable=samples>0 && pose.epoch()==epoch && dx*dx+dy*dy+dz*dz<=.25;
        if(!stable){samples=0;stableAt=now;}
        sequence=pose.sequence();epoch=pose.epoch();x=pose.px();y=pose.py();z=pose.pz();++samples;
        if(samples>=3 && now>=stableAt && now-stableAt>=150){++revision;holding=false;}
    }
}
