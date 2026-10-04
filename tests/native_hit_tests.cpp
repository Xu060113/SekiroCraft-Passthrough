#include "../src/combat_game_hook.hpp"
#include <iostream>
#include <stdexcept>
extern "C" {
uintptr_t scTraceFixture();void scTraceGameClobber(uintptr_t,float) noexcept;void scTraceTarget();
uintptr_t scTraceObservedRegs[7]{};uintptr_t scTraceObservedFlags{};
uint32_t scTraceBeforeMxcsr{},scTraceChangedMxcsr{},scTraceObservedMxcsr{};
uint32_t scTraceVector[4]{0x12345678,0x87654321,0xabcdef01,0x10fedcba},scTraceObservedVectors[24]{};
}
static uintptr_t targetChr{},targetData{},attackerChr{},paramRow{};
static int hitCalls{},initCalls{},hpCalls{},lookupCalls{};static bool hasParam=true;
template<class T>T field(uintptr_t p,size_t at){T value;std::memcpy(&value,reinterpret_cast<void*>(p+at),sizeof(value));return value;}
template<class T>void store(uintptr_t p,size_t at,T value){std::memcpy(reinterpret_cast<void*>(p+at),&value,sizeof(value));}
static uintptr_t init(void *packet){++initCalls;auto p=reinterpret_cast<uintptr_t>(packet);
    for(size_t i=0;i<0x240;++i)if(field<uint8_t>(p,i)!=0)throw std::runtime_error("owned packet must start zeroed");
    if(p%16)throw std::runtime_error("packet SIMD alignment");
    store(p,0x80,int32_t(-1));store(p,0x84,int32_t(-1));store(p,0x88,int32_t(-1));return p;
}
static void param(void *ref,int32_t category,int32_t id){++lookupCalls;
    if(category!=1 || id!=5000061)throw std::runtime_error("PC normal attack lookup ABI");
    store(reinterpret_cast<uintptr_t>(ref),0,id);store(reinterpret_cast<uintptr_t>(ref),0x10,hasParam?paramRow:0);
}
static void hit(uintptr_t module,uintptr_t source,void *packet){auto p=reinterpret_cast<uintptr_t>(packet);
    if(field<uintptr_t>(module,8)!=targetChr || source!=attackerChr ||
       field<uintptr_t>(p,0x190)!=source || field<uintptr_t>(p,0x198)!=targetChr)
        throw std::runtime_error("fresh native ownership/three argument ABI");
    if(field<int32_t>(p,0x28)!=1 || field<int32_t>(p,0x50)!=5000061 || field<int32_t>(p,0x54)!=1)
        throw std::runtime_error("normal hit must never manufacture a deathblow");
    if(field<int32_t>(p,0x80)!=-1 || field<uintptr_t>(p,0x1c0)!=0 || field<uintptr_t>(p,0x208)!=0)
        throw std::runtime_error("status/defaults and pointer-bearing tail retained");
    if(!bridge::bridgeVitalWriteDepth)throw std::runtime_error("native bridge trace scope");
    ++hitCalls;
    auto hp=field<int32_t>(targetData,0x130);auto amount=int(field<float>(p,0));
    store(targetData,0x130,std::max(1,hp-amount)); // fixture engine owns its outcome
    store(targetData,0x148,std::max(0,field<int32_t>(targetData,0x148)-int(field<float>(p,0xe0))));
    store(p,0x1e0,123.f); // Native mutable scratch is confined to this dispatch.
}
static uintptr_t find(uintptr_t,uint32_t handle){return handle==456?targetChr:0;}
static void hp(uintptr_t data,int32_t value){++hpCalls;store(data,0x130,value);}
int main(){int checks{};auto check=[&](bool ok,const char *message){++checks;if(!ok)throw std::runtime_error(message);};
    scCombatGameCallback=scTraceGameClobber;
    scCombatGameContinue=reinterpret_cast<void*>(scTraceTarget);
    check(scTraceFixture()==999,"game update hook preserves original return");
    for(unsigned i=0;i<7;++i)check(scTraceObservedRegs[i]==101+i,"game update hook preserves volatile GPRs");
    check((scTraceObservedFlags&0x8d5)==0x45,"game update hook preserves incoming arithmetic flags");
    check(scTraceBeforeMxcsr==scTraceObservedMxcsr,"game update hook preserves SIMD control state");
    for(unsigned i=0;i<24;++i)check(scTraceObservedVectors[i]==scTraceVector[i%4],"game update hook preserves XMM0 through XMM5");
    auto image=static_cast<uint8_t*>(VirtualAlloc(nullptr,70066176,MEM_RESERVE|MEM_COMMIT,PAGE_EXECUTE_READWRITE));
    check(image!=nullptr,"isolated native code fixture");auto base=reinterpret_cast<uintptr_t>(image);
    auto bytes=[&](size_t at,std::initializer_list<uint8_t> values){std::copy(values.begin(),values.end(),image+at);};
    bytes(0x997890,{0x40,0x53,0x48,0x83,0xec,0x20,0x48,0x8b,0xd9,0xe8,0x52,0x04,0,0,0x0f,0x28,0x05,0xeb,0x13,0x43,0x02,0x33,0xc9,0x0f});
    bytes(0x997cf0,{0x80,0xa1,0xed,0,0,0,0xfc,0x48,0x8d,0x91,0xb8,0,0,0,0x83,0xa1,0xf0,0,0,0,0xfe,0x33,0xc0,0x80});
    bytes(0xb6a040,{0x40,0x55,0x53,0x56,0x57,0x41,0x55,0x41,0x56,0x41,0x57,0x48,0x8d,0xac,0x24,0xb0,0xfd,0xff,0xff,0x48,0x81,0xec,0x50,0x03});
    bytes(0x10b5160,{0x40,0x57,0x48,0x83,0xec,0x40,0x48,0xc7,0x44,0x24,0x20,0xfe,0xff,0xff,0xff,0x48,0x89,0x5c,0x24,0x50,0x48,0x89,0x6c,0x24});
    bytes(0xbd64e0,{0x48,0x89,0x5c,0x24,0x18,0x89,0x54,0x24,0x10,0x57,0x48,0x83,0xec,0x20,0x8b,0xb9});
    bytes(0xa4a050,{0x48,0x83,0xec,0x28,0xe8,0x37,0xff,0xff,0xff,0x48,0x85,0xc0,0x74,0x08,0x48,0x8b});
    bytes(0xbd6710,{0x48,0x89,0x6c,0x24,0x18,0x48,0x89,0x74,0x24,0x20,0x57,0x48,0x83,0xec,0x20,0x41});
    bytes(0xbd679a,{0x89,0x87,0x48,0x01,0,0,0x85,0xdb});
    bridge::SharedMemory memory;check(memory.open(L"native-hit-test-"+std::to_wstring(GetCurrentProcessId())),"combat channels");
    bridge::NativeCombatAdapter adapter;adapter.initialize(base,memory);check(adapter.ready() && adapter.prepareNativeHits(),"production code gates accept verified image");
    image[0x997cf0]=0;bridge::NativeHitBackend disabled;check(!disabled.initialize(base),"changed parent initializer refuses dispatch");image[0x997cf0]=0x80;
    auto stub=[&](size_t at,uintptr_t function){uint8_t jump[]{0x48,0xb8,0,0,0,0,0,0,0,0,0xff,0xe0};std::memcpy(jump+2,&function,8);std::memcpy(image+at,jump,12);};
    stub(0x997890,reinterpret_cast<uintptr_t>(init));stub(0x10b5160,reinterpret_cast<uintptr_t>(param));stub(0xb6a040,reinterpret_cast<uintptr_t>(hit));
    stub(0xa4a050,reinterpret_cast<uintptr_t>(find));stub(0xbd64e0,reinterpret_cast<uintptr_t>(hp));
    std::vector<uint8_t> root(0x100),list(0x20),hero(0x2100),target(0x2100),heroModules(0xa0),targetModules(0xa0);
    std::vector<uint8_t> heroPhysics(0x100),targetPhysics(0x100),heroData(0x280),npcData(0x280),heroDamage(0x10),targetDamage(0x10),row(0x240),manager(0x100);
    auto ptr=[](auto &v){return reinterpret_cast<uintptr_t>(v.data());};
    attackerChr=ptr(hero);targetChr=ptr(target);targetData=ptr(npcData);paramRow=ptr(row);
    store(base,0x3d7a1e0,ptr(root));store(base,0x3d77ef0,ptr(manager));store(base,0x3d978b0,ptr(row));
    store(ptr(root),0x88,attackerChr);store(ptr(root),0x10,ptr(list));store(ptr(list),0x18,int32_t(1));
    for(auto pair:{std::pair{attackerChr,ptr(heroModules)},std::pair{targetChr,ptr(targetModules)}}){store(pair.first,0,base+0x1000);store(pair.first,0x1ff8,pair.second);}
    store(attackerChr,8,uint32_t(123));store(targetChr,8,uint32_t(456));store(targetChr,0x74,uint8_t(6));
    store(ptr(heroModules),0x18,ptr(heroData));store(ptr(heroModules),0x68,ptr(heroPhysics));store(ptr(heroModules),0x98,ptr(heroDamage));
    store(ptr(targetModules),0x18,targetData);store(ptr(targetModules),0x68,ptr(targetPhysics));store(ptr(targetModules),0x98,ptr(targetDamage));
    store(ptr(heroPhysics),8,attackerChr);store(ptr(targetPhysics),8,targetChr);
    store(ptr(heroPhysics),0x80,sc::Vec3{1,2,3});store(ptr(targetPhysics),0x80,sc::Vec3{2,2,3});
    store(base,0x2048,base+0xb6a040);
    for(auto pair:{std::pair{ptr(heroDamage),attackerChr},std::pair{ptr(targetDamage),targetChr}}){store(pair.first,0,base+0x2000);store(pair.first,8,pair.second);}
    store(ptr(heroDamage),0,base+0x3000); // Only the target virtual method is invoked.
    for(auto data:{ptr(heroData),targetData}){store(data,0x130,int32_t(1000));store(data,0x134,int32_t(1000));store(data,0x148,int32_t(200));store(data,0x14c,int32_t(200));}
    store(targetData,0x25c,int32_t(2));adapter.enableNativeHits(true);adapter.tick(9,true);adapter.observe(ptr(targetPhysics));Sleep(80);adapter.tick(9,true);
    bridge::CombatState state;check(memory.combatState.read(state) && state.count==1,"current native actor registry");
    bridge::CombatReport report;report.tick=GetTickCount64();report.epoch=9;report.hero=state.hero;report.session=7;report.command=1;report.commands[0]={1,state.actors[0].id,2,0};memory.combatReport.write(report);
    adapter.tick(9,true);check(hitCalls==0 && hpCalls==0,"physics callback defers native hit and does not double-write HP");
    adapter.gameTick(ptr(manager)+1,.016f);adapter.gameTick(ptr(manager),0);adapter.gameTick(ptr(manager),NAN);
    check(hitCalls==0,"wrong manager and paused/invalid frame reject native entry");
    adapter.gameTick(ptr(manager),.016f);check(hitCalls==1 && initCalls==1 && lookupCalls==1,"owned normal packet dispatches exactly once on combat update");
    check(field<int32_t>(targetData,0x130)==900 && field<int32_t>(targetData,0x148)==180 && hpCalls==0,"native engine owns HP and posture outcomes");
    check(field<int32_t>(targetData,0x25c)==2 && bridge::bridgeVitalWriteDepth==0,"normal hits do not edit Boss nodes and trace scope unwinds");
    adapter.gameTick(ptr(manager),.016f);check(hitCalls==1,"acknowledged native command is not replayed");
    report.command=2;report.commands[1]={2,state.actors[0].id,2,0};report.tick=GetTickCount64();memory.combatReport.write(report);adapter.tick(9,true);
    store(ptr(targetDamage),8,uintptr_t(0));adapter.gameTick(ptr(manager),.016f);check(hitCalls==1 && adapter.rejected==1 && hpCalls==0 && adapter.nativeHitFailure()==2,"freed/replaced damage-module owner rejects without HP fallback");store(ptr(targetDamage),8,targetChr);
    report.command=3;report.commands[2]={3,state.actors[0].id,2,0};memory.combatReport.write(report);adapter.tick(9,true);hasParam=false;adapter.gameTick(ptr(manager),.016f);
    check(hitCalls==1 && initCalls==1 && adapter.rejected==2 && adapter.nativeHitFailure()==5,"missing native attack PARAM never creates a partial packet");hasParam=true;
    report.command=4;report.commands[3]={4,state.actors[0].id,2,0};report.tick=1;memory.combatReport.write(report);adapter.tick(9,true);adapter.gameTick(ptr(manager),.016f);
    check(hitCalls==1,"stale peer cannot apply native damage");
    report.tick=GetTickCount64();memory.combatReport.write(report);adapter.tick(9,false);adapter.gameTick(ptr(manager),.016f);check(hitCalls==1,"inactive bridge cannot apply native damage");
    std::cout<<checks<<" native hit ownership, game context, deduplication and register checks passed\n";
    VirtualFree(image,0,MEM_RELEASE);
}
