#pragma once
#include "sekirocraft/host.hpp"
#include "sekirocraft/actor_model.hpp"
#include "combat_trace.hpp"

namespace bridge {
// Sekiro 1.06 only. This backend constructs an owned normal-sword packet. It
// never keeps a live trace packet or attacker pointer. The separate phase profile
// is an opt-in candidate until MC-driven stage changes and rewards are accepted.
// Call only from the fingerprinted AttackManager update, after actor lookup.
class NativeHitBackend {
    uintptr_t base_{}; bool ready_{};
    mutable std::atomic<uint32_t> failure_{};
    struct alignas(16) Packet {
        std::array<uint8_t,0x240> bytes{};
        template<class T> void put(size_t offset,T value){
            std::memcpy(bytes.data()+offset,&value,sizeof(value));
        }
    };
    struct ParamRef {int32_t index{-1},reserved{},category{1},padding{};uintptr_t row{};};
    static_assert(sizeof(ParamRef)==24);
    using Initialize=uintptr_t(*)(void*);
    using ParamLookup=void(*)(ParamRef*,int32_t,int32_t);
    using Hit=void(*)(uintptr_t,uintptr_t,void*);
    template<size_t N>bool code(uintptr_t rva,const std::array<uint8_t,N>&expected)const {
        std::array<uint8_t,N> found{};return sc::readMemory(base_+rva,found)&&found==expected;
    }
    uintptr_t damageModule(uintptr_t chr,bool requireEntry=true)const {
        uintptr_t modules{},module{},owner{},vt{},entry{};
        if(!sc::readMemory(chr+0x1ff8,modules) || !sc::readMemory(modules+0x98,module) ||
           !sc::readMemory(module+8,owner) || owner!=chr || !sc::readMemory(module,vt) ||
           vt<base_ || vt>=base_+70066176 ||
           (requireEntry && (!sc::readMemory(vt+0x48,entry) || entry!=base_+0xb6a040)))return 0;
        return module;
    }
  public:
    bool initialize(uintptr_t base){base_=base;
        ready_=base && code(0x997890,std::array<uint8_t,24>{0x40,0x53,0x48,0x83,0xec,0x20,0x48,0x8b,0xd9,0xe8,0x52,0x04,0,0,0x0f,0x28,0x05,0xeb,0x13,0x43,0x02,0x33,0xc9,0x0f}) &&
            code(0x997cf0,std::array<uint8_t,24>{0x80,0xa1,0xed,0,0,0,0xfc,0x48,0x8d,0x91,0xb8,0,0,0,0x83,0xa1,0xf0,0,0,0,0xfe,0x33,0xc0,0x80}) &&
            code(0xb6a040,std::array<uint8_t,24>{0x40,0x55,0x53,0x56,0x57,0x41,0x55,0x41,0x56,0x41,0x57,0x48,0x8d,0xac,0x24,0xb0,0xfd,0xff,0xff,0x48,0x81,0xec,0x50,0x03}) &&
            code(0x10b5160,std::array<uint8_t,24>{0x40,0x57,0x48,0x83,0xec,0x40,0x48,0xc7,0x44,0x24,0x20,0xfe,0xff,0xff,0xff,0x48,0x89,0x5c,0x24,0x50,0x48,0x89,0x6c,0x24});
        return ready_;
    }
    bool ready()const{return ready_;}
    uint32_t lastFailure()const{return failure_.load(std::memory_order_relaxed);}
    bool phaseReady()const{
        return ready_ && code(0xb6e7c5,std::array<uint8_t,7>{0x41,0x83,0x7e,0x28,0x05,0x75,0x4a}) &&
            code(0xb6e800,std::array<uint8_t,6>{0x89,0x91,0x5c,0x02,0x00,0x00}) &&
            code(0x9f0410,std::array<uint8_t,11>{0x48,0x8b,0xc4,0x57,0x48,0x81,0xec,0xa0,0x00,0x00,0x00});
    }
    bool dispatch(uintptr_t attacker,uintptr_t target,const sc::Vec3 &from,const sc::Vec3 &to,
                  int32_t maxHp,int32_t maxPosture,const DamageCommand &command,bool finish=false,int healthPoints=20,
                  const sc::ActorModelBody *body=nullptr)const {
        auto amount=command.amount;
        if(finish && !phaseReady()){failure_=7;return false;}
        if(!ready_ || !attacker || attacker==target || !sc::finite(from) || !sc::finite(to) ||
           (body?!(command.flags&1) || !sc::modelHitInRange(*body,from,to,command.impact):sc::length(to-from)>64) ||
           maxHp<=0 || maxHp>10000000 || maxPosture<0 || maxPosture>10000000 ||
           !std::isfinite(amount) || amount<=0 || amount>10000){failure_=1;return false;}
        auto module=damageModule(target);
        if(!module){failure_=2;return false;}
        if(!damageModule(attacker,false)){failure_=3;return false;}
        uintptr_t params{};
        if(!sc::readMemory(base_+0x3d978b0,params) || !params){failure_=4;return false;}
        // The PC category and ordinary Kusabimaru parameter are present in the
        // live normal-hit samples. Resolve them anew; no cached PARAM row.
        ParamRef param;
        int32_t profile=finish?5000600:5000010;
        reinterpret_cast<ParamLookup>(base_+0x10b5160)(&param,1,profile);
        uint8_t floor{};
        if(!param.row || param.index!=profile || param.category!=1 ||
           !sc::readMemory(param.row+0x196,floor)){failure_=5;return false;}
        Packet packet;
        reinterpret_cast<Initialize>(base_+0x997890)(&packet);
        // Explicit scalar fields of the observed ordinary sword profile. Leave
        // status-effect lists, ownership and remaining flags at native defaults.
        auto divisor=std::clamp(healthPoints,20,10000);
        auto health=float(std::clamp(double(maxHp)*amount/divisor,1.,double(maxHp)));
        auto posture=float(std::clamp(double(maxPosture)*amount/divisor,0.,double(maxPosture)));
        packet.put(0x00,health);packet.put(0x20,11.f);
        // Native sample: first grounded Kusabimaru swing (5000010). The old
        // 5000061 weak-hit identity and reaction strengths were not this profile.
        packet.put(0x24,int32_t(2));
        for(auto at:{0x28u,0x2cu,0x30u})packet.put(at,int32_t(1));
        packet.put(0x34,15.f);packet.put(0x38,30.f);packet.put(0x3c,30.f);packet.put(0x40,int32_t(30));
        packet.put(0x4c,int32_t(105000010));packet.put(0x50,int32_t(5000010));packet.put(0x54,int32_t(1));
        packet.put(0x5c,10000.f);packet.put(0x60,.4f);packet.put(0x70,1.f);packet.put(0x7c,uint32_t(0x10001));
        for(auto at:{0x8cu,0x90u,0x94u,0x98u,0x9cu})packet.put(at,int32_t(0));
        packet.put(0xd0,int32_t(120));packet.put(0xd4,int32_t(100));packet.put(0xd8,uint32_t(0x10000));
        packet.put(0xdc,1.f);packet.put(0xe0,posture);packet.put(0xe4,int32_t(1));
        packet.put(0xf4,uint8_t(5));packet.put(0xf8,int32_t(2110));packet.put(0x114,0.f);
        packet.put(0x118,0.f);packet.put(0x11c,int32_t(5000));
        if(finish){
            // Scalar identity of natural Boss sample 67. Initialize a new packet
            // for every invocation; position/owner and all native pointers are fresh.
            packet.put(0,24000.f);packet.put(0x20,0.f);packet.put(0x24,int32_t(0));
            packet.put(0x28,int32_t(5));packet.put(0x2c,int32_t(5));packet.put(0x30,int32_t(1));
            packet.put(0x40,int32_t(90));packet.put(0x4c,int32_t(105000600));packet.put(0x50,profile);
            packet.put(0x7c,uint32_t(1));packet.put(0xd0,int32_t(-1));packet.put(0xd4,int32_t(-1));
            packet.put(0xd8,uint32_t(0x10200));packet.put(0xe0,0.f);
        }
        auto impact=(command.flags&1)?command.impact:to+sc::Vec3{0,1,0};
        if(!sc::finite(impact) || (!body && sc::length(impact-to)>12)){failure_=6;return false;}
        const std::array<float,4> position{impact.x,impact.y,impact.z,1.f};
        auto direction=(command.flags&1)?command.direction:sc::normalize(to-from);
        const std::array<float,4> normal{direction.x,direction.y,direction.z,0.f};
        packet.put(0x130,position);packet.put(0x140,normal);packet.put(0x150,normal);
        // The native collision caller initializes the two secondary reaction
        // selectors to -1 (99ac29/99ac30). Zero is a real selector, not "unset".
        // Keep flags/pointers/padding at owned defaults; never replay trace bytes.
        packet.put(0x1da,int16_t(-1));packet.put(0x1de,int16_t(-1));
        packet.put(0x190,attacker);packet.put(0x198,target);
        BridgeVitalWrite write;
        reinterpret_cast<Hit>(base_+0xb6a040)(module,attacker,&packet);
        failure_=0;
        return true; // Dispatched once; blocking/immunity outcomes belong to the engine.
    }
};
} // namespace bridge
