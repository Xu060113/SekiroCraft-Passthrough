package dev.sekirobridge;
/** Cumulative deltas survive IPC loss; acknowledgements prevent feedback damage. */
final class HealthLedger {
    double damage,heal,last;boolean seeded;
    void reset(){damage=heal=last=0;seeded=false;}
    double synchronize(double observed,double nativeRatio,double ackDamage,double ackHeal,boolean immune,boolean alive){
        // A dead MC entity never observes the restored native health. Comparing
        // its zero HP to that predicted restoration would send a second death.
        if(!alive){if(seeded && !immune && nativeRatio>0)damage+=Math.max(0,last);seeded=false;last=0;return 0;}
        return synchronize(observed,nativeRatio,ackDamage,ackHeal,immune);
    }
    double synchronize(double observed,double nativeRatio,double ackDamage,double ackHeal,boolean immune){
        if(seeded && !immune){double difference=observed-last;
            if(difference< -1e-6)damage-=difference;else if(difference>1e-6)heal+=difference;}
        double result=Math.max(0,Math.min(1,nativeRatio-Math.max(0,damage-ackDamage)+Math.max(0,heal-ackHeal)));
        seeded=true;last=result;return result;
    }
}
