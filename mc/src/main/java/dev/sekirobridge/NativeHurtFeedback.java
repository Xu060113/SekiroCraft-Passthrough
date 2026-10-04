package dev.sekirobridge;
/** Separate visual feedback from HP reconciliation, excluding acknowledged MC damage. */
final class NativeHurtFeedback {
    private boolean seeded;
    private double life,damage,heal;
    boolean update(double nextLife,double nextDamage,double nextHeal,boolean immune){
        boolean hurt=seeded && !immune && nextDamage>=damage && nextHeal>=heal &&
            life-nextLife-(nextDamage-damage)+(nextHeal-heal)>1e-5;
        seeded=true;life=nextLife;damage=nextDamage;heal=nextHeal;return hurt;
    }
    void reset(){seeded=false;life=damage=heal=0;}
}
