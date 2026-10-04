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
        var b=Protocol.direct(CombatProtocol.STATE_BYTES);
        b.putLong(0,1).putLong(8,1000).putLong(16,12).putLong(24,34).putInt(40,250).putInt(44,500).putInt(48,1)
            .putInt(52,1).putLong(80,56).putFloat(88,2).putFloat(92,3).putFloat(96,4)
            .putInt(100,100).putInt(104,200).putInt(108,6).putInt(112,1);
        var s=CombatProtocol.decode(b);
        check(s!=null && s.hp()==250 && s.hero()==34 && s.actors().get(0).maxHp()==200,"packed native offsets");
        b.putInt(52,65);check(CombatProtocol.decode(b)==null,"reject actor overflow");
        b.putInt(52,1).putFloat(88,Float.NaN);check(CombatProtocol.decode(b)==null,"reject invalid actor position");
        b.putFloat(88,2).putDouble(56,Double.NaN);check(CombatProtocol.decode(b)==null,"reject invalid health acknowledgement");
        System.out.println(checks+" combat protocol and health feedback checks passed");
    }
}
