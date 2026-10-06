#pragma once
#include "session.hpp"
namespace bridge {
constexpr uint32_t nativeDefenseCapability=2048;
constexpr size_t injurySlots=64;
struct NativeInjury {uint64_t sequence{};float ratio{};uint32_t flags{};sc::Vec3 source{};uint32_t reserved{};};
struct NativeInjuries {uint64_t tick{},epoch{},hero{},session{},produced{};std::array<NativeInjury,injurySlots> hits{};};
struct NativeInjuryAck {uint64_t tick{},epoch{},hero{},session{},processed{};};
static_assert(sizeof(NativeInjury)==32 && sizeof(NativeInjuries)==2088 && sizeof(NativeInjuryAck)==40);
// Sekiro's collision builder stores normalize(impact - attack center) at
// packet+150. When a projectile has no live ChrIns, recover only its incoming
// side, never substitute the player's look direction or an arbitrary NPC.
inline bool injuryOriginFromTrajectory(const sc::Vec3 &defender,const sc::Vec3 &impact,
                                       const sc::Vec3 &travel,sc::Vec3 &origin){
    if(!sc::finite(defender) || !sc::finite(impact) || !sc::finite(travel) ||
       sc::length(impact-defender)>12)return false;
    float magnitude=sc::length(travel);
    if(magnitude<.5f || magnitude>1.5f || travel.x*travel.x+travel.z*travel.z<.0001f)return false;
    origin=defender-travel*(2.f/magnitude);
    return sc::finite(origin) && sc::length(origin)<=150000;
}
inline bool validInjuries(const NativeInjuries &s){
    if(!s.tick || !s.epoch || !s.hero || !s.session)return false;
    for(uint64_t i=s.produced>injurySlots?s.produced-injurySlots:0;i<s.produced;++i){const auto &h=s.hits[i%injurySlots];
        if(h.sequence!=i+1 || !std::isfinite(h.ratio) || h.ratio<=0 || h.ratio>10000 || h.flags&~1u ||
           h.reserved || !sc::finite(h.source) || sc::length(h.source)>150000)return false;}
    return true;
}
// Unacknowledged hits are never overwritten; full queues fall back to native damage.
class NativeInjuryQueue {
    NativeInjuries state_{};uint64_t acknowledged_{};
  public:
    void reset(uint64_t epoch=0,uint64_t hero=0,uint64_t session=0){state_={};state_.epoch=epoch;state_.hero=hero;state_.session=session;acknowledged_=0;}
    bool matches(uint64_t epoch,uint64_t hero,uint64_t session)const{return state_.epoch==epoch && state_.hero==hero && state_.session==session;}
    bool acknowledge(const NativeInjuryAck &ack,uint64_t now){
        if(!fresh(now,ack.tick) || !matches(ack.epoch,ack.hero,ack.session) || ack.processed<acknowledged_ || ack.processed>state_.produced)return false;
        acknowledged_=ack.processed;return true;
    }
    bool push(float ratio,const sc::Vec3 &source,bool known){
        if(!state_.session || state_.produced-acknowledged_>=injurySlots || !std::isfinite(ratio) || ratio<=0 || ratio>10000 ||
           !sc::finite(source) || sc::length(source)>150000)return false;
        auto &hit=state_.hits[state_.produced%injurySlots];hit={++state_.produced,ratio,known?1u:0u,source,0};return true;
    }
    NativeInjuries snapshot(uint64_t now)const{auto copy=state_;copy.tick=now;return copy;}
};
}
