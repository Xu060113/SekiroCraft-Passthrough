#pragma once
#include "sekirocraft/host.hpp"
#include "MinHook.h"
#include <atomic>
#include <thread>
#include "../bridge/native_action.hpp"
extern "C" {
void scCombatHitTraceEntry();
extern void *scCombatHitTraceContinue;
extern void (*scCombatHitTraceCallback)(uintptr_t,uintptr_t,uintptr_t,uintptr_t);
}

namespace bridge {
// Diagnostic ownership is independent of the combat adapter. Native calls are
// always forwarded once, with their original arguments and return path.
inline thread_local unsigned bridgeVitalWriteDepth{};
struct BridgeVitalWrite {
    BridgeVitalWrite(){++bridgeVitalWriteDepth;}
    ~BridgeVitalWrite(){--bridgeVitalWriteDepth;}
};

class CombatTrace {
    using SetHp=void(*)(uintptr_t,int32_t);
    using SetPosture=void(*)(uintptr_t,int32_t,uint8_t);
    struct Vitals {int32_t hp{},maxHp{},posture{},maxPosture{},node{};uint8_t bits{};};
    struct Event {
        uint64_t tick{};uintptr_t data{};DWORD thread{};int32_t target{};
        uint8_t kind{},recovery{},bridge{},frames{};
        Vitals before{},after{};std::array<uint32_t,24> stack{};
        uint64_t hitId{};std::array<uintptr_t,4> args{};
        std::array<uint8_t,256> attack{};std::array<uint8_t,640> hit{};
        bool attackRead{},hitRead{};
    };
    inline static CombatTrace *instance_{};
    uintptr_t base_{};SetHp hp_{};SetPosture posture_{};
    std::mutex mutex_;std::array<Event,2048> events_{};
    size_t head_{},count_{};std::atomic<uint64_t> dropped_{};
    std::atomic<bool> enabled_{};uint64_t started_{};
    std::atomic<uint64_t> hitSequence_{},activity_{};
    NativeAnimation lastAnimation_{};uint32_t lastActionFlags_=~0u;uint64_t animationTick_{};
    static bool read(uintptr_t data,Vitals &v){
        return sc::readMemory(data+0x130,v.hp) && sc::readMemory(data+0x134,v.maxHp) &&
            sc::readMemory(data+0x148,v.posture) && sc::readMemory(data+0x14c,v.maxPosture) &&
            sc::readMemory(data+0x25c,v.node) && sc::readMemory(data+0x228,v.bits) &&
            v.maxHp>0 && v.maxHp<=10000000 && v.hp>=0 && v.hp<=v.maxHp &&
            v.maxPosture>=0 && v.maxPosture<=10000000 && v.posture>=-100 && v.posture<=v.maxPosture;
    }
    bool begin(Event &e,uintptr_t data,int32_t target,uint8_t kind,uint8_t recovery=0){
        if(!enabled_.load(std::memory_order_relaxed) || !read(data,e.before) ||
           (kind==1?target==e.before.hp:target>=e.before.posture))return false;
        e.tick=GetTickCount64();e.thread=GetCurrentThreadId();e.data=data;e.target=target;
        e.kind=kind;e.recovery=recovery;e.bridge=bridgeVitalWriteDepth!=0;
        if(target<(kind==1?e.before.hp:e.before.posture))activity_=e.tick;
        stack(e);
        return true;
    }
    void stack(Event &e){
        std::array<void*,24> stack{};
        auto n=CaptureStackBackTrace(0,stack.size(),stack.data(),nullptr);
        for(unsigned i=0;i<n;++i){auto p=reinterpret_cast<uintptr_t>(stack[i]);
            if(p>=base_ && p<base_+70066176)e.stack[e.frames++]=uint32_t(p-base_);}
    }
    void queue(const Event &e){
        std::unique_lock lock(mutex_,std::try_to_lock);
        if(!lock || count_==events_.size()){dropped_.fetch_add(1);return;}
        events_[(head_+count_)%events_.size()]=e;++count_;
    }
    void end(Event &e){
        if(!read(e.data,e.after))return;
        queue(e);
    }
    static void hitCallback(uintptr_t context,uintptr_t attack,uintptr_t hit,uintptr_t mode)noexcept{
        auto *self=instance_;if(!self || !self->enabled_.load(std::memory_order_relaxed))return;
        Event e{};e.kind=3;e.tick=GetTickCount64();e.thread=GetCurrentThreadId();
        e.hitId=++self->hitSequence_;e.args={context,attack,hit,mode};e.bridge=bridgeVitalWriteDepth!=0;
        uintptr_t chr{},modules{},data{},vt{};
        if(sc::readMemory(context+8,chr) && sc::readMemory(chr,vt) && vt>=self->base_ && vt<self->base_+70066176 &&
           sc::readMemory(chr+0x1ff8,modules) && sc::readMemory(modules+0x18,data) && read(data,e.before))e.data=data;
        e.after=e.before;e.attackRead=sc::readMemory(attack,e.attack);e.hitRead=sc::readMemory(hit,e.hit);
        self->activity_=e.tick;self->stack(e);self->queue(e);
    }
    static void hpHook(uintptr_t data,int32_t hp){
        auto *self=instance_;Event e{};bool record=self->begin(e,data,hp,1);
        self->hp_(data,hp);if(record)self->end(e);
    }
    static void postureHook(uintptr_t data,int32_t posture,uint8_t recovery){
        auto *self=instance_;Event e{};bool record=self->begin(e,data,posture,2,recovery);
        self->posture_(data,posture,recovery);if(record)self->end(e);
    }
    static void vital(std::ostream &out,const Vitals &v){
        out<<"{\"hp\":"<<v.hp<<",\"maxHp\":"<<v.maxHp<<",\"posture\":"<<v.posture
           <<",\"maxPosture\":"<<v.maxPosture<<",\"bossNode\":"<<v.node<<",\"bits\":"<<unsigned(v.bits)<<'}';
    }
    template<size_t N>static void bytes(std::ostream &out,const std::array<uint8_t,N> &data){
        constexpr char hex[]="0123456789abcdef";out<<'"';
        for(auto b:data)out<<hex[b>>4]<<hex[b&15];out<<'"';
    }
    void flush(std::ofstream &out){
        // File I/O and formatting never run in the game's setter threads.
        for(;;){Event e;{std::lock_guard lock(mutex_);if(!count_)break;
                e=events_[head_];head_=(head_+1)%events_.size();--count_;}
            out<<"{\"tick\":"<<e.tick<<",\"thread\":"<<e.thread<<",\"data\":\"0x"<<std::hex<<e.data
               <<std::dec<<"\",\"kind\":\""<<(e.kind==1?"hp":e.kind==2?"posture":e.kind==4?"animation-state":"native-hit-entry")<<"\",\"source\":\""
               <<(e.bridge?"bridge":"native")<<"\",\"target\":"<<e.target<<",\"recovery\":"<<unsigned(e.recovery)
               <<",\"before\":";vital(out,e.before);out<<",\"after\":";vital(out,e.after);
            out<<",\"stackRva\":[";for(unsigned i=0;i<e.frames;++i){if(i)out<<',';
                out<<'"'<<std::hex<<e.stack[i]<<std::dec<<'"';}out<<']';
            if(e.kind==3 || e.kind==4){out<<",\"hitId\":"<<e.hitId<<",\"args\":[";
                for(unsigned i=0;i<4;++i){if(i)out<<',';out<<"\"0x"<<std::hex<<e.args[i]<<std::dec<<'"';}
                out<<"],\"attackBytes\":";if(e.attackRead)bytes(out,e.attack);else out<<"null";
                out<<",\"hitBytes\":";if(e.hitRead)bytes(out,e.hit);else out<<"null";}
            out<<"}\n";
        }out.flush();
    }
  public:
    // Present samples only changes; the existing bounded writer owns all file I/O.
    void animation(NativeAnimation value,uint32_t actionFlags){
        auto now=GetTickCount64();if(!enabled_ || now-animationTick_<16)return;
        if(value.hero==lastAnimation_.hero && value.module==lastAnimation_.module && value.id==lastAnimation_.id &&
           value.valid==lastAnimation_.valid && actionFlags==lastActionFlags_)return;
        animationTick_=now;lastAnimation_=value;lastActionFlags_=actionFlags;
        Event e{};e.kind=4;e.tick=now;e.thread=GetCurrentThreadId();e.target=value.id;e.recovery=value.valid;
        e.args={value.hero,value.module,0,actionFlags};sc::readMemory(value.module+8,e.args[2]);
        uintptr_t modules{};
        if(sc::readMemory(value.hero+0x1ff8,modules) && sc::readMemory(modules+0x18,e.data))read(e.data,e.before);
        e.after=e.before;queue(e);
    }
    // The host has already matched the complete executable and both setter
    // signatures. Installation happens before any bridge vital writes.
    bool install(uintptr_t base,bool signaturesVerified,const std::filesystem::path &root){
        if(!signaturesVerified || instance_)return false;
        base_=base;started_=GetTickCount64();activity_=started_;instance_=this;
        auto hp=reinterpret_cast<void*>(base+0xbd64e0),pose=reinterpret_cast<void*>(base+0xbd6710);
        if(MH_CreateHook(hp,reinterpret_cast<void*>(hpHook),reinterpret_cast<void**>(&hp_))!=MH_OK){instance_=nullptr;return false;}
        if(MH_CreateHook(pose,reinterpret_cast<void*>(postureHook),reinterpret_cast<void**>(&posture_))!=MH_OK){
            MH_RemoveHook(hp);instance_=nullptr;return false;}
        auto path=root/(L"combat-trace-"+std::to_wstring(GetCurrentProcessId())+L"-"+std::to_wstring(started_)+L".jsonl");
        std::ofstream out(path);if(!out){MH_RemoveHook(hp);MH_RemoveHook(pose);instance_=nullptr;return false;}
        // Entry has been identified in live HP/posture stacks. The assembly
        // probe restores arguments, registers and SIMD state, then tail-jumps
        // to the original. It never assumes a native ApplyDamage return ABI.
        std::array<uint8_t,24> hitEntry{};
        const std::array<uint8_t,24> expectedHit{0x48,0x89,0x5c,0x24,0x08,0x48,0x89,0x6c,0x24,0x10,
            0x48,0x89,0x74,0x24,0x18,0x57,0x48,0x83,0xec,0x30,0x41,0x0f,0xb6,0xe9};
        auto hit=reinterpret_cast<void*>(base+0xb68ff0);
        bool hitReady=sc::readMemory(base+0xb68ff0,hitEntry) && hitEntry==expectedHit &&
            MH_CreateHook(hit,reinterpret_cast<void*>(scCombatHitTraceEntry),&scCombatHitTraceContinue)==MH_OK;
        if(hitReady){scCombatHitTraceCallback=hitCallback;MH_QueueEnableHook(hit);}
        enabled_=true;
        MH_QueueEnableHook(hp);MH_QueueEnableHook(pose);
        if(MH_ApplyQueued()!=MH_OK){enabled_=false;MH_DisableHook(hp);MH_DisableHook(pose);if(hitReady)MH_DisableHook(hit);return false;}
        std::thread([this,root,out=std::move(out)]()mutable{
            out<<"{\"schema\":\"sekiro-vital-trace-v1\",\"process\":"<<GetCurrentProcessId()
               <<",\"hpSetterRva\":\"bd64e0\",\"postureSetterRva\":\"bd6710\",\"hitEntryRva\":\"b68ff0\"}\n";
            while(enabled_.load()){flush(out);std::error_code ec;
                auto now=GetTickCount64();
                if(now-started_>3600000 || now-activity_.load()>600000 || std::filesystem::exists(root/L"combat-trace.stop",ec))enabled_=false;
                Sleep(100);}
            flush(out);out<<"{\"stopped\":true,\"dropped\":"<<dropped_.load()<<"}\n";
        }).detach();
        sc::log("Native combat trace enabled; hit arguments="+std::to_string(hitReady)+
                "; stops after 10 idle minutes / 60 minutes total: "+path.filename().string());return true;
    }
};
} // namespace bridge
