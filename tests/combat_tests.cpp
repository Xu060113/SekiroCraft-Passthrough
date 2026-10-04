#include "../bridge/combat.hpp"
#include "../src/native_combat.hpp"
#include <iostream>
#include <stdexcept>
int main(){int n{};auto check=[&](bool b,const char*s){++n;if(!b)throw std::runtime_error(s);};
    using namespace bridge;
    CombatState state;state.sequence=1;state.epoch=7;state.hero=2;state.flags=1;state.hp=250;state.maxHp=500;
    check(validCombat(state),"valid native life");state.hp=501;check(!validCombat(state),"invalid HP");state.hp=250;
    state.count=65;check(!validCombat(state),"actor overflow");state.count=1;auto &a=state.actors[0];a.id=9;a.hp=100;a.maxHp=200;
    check(validCombat(state),"actor");a.position.x=NAN;check(!validCombat(state),"invalid actor");a.position.x=0;
    CombatReport p;p.tick=100;p.epoch=7;p.hero=2;p.session=3;
    check(validCombat(p),"empty report");p.command=1;p.commands[0]={1,9,6,0};check(validCombat(p),"valid hit");
    p.commands[0].sequence=2;check(!validCombat(p),"ring sequence mismatch");p.commands[0].sequence=1;
    p.commands[0].amount=NAN;check(!validCombat(p),"nonfinite injury");p.commands[0].amount=6;
    p.damage=-1;check(!validCombat(p),"negative cumulative injury");p.damage=0;
    for(size_t i=0;i<damageSlots;++i)p.commands[i]={i+1,9,6,0};p.command=64;
    check(validCombat(p),"full report");p.command=65;p.commands[0]={65,9,1,0};check(validCombat(p),"wrapped ring");
    check(damageHp(500,1000,.1,0)==400,"MC damage ratio");
    check(damageHp(400,1000,0,.1)==500,"MC healing ratio");
    check(damageHp(10,1000,1,0)==0,"death clamped");check(damageHp(900,1000,0,1)==1000,"heal clamped");
    // Actual production adapter with a bounded fake image and entity chains.
    std::vector<uint8_t> image(0x3d7a200),root(0x100),chr(0x2100),modules(0x80),data(0x240),physics(0x100);
    auto base=reinterpret_cast<uintptr_t>(image.data());auto ptr=[](auto &v){return reinterpret_cast<uintptr_t>(v.data());};
    auto put=[](auto &v,size_t at,auto x){std::memcpy(v.data()+at,&x,sizeof(x));};
    const uint8_t setter[]{0x48,0x89,0x5c,0x24,0x18,0x89,0x54,0x24,0x10,0x57,0x48,0x83,0xec,0x20,0x8b,0xb9};
    const uint8_t lookup[]{0x48,0x83,0xec,0x28,0xe8,0x37,0xff,0xff,0xff,0x48,0x85,0xc0,0x74,0x08,0x48,0x8b};
    std::memcpy(image.data()+0xbd64e0,setter,16);std::memcpy(image.data()+0xa4a050,lookup,16);
    put(image,0x3d7a1e0,ptr(root));put(root,0x88,ptr(chr));put(chr,0,base+0x1000);put(chr,8,uint32_t(123));
    put(chr,0x1ff8,ptr(modules));put(modules,0x18,ptr(data));put(modules,0x68,ptr(physics));
    put(physics,8,ptr(chr));put(physics,0x80,sc::Vec3{1,2,3});put(data,0x130,int32_t(500));put(data,0x134,int32_t(1000));
    SharedMemory memory;check(memory.open(L"combat-test-"+std::to_wstring(GetCurrentProcessId())),"test channels");
    NativeCombatAdapter adapter;adapter.initialize(base,memory);check(adapter.ready(),"production signatures");adapter.tick(7,true);
    check(memory.combatState.read(state) && validCombat(state) && state.hp==500,"production reads current hero");
    p={};p.tick=GetTickCount64();p.epoch=7;p.hero=state.hero;p.session=5;p.invulnerable=1;
    memory.combatReport.write(p);adapter.tick(7,true);check(data[0x228]==8,"creative acquires only player NoDamage");
    data[0x228]|=16;adapter.releaseIfInactive(false);check(data[0x228]==16,"release preserves unrelated bits");
    data[0x228]=8;p.tick=GetTickCount64();memory.combatReport.write(p);adapter.tick(7,true);adapter.releaseIfInactive(false);
    check(data[0x228]==8,"existing NoDamage preserved");
    data[0x228]=0;memory.combatReport.write(p);adapter.tick(7,true);put(root,0x88,uintptr_t(0));adapter.releaseIfInactive(false);
    check(data[0x228]==8,"does not restore a potentially freed former hero");
    image[0xbd64e0]=0;NativeCombatAdapter disabled;disabled.initialize(base,memory);check(!disabled.ready(),"unknown code disables writes");
    std::cout<<n<<" combat layout, validation and owned player immunity checks passed\n";
}
