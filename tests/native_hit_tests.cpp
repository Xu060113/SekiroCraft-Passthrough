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
static int hitCalls{},initCalls{},hpCalls{},lookupCalls{},phaseCalls{};static bool hasParam=true;
static sc::Vec3 lastPoint{},lastDirection{};
template<class T>T field(uintptr_t p,size_t at){T value;std::memcpy(&value,reinterpret_cast<void*>(p+at),sizeof(value));return value;}
template<class T>void store(uintptr_t p,size_t at,T value){std::memcpy(reinterpret_cast<void*>(p+at),&value,sizeof(value));}
static uintptr_t init(void *packet){++initCalls;auto p=reinterpret_cast<uintptr_t>(packet);
    for(size_t i=0;i<0x240;++i)if(field<uint8_t>(p,i)!=0)throw std::runtime_error("owned packet must start zeroed");
    if(p%16)throw std::runtime_error("packet SIMD alignment");
    store(p,0x80,int32_t(-1));store(p,0x84,int32_t(-1));store(p,0x88,int32_t(-1));
    for(auto at:{0x8cu,0x90u,0x94u,0x98u,0x9cu})store(p,at,int32_t(-1));return p;
}
static void param(void *ref,int32_t category,int32_t id){++lookupCalls;
    if(category!=1 || (id!=5000010 && id!=5000600))throw std::runtime_error("PC attack lookup ABI");
    store(reinterpret_cast<uintptr_t>(ref),0,id);store(reinterpret_cast<uintptr_t>(ref),0x10,hasParam?paramRow:0);
}
static void hit(uintptr_t module,uintptr_t source,void *packet){auto p=reinterpret_cast<uintptr_t>(packet);
    if(field<uintptr_t>(module,8)!=targetChr || source!=attackerChr ||
       field<uintptr_t>(p,0x190)!=source || field<uintptr_t>(p,0x198)!=targetChr)
        throw std::runtime_error("fresh native ownership/three argument ABI");
    if(field<int32_t>(p,0x28)==5){
        if(field<int32_t>(p,0x50)!=5000600 || !bridge::bridgeVitalWriteDepth)throw std::runtime_error("native phase ABI");
        ++phaseCalls;store(targetData,0x25c,field<int32_t>(targetData,0x25c)-1);store(targetData,0x130,int32_t(1));return;
    }
    if(field<int32_t>(p,0x28)!=1 || field<int32_t>(p,0x50)!=5000010 || field<int32_t>(p,0x54)!=1)
        throw std::runtime_error("normal hit must never manufacture a deathblow");
    if(field<int32_t>(p,0x24)!=2 || field<float>(p,0x34)!=15 || field<float>(p,0x38)!=30 ||
       field<float>(p,0x3c)!=30 || field<int32_t>(p,0x4c)!=105000010 || field<uint32_t>(p,0x7c)!=0x10001)
        throw std::runtime_error("grounded native attack identity and reaction strengths stay coherent");
    for(auto at:{0x8cu,0x90u,0x94u,0x98u,0x9cu})
        if(field<int32_t>(p,at)!=0)throw std::runtime_error("normal profile's empty effect fields");
    if(field<int16_t>(p,0x1da)!=-1 || field<int16_t>(p,0x1de)!=-1 ||
       field<uint16_t>(p,0x230)!=0 || field<uint32_t>(p,0xec)!=0 || field<uint32_t>(p,0xf0)!=0)
        throw std::runtime_error("native reaction selectors set without copying stack padding into flags");
    if(field<int32_t>(p,0x80)!=-1 || field<uintptr_t>(p,0x1c0)!=0 || field<uintptr_t>(p,0x208)!=0)
        throw std::runtime_error("status/defaults and pointer-bearing tail retained");
    if(!bridge::bridgeVitalWriteDepth)throw std::runtime_error("native bridge trace scope");
    ++hitCalls;
    lastPoint=field<sc::Vec3>(p,0x130);lastDirection=field<sc::Vec3>(p,0x140);
    auto hp=field<int32_t>(targetData,0x130);auto amount=int(field<float>(p,0));
    store(targetData,0x130,std::max(1,hp-amount)); // fixture engine owns its outcome
    store(targetData,0x148,std::max(0,field<int32_t>(targetData,0x148)-int(field<float>(p,0xe0))));
    store(p,0x1e0,123.f); // Native mutable scratch is confined to this dispatch.
}
static uintptr_t find(uintptr_t,uint32_t handle){return handle==456?targetChr:handle==123?attackerChr:0;}
static void hp(uintptr_t data,int32_t value){++hpCalls;
    if(value==0 && (field<uint8_t>(data,0x228)&4))value=1;
    store(data,0x130,value);
}
static bridge::NativeCombatAdapter *defenseAdapter{};
static void routedHeroHp(uintptr_t data,int32_t value){hp(data,defenseAdapter->incomingHp(data,value));}
static void posture(uintptr_t data,int32_t value,uint8_t recovery){
    if(recovery!=1)throw std::runtime_error("stage recovery ABI");store(data,0x148,value);
}
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
    bytes(0xb6e800,{0x89,0x91,0x5c,0x02,0,0});
    bytes(0xb6e892,{0xe8,0xa9,0x64,0x06,0});
    bridge::SharedMemory memory;check(memory.open(L"native-hit-test-"+std::to_wstring(GetCurrentProcessId())),"combat channels");
    bridge::NativeCombatAdapter adapter;adapter.initialize(base,memory);check(adapter.ready() && adapter.prepareNativeHits(),"production code gates accept verified image");
    image[0x997cf0]=0;bridge::NativeHitBackend disabled;check(!disabled.initialize(base),"changed parent initializer refuses dispatch");image[0x997cf0]=0x80;
    auto stub=[&](size_t at,uintptr_t function){uint8_t jump[]{0x48,0xb8,0,0,0,0,0,0,0,0,0xff,0xe0};std::memcpy(jump+2,&function,8);std::memcpy(image+at,jump,12);};
    stub(0x997890,reinterpret_cast<uintptr_t>(init));stub(0x10b5160,reinterpret_cast<uintptr_t>(param));stub(0xb6a040,reinterpret_cast<uintptr_t>(hit));
    stub(0xa4a050,reinterpret_cast<uintptr_t>(find));stub(0xbd64e0,reinterpret_cast<uintptr_t>(hp));
    stub(0xbd6710,reinterpret_cast<uintptr_t>(posture));
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
    bridge::CombatReport report;report.tick=GetTickCount64();report.epoch=9;report.hero=state.hero;report.session=7;report.command=1;report.commands[0]={1,state.actors[0].id,2,0,1};memory.combatReport.write(report);
    adapter.tick(9,true);check(hitCalls==0 && hpCalls==0,"physics callback defers native hit and does not double-write HP");
    adapter.gameTick(ptr(manager)+1,.016f);adapter.gameTick(ptr(manager),0);adapter.gameTick(ptr(manager),NAN);
    check(hitCalls==0,"wrong manager and paused/invalid frame reject native entry");
    adapter.gameTick(ptr(manager),.016f);check(hitCalls==1 && initCalls==1 && lookupCalls==1,"owned normal packet dispatches exactly once on combat update");
    check(field<int32_t>(targetData,0x130)==990 && field<int32_t>(targetData,0x148)==198 && hpCalls==0,"native engine owns HP and posture outcomes");
    check(1000-field<int32_t>(targetData,0x130)==10,"2 MC damage costs 1 percent of a default Boss stage, not 10 percent");
    check(field<int32_t>(targetData,0x25c)==2 && bridge::bridgeVitalWriteDepth==0,"normal hits do not edit Boss nodes and trace scope unwinds");
    adapter.gameTick(ptr(manager),.016f);check(hitCalls==1,"acknowledged native command is not replayed");
    report.command=2;report.commands[1]={2,state.actors[0].id,2,0,1};report.tick=GetTickCount64();memory.combatReport.write(report);adapter.tick(9,true);
    store(ptr(targetDamage),8,uintptr_t(0));adapter.gameTick(ptr(manager),.016f);check(hitCalls==1 && adapter.rejected==1 && hpCalls==0 && adapter.nativeHitFailure()==2,"freed/replaced damage-module owner rejects without HP fallback");store(ptr(targetDamage),8,targetChr);
    report.command=3;report.commands[2]={3,state.actors[0].id,2,0,1};memory.combatReport.write(report);adapter.tick(9,true);hasParam=false;adapter.gameTick(ptr(manager),.016f);
    check(hitCalls==1 && initCalls==1 && adapter.rejected==2 && adapter.nativeHitFailure()==5,"missing native attack PARAM never creates a partial packet");hasParam=true;
    report.command=4;report.commands[3]={4,state.actors[0].id,2,0,1};report.tick=1;memory.combatReport.write(report);adapter.tick(9,true);adapter.gameTick(ptr(manager),.016f);
    check(hitCalls==1,"stale peer cannot apply native damage");
    report.tick=GetTickCount64();memory.combatReport.write(report);adapter.tick(9,false);adapter.gameTick(ptr(manager),.016f);check(hitCalls==1,"inactive bridge cannot apply native damage");
    check(!adapter.enablePhaseFinishes(true),"missing phase signatures refuse the opt-in stage backend");
    store(targetData,0x25c,int32_t(1));
    report.tick=GetTickCount64();report.command=5;report.commands[4]={5,state.actors[0].id,2,1,1};
    memory.combatReport.write(report);adapter.tick(9,true);adapter.gameTick(ptr(manager),.016f);
    check(hitCalls==1,"queued projectiles from the previous Boss stage are acknowledged without damaging the next stage");
    store(ptr(targetPhysics),0x80,sc::Vec3{40,2,3});
    report.command=6;report.commands[5]={6,state.actors[0].id,2,1,2,{40,3,3},{1,0,0},1,0};
    memory.combatReport.write(report);adapter.tick(9,true);adapter.gameTick(ptr(manager),.016f);
    check(hitCalls==2,"a fresh ranged hit between 32 and 64 metres reaches the native backend");
    check(lastPoint.x==40 && lastPoint.y==3 && lastDirection.x==1,"projectile impact and direction survive native packet construction");
    adapter.gameTick(ptr(manager),.016f);check(hitCalls==2,"ranged hit is dispatched once");
    check(adapter.enableAutoBossPhases(true),"explicit automatic stages accept validated node store");
    store(targetData,0x25c,int32_t(2));store(targetData,0x130,int32_t(10));store(targetData,0x228,uint8_t(4));
    Sleep(80);adapter.tick(9,true);memory.combatState.read(state);
    report.command=7;report.tick=GetTickCount64();report.commands[6]={7,state.actors[0].id,40,0,state.actors[0].stage};
    memory.combatReport.write(report);adapter.tick(9,true);adapter.gameTick(ptr(manager),.016f);
    check(field<int32_t>(targetData,0x25c)==1 && field<int32_t>(targetData,0x130)==1000 &&
        field<int32_t>(targetData,0x148)==200,"melee HP depletion removes exactly one node and refills remaining phase");
    check(field<uint8_t>(targetData,0x228)==4 && adapter.phaseConfirmed==1 && adapter.phaseFallbacks==1,
        "explicit stage fallback preserves native flags and reports a confirmed decrement");
    adapter.gameTick(ptr(manager),.016f);check(adapter.phaseConfirmed==1,"acknowledged phase command cannot remove a second node");
    report.command=8;report.commands[7]={8,state.actors[0].id,40,1,report.commands[6].stage};
    memory.combatReport.write(report);adapter.tick(9,true);adapter.gameTick(ptr(manager),.016f);
    check(field<int32_t>(targetData,0x130)==1000 && field<int32_t>(targetData,0x25c)==1,
        "queued arrow from old stage cannot injure the refilled stage");
    Sleep(800);store(targetData,0x130,int32_t(10));adapter.tick(9,true);memory.combatState.read(state);
    report.command=9;report.tick=GetTickCount64();report.commands[8]={9,state.actors[0].id,40,1,state.actors[0].stage};
    memory.combatReport.write(report);adapter.tick(9,true);adapter.gameTick(ptr(manager),.016f);
    check(field<int32_t>(targetData,0x25c)==0 && field<int32_t>(targetData,0x130)==0 && adapter.phaseConfirmed==2,
        "ranged HP depletion completes final node without a manual deathblow");
    bytes(0xb6e7c5,{0x41,0x83,0x7e,0x28,0x05,0x75,0x4a});
    bytes(0x9f0410,{0x48,0x8b,0xc4,0x57,0x48,0x81,0xec,0xa0,0,0,0});
    Sleep(800);store(targetData,0x25c,int32_t(2));store(targetData,0x130,int32_t(10));
    adapter.tick(9,true);memory.combatState.read(state);
    report.command=10;report.tick=GetTickCount64();report.commands[9]={10,state.actors[0].id,40,4,state.actors[0].stage};
    memory.combatReport.write(report);adapter.tick(9,true);adapter.gameTick(ptr(manager),.016f);
    check(phaseCalls==1 && field<int32_t>(targetData,0x25c)==1 && adapter.phaseFallbacks==2 && adapter.phaseConfirmed==3,
        "mob damage prefers native phase packet and never double decrements after engine confirmation");
    defenseAdapter=&adapter;adapter.defenseReady(true);
    report.tick=GetTickCount64();memory.combatReport.write(report);adapter.tick(9,true);
    bridge::NativeInjuries injuries;
    check(memory.nativeInjuries.read(injuries) && injuries.hero==state.hero && injuries.session==7,"incoming damage channel is paired to current MC report and native life");
    // Synthetic native caller returns at the exact live HP damage stack RVA.
    // It calls the production filter, so script/fall paths cannot masquerade as combat.
    uint8_t call[]{0x48,0x83,0xec,0x28,0x48,0xb8,0,0,0,0,0,0,0,0,0xff,0xd0,0x48,0x83,0xc4,0x28,0xc3};
    auto route=reinterpret_cast<uintptr_t>(routedHeroHp);std::memcpy(call+6,&route,8);std::memcpy(image+0xb690f0,call,sizeof(call));
    auto incomingHp=reinterpret_cast<void(*)(uintptr_t,int32_t)>(base+0xb690f0);
    incomingHp(ptr(heroData),900);
    check(field<int32_t>(ptr(heroData),0x130)==900,"native damage retained until a vanilla server acknowledges defense readiness");
    store(ptr(heroData),0x130,int32_t(1000));
    bridge::NativeInjuryAck injuryAck{GetTickCount64(),9,state.hero,7,0};memory.nativeInjuryAck.write(injuryAck);
    std::array<uint8_t,0x280> incomingPacket{};auto packet=reinterpret_cast<uintptr_t>(incomingPacket.data());
    store(packet,0x190,targetChr);store(packet,0x28,int32_t(1));adapter.incoming(ptr(heroDamage),packet);
    incomingHp(ptr(heroData),0);
    check(field<int32_t>(ptr(heroData),0x130)==1000,"lethal native attack waits for vanilla armor/shield processing before committing HP/death");
    adapter.tick(9,true);memory.nativeInjuries.read(injuries);
    check(injuries.produced==1 && injuries.hits[0].ratio==1 && injuries.hits[0].flags==1 && injuries.hits[0].source.x==40,"raw native attack and actual attacker position reach vanilla defense once");
    injuryAck.processed=1;memory.nativeInjuryAck.write(injuryAck);report.damage=.1;report.tick=GetTickCount64();memory.combatReport.write(report);adapter.tick(9,true);
    check(field<int32_t>(ptr(heroData),0x130)==900,"only vanilla's reduced injury is written back to native HP");
    auto raw=adapter.incomingHp(ptr(heroData),800);
    check(raw==800,"native script/fall HP writes outside verified attack stack bypass armor routing");
    // Ranged callers may arrive at the shared damage core without B69100.
    std::memcpy(image+0xb6e887,call,sizeof(call));
    auto coreHp=reinterpret_cast<void(*)(uintptr_t,int32_t)>(base+0xb6e887);
    auto routeHit=[&](uintptr_t callerSource=0){
        injuryAck.tick=GetTickCount64();memory.nativeInjuryAck.write(injuryAck);
        report.tick=GetTickCount64();memory.combatReport.write(report);adapter.tick(9,true);
        adapter.incoming(ptr(heroDamage),packet,callerSource);coreHp(ptr(heroData),800);
        adapter.tick(9,true);memory.nativeInjuries.read(injuries);
    };
    store(packet,0x190,uintptr_t(0));routeHit(targetChr);
    check(injuries.produced==2 && injuries.hits[1].flags==1 && injuries.hits[1].source.x==40 &&
          field<int32_t>(ptr(heroData),0x130)==900,"ranged core HP path preserves the caller's live source actor for shields");
    injuryAck.processed=2;
    store(packet,0x130,sc::Vec3{1,3,3});store(packet,0x150,sc::Vec3{0,0,1});routeHit();
    check(injuries.produced==3 && injuries.hits[2].flags==1 && injuries.hits[2].source.z==1 &&
          adapter.routedTrajectoryInjuries==1,"ownerless forward-moving projectile recovers the incoming side rather than the player's view");
    injuryAck.processed=3;
    store(packet,0x150,sc::Vec3{0,0,-1});routeHit();
    check(injuries.produced==4 && injuries.hits[3].source.z==5,"rear projectile keeps the opposite incoming side for vanilla shield rejection");
    injuryAck.processed=4;
    store(packet,0x150,sc::Vec3{});routeHit();
    check(injuries.produced==5 && injuries.hits[4].flags==0 && adapter.routedUnknownInjuries==1,
          "missing projectile direction never grants an arbitrary shield origin");
    injuryAck.processed=5;
    store(packet,0x150,sc::Vec3{NAN,0,1});routeHit();
    check(injuries.produced==6 && injuries.hits[5].flags==0,"nonfinite projectile metadata cannot grant directional immunity");
    injuryAck.processed=6;
    store(packet,0x150,sc::Vec3{0,0,1});store(packet,0x130,sc::Vec3{100,3,3});routeHit();
    check(injuries.produced==7 && injuries.hits[6].flags==0,"remote impact unrelated to the hero cannot supply a shield direction");
    injuryAck.processed=7;
    // An intervening hit on an NPC must invalidate the player's same-thread source.
    adapter.incoming(ptr(targetDamage),packet);coreHp(ptr(heroData),800);
    check(field<int32_t>(ptr(heroData),0x130)==800,"core HP path without a current player hit retains native damage");
    store(ptr(heroData),0x130,int32_t(900));
    store(packet,0x130,sc::Vec3{1,3,3});store(packet,0x150,sc::Vec3{0,0,1});
    adapter.incoming(ptr(heroDamage),packet);Sleep(60);coreHp(ptr(heroData),800);
    check(field<int32_t>(ptr(heroData),0x130)==800,"stale player hit cannot route later core HP writes through shields");
    check(adapter.routedCoreInjuries==6,"alternate attack core accounts for routed ranged damage");
    sc::Vec3 origin;
    check(!bridge::injuryOriginFromTrajectory({1,2,3},{1,3,3},{0,1,0},origin),"vertical trajectory cannot fabricate a horizontal shield side");
    check(!bridge::injuryOriginFromTrajectory({1,2,3},{1,3,3},{0,0,10},origin),"unnormalized collision metadata cannot supply a shield side");
    injuryAck.tick=1;memory.nativeInjuryAck.write(injuryAck);incomingHp(ptr(heroData),800);
    check(field<int32_t>(ptr(heroData),0x130)==800,"stale defense acknowledgement cannot leave player invulnerable");
    injuryAck.tick=GetTickCount64();memory.nativeInjuryAck.write(injuryAck);
    store(packet,0x28,int32_t(5));adapter.incoming(ptr(heroDamage),packet);incomingHp(ptr(heroData),0);
    check(field<int32_t>(ptr(heroData),0x130)==0,"native deathblow remains unshieldable and never enters the vanilla injury ring");
    std::cout<<checks<<" native hit ownership, defense routing, game context and register checks passed\n";
    VirtualFree(image,0,MEM_RELEASE);
}
