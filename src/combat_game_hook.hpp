#pragma once
#include "native_combat.hpp"
#include "MinHook.h"
extern "C" {
void scCombatGameEntry();
extern void *scCombatGameContinue;
extern void (*scCombatGameCallback)(uintptr_t,float) noexcept;
}
namespace bridge {
class CombatGameHook {
    inline static NativeCombatAdapter *adapter_{};
    static void callback(uintptr_t manager,float dt)noexcept {
        if(adapter_)adapter_->gameTick(manager,dt);
    }
  public:
    bool install(uintptr_t base,NativeCombatAdapter &adapter){
        if(adapter_ || !adapter.prepareNativeHits())return false;
        const std::array<uint8_t,24> expected{0x40,0x56,0x57,0x41,0x56,0x48,0x83,0xec,0x40,0x48,0xc7,0x44,0x24,0x20,0xfe,0xff,0xff,0xff,0x48,0x89,0x5c,0x24,0x68,0x48};
        std::array<uint8_t,24> actual{};
        if(!sc::readMemory(base+0x9a0dc0,actual) || actual!=expected)return false;
        auto target=reinterpret_cast<void*>(base+0x9a0dc0);
        if(MH_CreateHook(target,reinterpret_cast<void*>(scCombatGameEntry),&scCombatGameContinue)!=MH_OK)return false;
        adapter_=&adapter;scCombatGameCallback=callback;adapter.enableNativeHits(true);
        if(MH_EnableHook(target)!=MH_OK){adapter.enableNativeHits(false);adapter_=nullptr;MH_RemoveHook(target);return false;}
        return true;
    }
};
}
