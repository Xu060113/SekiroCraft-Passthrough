#pragma once
#include "session.hpp"
namespace bridge {
struct alignas(8) NativeActionRequest {
    uint64_t tick{},epoch{},sequence{};
    uint32_t kind{},reserved{}; // 1: native attack/resurrection button
};
static_assert(sizeof(NativeActionRequest)==32);
inline bool validAction(const NativeActionRequest &p){return p.tick && p.epoch && p.sequence && p.kind==1 && !p.reserved;}
// Keep root motion native until the user releases the action key and the native
// position settles. A safety deadline handles a lost key-up or interrupted scene.
class ActionHandoff {
    uint64_t started_{},moved_{},minimum_=2500;sc::Vec3 last_{};
  public:
    void reset(){started_=moved_=0;last_={};}
    void begin(uint64_t now,sc::Vec3 p,uint64_t minimum=2500){started_=moved_=now;last_=p;minimum_=minimum;}
    bool update(uint64_t now,sc::Vec3 p,bool held,bool allowed){
        if(!allowed || !sc::finite(p)){reset();return false;}
        if(!started_)return false;
        if(sc::length(p-last_)>.005f){moved_=now;last_=p;}
        if(now-started_>20000 || (!held && now-started_>=minimum_ && now-moved_>=800)){reset();return false;}
        return true;
    }
};
}
