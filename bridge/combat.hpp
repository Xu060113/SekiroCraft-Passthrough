#pragma once
#include "session.hpp"
#include <cstddef>
namespace bridge {
constexpr uint32_t combatCapability=1024;
constexpr size_t actorSlots=64, damageSlots=64;
struct alignas(8) ActorState {
    uint64_t id{}; sc::Vec3 position{}; int32_t hp{},maxHp{};
    uint32_t team{},flags{}; // 1 hostile, 2 NoDamage, 4 NoDeath, 8 NoPostureConsume
    int32_t posture{},maxPosture{},bossNode{}; // Engine remaining posture, not filled gauge.
};
struct alignas(8) CombatState {
    uint64_t sequence{},tick{},epoch{},hero{},ackCommand{};
    int32_t hp{},maxHp{}; uint32_t flags{},count{}; // flags: 1 valid player, 2 creative protected
    double ackDamage{},ackHeal{}; uint64_t ackSession{};
    int32_t posture{},maxPosture{};
    std::array<ActorState,actorSlots> actors{};
};
struct alignas(8) DamageCommand {
    uint64_t sequence{},actor{}; float amount{}; uint32_t reserved{};
};
struct alignas(8) CombatReport {
    uint64_t tick{},epoch{},hero{},session{};
    double damage{},heal{}; uint64_t command{};
    uint32_t invulnerable{},reserved{};
    std::array<DamageCommand,damageSlots> commands{};
};
static_assert(sizeof(ActorState)==48 && sizeof(CombatState)==3160 && sizeof(CombatReport)==1600);
static_assert(offsetof(CombatState,actors)==88 && offsetof(CombatReport,commands)==64);
inline bool validCombat(const CombatState &p) {
    if(!p.sequence || !p.epoch || p.count>actorSlots || p.flags&~7u || !std::isfinite(p.ackDamage) ||
       !std::isfinite(p.ackHeal) || p.ackDamage<0 || p.ackHeal<0)return false;
    if((p.flags&1) && (!p.hero || p.maxHp<=0 || p.maxHp>10000000 || p.hp<0 || p.hp>p.maxHp))return false;
    auto validPosture=[](int left,int maximum){return maximum>=0 && maximum<=10000000 && left>=0 && left<=maximum;};
    if(!validPosture(p.posture,p.maxPosture))return false;
    for(size_t i=0;i<p.count;++i){const auto &a=p.actors[i];
        if(!a.id || !sc::finite(a.position) || sc::length(a.position)>150000 ||
           a.maxHp<=0 || a.maxHp>10000000 || a.hp<0 || a.hp>a.maxHp || a.flags&~15u ||
           !validPosture(a.posture,a.maxPosture))return false;}
    return true;
}
inline bool validCombat(const CombatReport &p) {
    if(!p.tick || !p.epoch || !p.hero || !p.session || p.invulnerable>1 || p.reserved ||
       !std::isfinite(p.damage) || !std::isfinite(p.heal) || p.damage<0 || p.heal<0 ||
       p.damage>1000000 || p.heal>1000000)return false;
    for(auto i=p.command>damageSlots?p.command-damageSlots:0;i<p.command;++i){
        const auto &c=p.commands[i%damageSlots];
        if(c.sequence!=i+1 || !c.actor || !std::isfinite(c.amount) || c.amount<=0 || c.amount>10000 || c.reserved)return false;}
    return true;
}
// Damage is measured in vanilla MC health points (20 = one full native life).
inline int32_t damageHp(int32_t hp,int32_t maximum,double damage,double healing) {
    return int32_t(std::clamp(std::llround(double(hp)+(healing-damage)*maximum),0LL,int64_t(maximum)*1LL));
}
} // namespace bridge
