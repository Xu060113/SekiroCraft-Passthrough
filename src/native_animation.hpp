#pragma once
#include "sekirocraft/host.hpp"
#include "../bridge/native_action.hpp"
namespace bridge {
// Read-only chain corroborated by Sekiro Practice CT. The runtime owner's
// identity and image range are checked on every sample; no animation is written.
inline NativeAnimation readNativeAnimation(uintptr_t base){
    uintptr_t root{},hero{},modules{},module{},owner{},vt{};int32_t id{};
    if(!sc::readMemory(base+0x3d7a1e0,root) || !sc::readMemory(root+0x88,hero) ||
       !sc::readMemory(hero+0x1ff8,modules) || !sc::readMemory(modules+0x10,module))return {};
    bool valid=sc::readMemory(module,vt) && vt>=base && vt<base+70066176 &&
       sc::readMemory(module+8,owner) && owner==hero && sc::readMemory(module+0x20,id) && id>=0 && id<=999999999;
    return {hero,module,id,valid};
}
}
