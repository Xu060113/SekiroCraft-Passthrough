#pragma once
#include "session.hpp"
namespace bridge {
struct alignas(8) NativeActionRequest {
    uint64_t tick{},epoch{},sequence{};
    uint32_t kind{},reserved{}; // 1: native attack/resurrection button
};
static_assert(sizeof(NativeActionRequest)==32);
inline bool validAction(const NativeActionRequest &p){return p.tick && p.epoch && p.sequence && p.kind==1 && !p.reserved;}
struct NativeAnimation {
    uintptr_t hero{},module{};int32_t id{};bool valid{};
};
// A request is confirmed by the current animation leaving its pre-action state.
// MC ownership can leave animation 0 as the baseline. Native actions may instead
// return to a grounded idle, so completion must not require that exact baseline.
inline bool nativeGroundIdle(int32_t id){return id==100321 || id==790010;}
class ActionHandoff {
    uint64_t started_{},sampleAt_{};NativeAnimation baseline_{};bool confirmed_{};unsigned idleSamples_{};
  public:
    void reset(){started_=sampleAt_=0;baseline_={};confirmed_=false;idleSamples_=0;}
    bool active()const{return started_!=0;}
    bool confirmed()const{return confirmed_;}
    bool begin(uint64_t now,NativeAnimation animation){
        if(active() || !animation.valid)return false;
        started_=sampleAt_=now;baseline_=animation;confirmed_=false;idleSamples_=0;return true;
    }
    bool update(uint64_t now,NativeAnimation animation,bool allowed,bool paused=false){
        if(!allowed || !animation.valid || animation.hero!=baseline_.hero || animation.module!=baseline_.module){reset();return false;}
        if(!started_)return false;
        if(now<sampleAt_){reset();return false;}
        if(paused){started_+=now-sampleAt_;sampleAt_=now;return true;}
        sampleAt_=now;
        bool idle=animation.id==baseline_.id || nativeGroundIdle(animation.id);
        if(!idle){confirmed_=true;idleSamples_=0;}
        else if(confirmed_ && ++idleSamples_>=2){reset();return false;}
        if(now<started_ || now-started_>20000 || (!confirmed_ && now-started_>=500)){reset();return false;}
        return true;
    }
};
}
