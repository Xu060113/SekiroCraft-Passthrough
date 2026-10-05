package dev.sekirobridge;
public final class CombatSelfTest {
    static int checks;
    static void check(boolean ok,String message){++checks;if(!ok)throw new AssertionError(message);}
    public static void main(String[] args){
        var l=new HealthLedger();
        check(l.synchronize(1,.8,0,0,false)==.8,"native health seeds, no initial difference counted");
        check(l.damage==0 && l.heal==0,"seed sends no injury");
        check(Math.abs(l.synchronize(.7,.8,0,0,false)-.7)<1e-6,"MC injury remains visible before acknowledgement");
        double d=l.damage;
        check(Math.abs(d-.1)<1e-6,"record normalized injury once");
        for(int i=0;i<5;++i)l.synchronize(.7,.8,0,0,false);
        check(l.damage==d,"repeated unacknowledged snapshots do not repeat injury");
        check(Math.abs(l.synchronize(.7,.7,d,0,false)-.7)<1e-6,"acknowledgement removes prediction without feedback");
        check(Math.abs(l.synchronize(.7,.5,d,0,false)-.5)<1e-6,"native enemy damage reaches MC");
        check(l.damage==d,"native damage is not bounced back");
        check(Math.abs(l.synchronize(.6,.5,d,0,false)-.6)<1e-6,"MC food/regeneration produces healing");
        double h=l.heal;l.synchronize(.6,.6,d,h,false);
        check(l.heal==h,"native acknowledgement does not repeat healing");
        l.synchronize(0,.6,d,h,true);
        check(l.damage==d,"creative health loss cannot damage native player");
        l.reset();check(!l.seeded && l.damage==0 && l.heal==0,"new hero resets ledger");
        l.synchronize(1,.6,0,0,false,true);
        check(l.synchronize(0,0,0,0,false,false)==0 && l.damage==0,"native death freezes the dead MC entity");
        for(int i=0;i<20;++i)l.synchronize(0,.5,0,0,false,false);
        check(l.damage==0,"restored native HP cannot bounce off the still dead MC entity");
        l.reset();check(l.synchronize(1,.5,0,0,false,true)==.5 && l.damage==0,"new life seeds after native resurrection");
        l.reset();l.synchronize(1,1,0,0,false,true);l.synchronize(0,1,0,0,false,false);
        check(l.damage==1,"a real MC death reports one final native injury");
        l.synchronize(0,1,0,0,false,false);check(l.damage==1,"dead snapshots never repeat final injury");
        var hurt=new NativeHurtFeedback();
        check(!hurt.update(1,0,0,false),"first native life snapshot has no hurt event");
        check(hurt.update(.8,0,0,false),"native HP injury emits feedback");
        check(!hurt.update(.8,0,0,false),"duplicate native state never replays feedback");
        check(!hurt.update(.6,.2,0,false),"MC injury acknowledgement never duplicates its vanilla feedback");
        check(hurt.update(.3,.4,0,false),"mixed MC acknowledgement and native injury retains native feedback");
        check(!hurt.update(.4,.4,.1,false),"healing has no hurt feedback");
        check(!hurt.update(.3,.4,.1,true),"creative receives no native hurt feedback");
        hurt.reset();check(!hurt.update(.1,0,0,false),"replacement hero does not inherit injury history");
        var b=Protocol.direct(CombatProtocol.STATE_BYTES);
        b.putLong(0,1).putLong(8,1000).putLong(16,12).putLong(24,34).putInt(40,250).putInt(44,500).putInt(48,1)
            .putInt(52,1).putLong(88,56).putFloat(96,2).putFloat(100,3).putFloat(104,4)
            .putInt(108,100).putInt(112,200).putInt(116,6).putInt(120,5)
            .putInt(80,80).putInt(84,100).putInt(124,0).putInt(128,200).putInt(132,2).putLong(136,1);
        var s=CombatProtocol.decode(b);
        check(s!=null && s.hp()==250 && s.hero()==34 && s.actors().get(0).maxHp()==200,"packed native offsets");
        check(s.posture()==80 && s.maxPosture()==100 && s.actors().get(0).bossNode()==2,"native remaining posture and boss node offsets");
        check(Math.abs(CombatProtocol.postureRatio(80,100)-.2)<.0001,"native remaining counter becomes filled posture gauge");
        check(CombatProtocol.postureRatio(0,100)==1 && CombatProtocol.postureRatio(100,100)==0,"broken and recovered posture direction");
        b.putInt(124,201);check(CombatProtocol.decode(b)==null,"reject posture outside native maximum");b.putInt(124,0);
        b.putInt(52,65);check(CombatProtocol.decode(b)==null,"reject actor overflow");
        b.putInt(52,1).putFloat(96,Float.NaN);check(CombatProtocol.decode(b)==null,"reject invalid actor position");
        b.putFloat(96,2).putDouble(56,Double.NaN);check(CombatProtocol.decode(b)==null,"reject invalid health acknowledgement");
        System.out.println(checks+" combat protocol and health feedback checks passed");
    }
}
