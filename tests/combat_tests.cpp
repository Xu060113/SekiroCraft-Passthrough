#include "../bridge/combat.hpp"
#include "../src/native_combat.hpp"
#include <iostream>
#include <stdexcept>
static uintptr_t fixtureActor{},fixtureData{};
static int fixturePostureCalls{};
static uintptr_t fixtureLookup(uintptr_t,uint32_t handle){return handle==456?fixtureActor:0;}
static void fixtureHp(uintptr_t data,int hp){
    if(*reinterpret_cast<uint8_t*>(data+0x228)&4)hp=std::max(1,hp);
    *reinterpret_cast<int*>(data+0x130)=hp;
}
static void fixturePosture(uintptr_t data,int left,uint8_t recovery){
    if(data!=fixtureData || recovery)throw std::runtime_error("posture setter ABI");
    ++fixturePostureCalls;*reinterpret_cast<int*>(data+0x148)=left;
}
int main(){int n{};auto check=[&](bool b,const char*s){++n;if(!b)throw std::runtime_error(s);};
    using namespace bridge;
    CombatState state;state.sequence=1;state.epoch=7;state.hero=2;state.flags=1;state.hp=250;state.maxHp=500;
    check(validCombat(state),"valid native life");state.hp=501;check(!validCombat(state),"invalid HP");state.hp=250;
    state.posture=101;state.maxPosture=100;check(!validCombat(state),"posture above maximum rejected");state.posture=80;
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
    std::memcpy(image.data()+0xbd64e0,setter,16);
    const uint8_t postureCode[]{0x48,0x89,0x6c,0x24,0x18,0x48,0x89,0x74,0x24,0x20,0x57,0x48,0x83,0xec,0x20,0x41};
    const uint8_t postureStore[]{0x89,0x87,0x48,0x01,0x00,0x00,0x85,0xdb};
    std::memcpy(image.data()+0xbd6710,postureCode,16);std::memcpy(image.data()+0xbd679a,postureStore,8);
    std::vector<uint8_t> npc(0x2100),npcModules(0x80),npcData(0x280),npcPhysics(0x100),list(0x20);
    put(root,0x88,ptr(chr));put(root,0x10,ptr(list));put(list,0x18,int(1));
    put(npc,0,base+0x1000);put(npc,8,uint32_t(456));put(npc,0x1ff8,ptr(npcModules));put(npc,0x74,uint8_t(6));
    put(npcModules,0x18,ptr(npcData));put(npcModules,0x68,ptr(npcPhysics));put(npcPhysics,8,ptr(npc));
    put(npcPhysics,0x80,sc::Vec3{2,2,3});put(npcData,0x130,int(1000));put(npcData,0x134,int(1000));
    put(npcData,0x148,int(200));put(npcData,0x14c,int(200));put(npcData,0x25c,int(2));npcData[0x228]=4;
    NativeCombatAdapter combat;combat.initialize(base,memory);check(combat.postureReady(),"validated production posture entry and store");
    DWORD hpOld{},lookupOld{};
    check(VirtualProtect(image.data()+0xbd6000,4096,PAGE_EXECUTE_READWRITE,&hpOld) &&
          VirtualProtect(image.data()+0xa4a000,4096,PAGE_EXECUTE_READWRITE,&lookupOld),"executable native setter fixture");
    auto stub=[&](size_t at,uintptr_t function){uint8_t jump[]{0x48,0xb8,0,0,0,0,0,0,0,0,0xff,0xe0};
        std::memcpy(jump+2,&function,8);std::memcpy(image.data()+at,jump,12);};
    fixtureActor=ptr(npc);fixtureData=ptr(npcData);
    stub(0xa4a050,reinterpret_cast<uintptr_t>(fixtureLookup));stub(0xbd64e0,reinterpret_cast<uintptr_t>(fixtureHp));
    stub(0xbd6710,reinterpret_cast<uintptr_t>(fixturePosture));
    combat.tick(8,true);combat.observe(ptr(npcPhysics));Sleep(80);combat.tick(8,true);
    check(memory.combatState.read(state) && state.count==1 && state.actors[0].posture==200 &&
          state.actors[0].bossNode==2 && (state.actors[0].flags&4),"boss counters and native NoDeath exported");
    p={};p.tick=GetTickCount64();p.epoch=8;p.hero=state.hero;p.session=9;p.command=1;
    p.commands[0]={1,state.actors[0].id,40,0};memory.combatReport.write(p);combat.tick(8,true);
    check(*reinterpret_cast<int*>(npcData.data()+0x130)==1 && *reinterpret_cast<int*>(npcData.data()+0x148)==0 &&
          fixturePostureCalls==1,"production dispatch uses HP and posture setters, preserves boss NoDeath");
    combat.tick(8,true);check(fixturePostureCalls==1,"acknowledged command never replays posture damage");
    check(*reinterpret_cast<int*>(npcData.data()+0x25c)==2,"combat never edits boss phase/node counter");
    put(npcData,0x148,int(-20));Sleep(80);combat.tick(8,true);memory.combatState.read(state);
    check(state.actors[0].posture==0 && state.actors[0].maxPosture==200 && state.actors[0].bossNode==2,
        "native negative posture publishes a full gauge and retains boss node");
    p.tick=GetTickCount64();p.command=2;p.commands[1]={2,state.actors[0].id,1,0};
    memory.combatReport.write(p);combat.tick(8,true);
    check(fixturePostureCalls==1 && *reinterpret_cast<int*>(npcData.data()+0x148)==-20,
        "bridge damage cannot recover an already broken posture remainder");
    put(npcData,0x148,int(200));
    npcData[0x228]|=16;p.tick=GetTickCount64();p.command=3;p.commands[2]={3,state.actors[0].id,1,0};
    memory.combatReport.write(p);combat.tick(8,true);check(fixturePostureCalls==1,"NoPostureConsume is respected");
    npcData[0x228]=0;put(npcData,0x130,int(1000));
    p.tick=GetTickCount64();p.command=4;p.commands[3]={4,state.actors[0].id,40,0};
    memory.combatReport.write(p);combat.tick(8,true);
    check(*reinterpret_cast<int*>(npcData.data()+0x130)==1 && *reinterpret_cast<int*>(npcData.data()+0x25c)==2,
        "compatibility damage preserves a Boss node without native NoDeath bit");
    put(npcData,0x14c,int(0));put(npcData,0x148,int(0));Sleep(80);combat.tick(8,true);memory.combatState.read(state);
    check(state.actors[0].bossNode==2 && state.actors[0].maxPosture==0,
        "Boss node protection does not depend on posture availability");
    auto previousLife=state.hero;
    put(data,0x130,int(0));p.tick=GetTickCount64();p.damage=1;
    memory.combatReport.write(p);Sleep(60);combat.tick(8,true);memory.combatState.read(state);
    check(state.hp==0 && state.hero==previousLife,"death still publishes a valid native life for the GUI");
    put(data,0x130,int(500));Sleep(60);combat.tick(8,true);memory.combatState.read(state);
    check(state.hero!=previousLife && state.hp==500 && state.ackSession==0,
        "same-address resurrection rejects the pre-death health report");
    auto revivedLife=state.hero;
    Sleep(60);combat.tick(8,true);memory.combatState.read(state);
    check(state.hero==revivedLife && state.hp==500,"stale fatal report cannot kill the resurrected hero next tick");
    VirtualProtect(image.data()+0xbd6000,4096,hpOld,&hpOld);VirtualProtect(image.data()+0xa4a000,4096,lookupOld,&lookupOld);
    std::cout<<n<<" combat layout, validation and owned player immunity checks passed\n";
}
