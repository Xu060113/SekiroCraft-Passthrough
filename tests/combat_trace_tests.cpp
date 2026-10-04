#include "../src/combat_trace.hpp"
#include <iostream>
#include <stdexcept>
extern "C" {
uintptr_t scTraceFixture();void scTraceClobber(uintptr_t,uintptr_t,uintptr_t,uintptr_t);void scTraceTarget();
uintptr_t scTraceObservedRegs[7]{};uintptr_t scTraceObservedFlags{};
uint32_t scTraceBeforeMxcsr{},scTraceChangedMxcsr{},scTraceObservedMxcsr{};
uint32_t scTraceVector[4]{0x12345678,0x87654321,0xabcdef01,0x10fedcba},scTraceObservedVectors[24]{};
}
static int hpCalls{},postureCalls{};
static int hitCalls{};
static uintptr_t hitSetter(uintptr_t context,uintptr_t attack,uintptr_t hit,uintptr_t mode){
    if(!context || !attack || !hit || mode!=0x12345)throw std::runtime_error("native hit arguments changed");
    ++hitCalls;*reinterpret_cast<uint8_t*>(hit)=0xcd;return 777;
}
static void hpSetter(uintptr_t data,int hp){++hpCalls;
    if(*reinterpret_cast<uint8_t*>(data+0x228)&4)hp=std::max(1,hp);
    *reinterpret_cast<int*>(data+0x130)=hp;
}
static void postureSetter(uintptr_t data,int posture,uint8_t recovery){++postureCalls;
    if(recovery!=7)throw std::runtime_error("original recovery argument changed");
    *reinterpret_cast<int*>(data+0x148)=posture;
}
int main(){int n{};auto check=[&](bool ok,const char *s){++n;if(!ok)throw std::runtime_error(s);};
    scCombatHitTraceCallback=scTraceClobber;scCombatHitTraceContinue=reinterpret_cast<void*>(scTraceTarget);
    check(scTraceFixture()==999,"native hit tail jump retains original return value");
    for(unsigned i=0;i<7;++i)check(scTraceObservedRegs[i]==101+i,"native argument and volatile registers retained");
    check((scTraceObservedFlags&0x8d5)==0x45,"native incoming arithmetic flags retained");
    check(scTraceBeforeMxcsr==scTraceObservedMxcsr,"SIMD control state retained after diagnostic callback");
    for(unsigned i=0;i<24;++i)check(scTraceObservedVectors[i]==scTraceVector[i%4],"all six volatile SIMD registers retained");
    auto image=static_cast<uint8_t*>(VirtualAlloc(nullptr,70066176,MEM_RESERVE|MEM_COMMIT,PAGE_EXECUTE_READWRITE));
    check(image!=nullptr,"allocate isolated executable fixture");
    auto base=reinterpret_cast<uintptr_t>(image);
    auto stub=[&](size_t at,uintptr_t target){uint8_t b[]{0x48,0xb8,0,0,0,0,0,0,0,0,0xff,0xe0};
        std::memcpy(b+2,&target,8);std::memcpy(image+at,b,12);};
    stub(0xbd64e0,reinterpret_cast<uintptr_t>(hpSetter));stub(0xbd6710,reinterpret_cast<uintptr_t>(postureSetter));
    const uint8_t hitPrologue[]{0x48,0x89,0x5c,0x24,0x08,0x48,0x89,0x6c,0x24,0x10,0x48,0x89,0x74,0x24,0x18,
        0x57,0x48,0x83,0xec,0x30,0x41,0x0f,0xb6,0xe9};
    std::memcpy(image+0xb68ff0,hitPrologue,sizeof(hitPrologue));
    uint8_t hitBody[]{0x48,0xb8,0,0,0,0,0,0,0,0,0xff,0xd0,0x48,0x8b,0x5c,0x24,0x40,
        0x48,0x8b,0x6c,0x24,0x48,0x48,0x8b,0x74,0x24,0x50,0x48,0x83,0xc4,0x30,0x5f,0xc3};
    auto hitFunction=reinterpret_cast<uintptr_t>(hitSetter);std::memcpy(hitBody+2,&hitFunction,8);
    std::memcpy(image+0xb68ff0+sizeof(hitPrologue),hitBody,sizeof(hitBody));
    std::array<uint8_t,0x280> data{};auto p=reinterpret_cast<uintptr_t>(data.data());
    auto put=[&](size_t at,int x){std::memcpy(data.data()+at,&x,4);};
    put(0x130,100);put(0x134,100);put(0x148,200);put(0x14c,200);put(0x25c,2);data[0x228]=4;
    auto root=std::filesystem::temp_directory_path()/(L"sekiro-trace-test-"+std::to_wstring(GetCurrentProcessId()));
    std::filesystem::create_directories(root);
    auto trace=new bridge::CombatTrace;
    check(!trace->install(base,false,root),"unverified native entry refuses tracing");
    check(MH_Initialize()==MH_OK && trace->install(base,true,root),"real production detours install");
    auto hp=reinterpret_cast<void(*)(uintptr_t,int)>(base+0xbd64e0);
    auto posture=reinterpret_cast<void(*)(uintptr_t,int,uint8_t)>(base+0xbd6710);
    hp(p,0);check(hpCalls==1 && *reinterpret_cast<int*>(data.data()+0x130)==1 && data[0x228]==4,
        "native HP call forwarded once with boss NoDeath unchanged");
    posture(p,150,7);check(postureCalls==1 && *reinterpret_cast<int*>(data.data()+0x148)==150,
        "native posture arguments and result unchanged");
    {bridge::BridgeVitalWrite a;bridge::BridgeVitalWrite b;hp(p,50);}
    check(bridge::bridgeVitalWriteDepth==0 && hpCalls==2,"nested bridge ownership unwinds");
    posture(p,190,7);check(postureCalls==2,"recovery remains native even when not recorded");
    posture(p,-20,7);check(postureCalls==3 && *reinterpret_cast<int*>(data.data()+0x148)==-20,
        "native broken-posture negative remainder is retained");
    std::array<uint8_t,32> context{};std::array<uint8_t,256> attack{};std::array<uint8_t,640> hit{};hit[0]=0xab;
    auto nativeHit=reinterpret_cast<uintptr_t(*)(uintptr_t,uintptr_t,uintptr_t,uintptr_t)>(base+0xb68ff0);
    check(nativeHit(reinterpret_cast<uintptr_t>(context.data()),reinterpret_cast<uintptr_t>(attack.data()),
                    reinterpret_cast<uintptr_t>(hit.data()),0x12345)==777 && hitCalls==1 && hit[0]==0xcd,
        "real hit detour tail-calls original once with original arguments and result");
    std::ofstream(root/L"combat-trace.stop").close();
    std::string content;
    for(int i=0;i<30;++i){Sleep(50);for(auto &entry:std::filesystem::directory_iterator(root))
        if(entry.path().extension()==L".jsonl"){std::ifstream in(entry.path());content.assign(std::istreambuf_iterator<char>(in),{});}
        if(content.find("\"stopped\":true")!=std::string::npos)break;}
    check(content.find("\"stopped\":true,\"dropped\":0")!=std::string::npos,"writer flushes bounded queue off the native thread");
    check(content.find("\"source\":\"native\",\"target\":0")!=std::string::npos &&
        content.find("\"source\":\"bridge\",\"target\":50")!=std::string::npos,"trace distinguishes native and bridge writes");
    check(content.find("\"target\":150,\"recovery\":7")!=std::string::npos &&
        content.find("\"target\":190") == std::string::npos,"damage recorded and routine posture recovery omitted");
    check(content.find("\"target\":-20")!=std::string::npos,"broken-posture transition is recorded");
    check(content.find("\"kind\":\"native-hit-entry\"")!=std::string::npos &&
        content.find("\"hitBytes\":\"ab00")!=std::string::npos,"native hit input captured before original mutation");
    MH_Uninitialize();VirtualFree(image,0,MEM_RELEASE);std::filesystem::remove_all(root);
    std::cout<<n<<" production combat trace detour checks passed\n";
}
