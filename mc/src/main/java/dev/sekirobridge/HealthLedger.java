package dev.sekirobridge;
/** Cumulative deltas survive IPC loss; acknowledgements prevent feedback damage. */
final class HealthLedger {
    double damage,heal,last;boolean seeded;
    void reset(){damage=heal=last=0;seeded=false;}
    double synchronize(double observed,double nativeRatio,double ackDamage,double ackHeal,boolean immune){
        if(seeded && !immune){double difference=observed-last;
            if(difference< -1e-6)damage-=difference;else if(difference>1e-6)heal+=difference;}
        double result=Math.max(0,Math.min(1,nativeRatio-Math.max(0,damage-ackDamage)+Math.max(0,heal-ackHeal)));
        seeded=true;last=result;return result;
    }
}
