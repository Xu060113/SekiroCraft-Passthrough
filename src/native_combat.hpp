#pragma once
#include "sekirocraft/host.hpp"
#include "../bridge/shared_memory.hpp"
#include "combat_trace.hpp"
#include "native_hit.hpp"
#include <atomic>
#include <mutex>
namespace bridge {
// Compatibility HP/posture writes use the verified physics callback. Opt-in
// native hits drain only in the separately fingerprinted AttackManager update.
class NativeCombatAdapter {
    struct Actor {uintptr_t chr{},data{},physics{};uint32_t handle{};uint64_t id{},seen{};
        uint64_t stage{1},attemptedStage{};int32_t lastNode{-1},lastHp{-1};};
    struct Vital {uintptr_t chr{},data{};uint32_t handle{};int32_t hp{},maxHp{},posture{},maxPosture{},bossNode{};uint8_t bits{};sc::Vec3 position{};uint8_t team{};};
    uintptr_t base_{};SharedMemory *memory_{};bool ready_{},postureReady_{};
    std::mutex mutex_; std::array<Actor,actorSlots> actors_{};
    uint64_t nextId_{},heroId_{},epoch_{},sequence_{},publishAt_{},session_{},ackCommand_{};
    uintptr_t hero_{},heroData_{}; uint32_t heroHandle_{};
    double damage_{},heal_{};
    bool dead_{};
    CombatReport report_{};
    NativeHitBackend nativeHit_;
    std::atomic<bool> nativeHits_{};
    std::atomic<bool> phaseFinishes_{};
    std::atomic<bool> paused_{};
    uint64_t controlAt_{};bool combatActive_{};
    uintptr_t ownedHero_{},ownedData_{};uint32_t ownedHandle_{};uint8_t oldNoDamage_{};bool owned_{};
    using Lookup=uintptr_t(*)(uintptr_t,uint32_t);
    using SetHp=void(*)(uintptr_t,int32_t);
    using SetPosture=void(*)(uintptr_t,int32_t,uint8_t);
    template<size_t N> bool code(uintptr_t rva,const std::array<uint8_t,N>&expected){std::array<uint8_t,N> a{};
        return sc::readMemory(base_+rva,a) && a==expected;}
    uintptr_t hero()const{uintptr_t root{},p{};return sc::readMemory(base_+0x3d7a1e0,root)&&sc::readMemory(root+0x88,p)?p:0;}
    bool read(uintptr_t chr,Vital &v)const {
        uintptr_t modules{},physics{},vt{},owner{};
        v={};v.chr=chr;
        if(!sc::readMemory(chr,vt) || vt<base_ || vt>=base_+70066176 ||
           !sc::readMemory(chr+8,v.handle) || v.handle==0xffffffff ||
           !sc::readMemory(chr+0x1ff8,modules) || !sc::readMemory(modules+0x18,v.data) ||
           !sc::readMemory(modules+0x68,physics) || !sc::readMemory(physics+8,owner) || owner!=chr ||
           !sc::readMemory(physics+0x80,v.position) || !sc::finite(v.position) || sc::length(v.position)>150000 ||
           !sc::readMemory(v.data+0x130,v.hp) || !sc::readMemory(v.data+0x134,v.maxHp) ||
           !sc::readMemory(v.data+0x228,v.bits) || !sc::readMemory(chr+0x74,v.team))return false;
        if(postureReady_ && sc::readMemory(v.data+0x148,v.posture) && sc::readMemory(v.data+0x14c,v.maxPosture) &&
           v.maxPosture>0 && v.maxPosture<=10000000 && v.posture>=-100 && v.posture<=v.maxPosture){
            // The engine permits a negative remainder while posture is broken.
            // Publish a full gauge without discarding its maximum or phase data.
            v.posture=std::max(0,v.posture);
        }
        else v.posture=v.maxPosture=0;
        int32_t nodes{};
        if(postureReady_ && sc::readMemory(v.data+0x25c,nodes) && nodes>=0 && nodes<=32)v.bossNode=nodes;
        return v.maxHp>0 && v.maxHp<=10000000 && v.hp>=0 && v.hp<=v.maxHp;
    }
    bool resolves(uintptr_t chr,uint32_t handle)const {
        uintptr_t root{},list{};int32_t groups{};
        if(!sc::readMemory(base_+0x3d7a1e0,root) || !sc::readMemory(root+0x10,list) ||
           !sc::readMemory(list+0x18,groups) || groups<0 || groups>1024)return false;
        return reinterpret_cast<Lookup>(base_+0xa4a050)(root,handle)==chr;
    }
    bool setHp(const Vital &v,int hp){Vital current;
        if(!resolves(v.chr,v.handle) || !read(v.chr,current) || current.data!=v.data ||
           current.handle!=v.handle || current.hp!=v.hp || current.maxHp!=v.maxHp)return false;
        BridgeVitalWrite write;
        reinterpret_cast<SetHp>(base_+0xbd64e0)(v.data,std::clamp(hp,0,v.maxHp));return true;}
    static void refreshStage(Actor &a,const Vital &v){
        if((a.lastNode>=0 && a.lastNode!=v.bossNode) || (a.lastHp==0 && v.hp>0))++a.stage;
        a.lastNode=v.bossNode;a.lastHp=v.hp;
    }
    void commands(const Vital &player,bool native){
        auto first=report_.command>damageSlots?report_.command-damageSlots:0;
        ackCommand_=std::max(ackCommand_,first);
        for(auto seq=ackCommand_+1;seq<=report_.command;++seq){const auto &cmd=report_.commands[(seq-1)%damageSlots];
            bool success=false;
            for(auto &a:actors_)if(a.id==cmd.actor && a.chr && resolves(a.chr,a.handle)){
                Vital v;if(read(a.chr,v) && v.data==a.data && v.handle==a.handle &&
                   sc::length(v.position-player.position)<64 && (v.hp>0 || (native && v.bossNode>0)) && !(v.bits&8)){
                    refreshStage(a,v);
                    if(cmd.stage!=a.stage){ackCommand_=seq;break;}
                    if(native){
                        success=v.hp==0 || nativeHit_.dispatch(player.chr,v.chr,player.position,v.position,v.maxHp,v.maxPosture,cmd);
                        Vital after;
                        bool remote=cmd.kind==1 || cmd.kind==2 || cmd.kind==3 || cmd.kind==5;
                        if(success && remote && phaseFinishes_ && read(a.chr,after) && resolves(a.chr,a.handle) &&
                           after.handle==a.handle && after.data==a.data){
                            refreshStage(a,after);
                            bool depleted=after.hp<=1 || (after.maxPosture>0 && after.posture==0 && !(after.bits&16));
                            if(a.stage==cmd.stage && a.attemptedStage!=a.stage && after.bossNode>0 &&
                               depleted && !(after.bits&12)){
                                a.attemptedStage=a.stage;
                                if(nativeHit_.dispatch(player.chr,after.chr,player.position,after.position,after.maxHp,after.maxPosture,cmd,true)){
                                    phaseDispatched.fetch_add(1);Vital done;
                                    if(read(a.chr,done) && done.data==a.data && done.handle==a.handle && done.bossNode==after.bossNode-1){
                                        phaseConfirmed.fetch_add(1);refreshStage(a,done);
                                    }else phaseRejected.fetch_add(1);
                                }else phaseRejected.fetch_add(1);
                            }
                        }
                    }
                    else {
                        auto target=damageHp(v.hp,v.maxHp,cmd.amount/20.,0);
                        // A Boss can retain native nodes without NoDeath bit 4.
                        // Compatibility damage must not bypass its finisher.
                        if(v.bossNode>0)target=std::max(1,target);
                        success=setHp(v,target);
                        if(success && postureDamage(v,cmd.amount))appliedPosture.fetch_add(1);
                    }
                }break;}
            (success?applied:rejected).fetch_add(1);ackCommand_=seq;
            if(native && success)nativeDispatched.fetch_add(1);
        }
    }
    bool postureDamage(const Vital &v,float amount){Vital current;
        if(!postureReady_ || !resolves(v.chr,v.handle) || !read(v.chr,current) ||
           current.data!=v.data || current.handle!=v.handle || current.hp==0 ||
           current.maxPosture<=0 || current.posture<=0 || (current.bits&24))return false;
        int target=damageHp(current.posture,current.maxPosture,amount/20.,0);
        BridgeVitalWrite write;
        reinterpret_cast<SetPosture>(base_+0xbd6710)(current.data,target,0);return true;
    }
    void release(){
        if(owned_ && hero()==ownedHero_){Vital v;
            if(read(ownedHero_,v) && v.data==ownedData_ && v.handle==ownedHandle_ && (v.bits&8)){
                uint8_t bits=(v.bits&~8u)|oldNoDamage_;SIZE_T n{};
                WriteProcessMemory(GetCurrentProcess(),reinterpret_cast<void*>(v.data+0x228),&bits,1,&n);
            }}
        owned_=false;ownedHero_=ownedData_=0;
    }
    void protect(const Vital &v,bool on){
        if(owned_ && (ownedHero_!=v.chr || ownedData_!=v.data || ownedHandle_!=v.handle))release();
        if(!on){release();return;}
        if(!owned_){ownedHero_=v.chr;ownedData_=v.data;ownedHandle_=v.handle;oldNoDamage_=v.bits&8;owned_=true;}
        if(!(v.bits&8)){uint8_t bits=v.bits|8;SIZE_T n{};
            WriteProcessMemory(GetCurrentProcess(),reinterpret_cast<void*>(v.data+0x228),&bits,1,&n);}
    }
  public:
    std::atomic<uint64_t> observed{},applied{},rejected{},publishedActors{};
    std::atomic<uint64_t> appliedPosture{};
    std::atomic<uint64_t> phaseDispatched{},phaseConfirmed{},phaseRejected{};
    std::atomic<uint64_t> nativeDispatched{},gameThreadCalls{};
    void initialize(uintptr_t base,SharedMemory &memory){base_=base;memory_=&memory;
        ready_=base && code(0xbd64e0,std::array<uint8_t,16>{0x48,0x89,0x5c,0x24,0x18,0x89,0x54,0x24,0x10,0x57,0x48,0x83,0xec,0x20,0x8b,0xb9}) &&
            code(0xa4a050,std::array<uint8_t,16>{0x48,0x83,0xec,0x28,0xe8,0x37,0xff,0xff,0xff,0x48,0x85,0xc0,0x74,0x08,0x48,0x8b});
        postureReady_=ready_ && code(0xbd6710,std::array<uint8_t,16>{0x48,0x89,0x6c,0x24,0x18,0x48,0x89,0x74,0x24,0x20,0x57,0x48,0x83,0xec,0x20,0x41}) &&
            code(0xbd679a,std::array<uint8_t,8>{0x89,0x87,0x48,0x01,0x00,0x00,0x85,0xdb});
        sc::log("Native HP / posture signatures="+std::to_string(ready_)+"/"+std::to_string(postureReady_));}
    bool ready()const{return ready_;}
    bool postureReady()const{return postureReady_;}
    bool prepareNativeHits(){return ready_ && nativeHit_.initialize(base_);}
    void enableNativeHits(bool on){nativeHits_.store(on,std::memory_order_release);}
    bool nativeHits()const{return nativeHits_.load(std::memory_order_acquire);}
    bool enablePhaseFinishes(bool on){phaseFinishes_=on && nativeHit_.phaseReady();return phaseFinishes_;}
    void pause(bool on){paused_=on;}
    uint32_t nativeHitFailure()const{return nativeHit_.lastFailure();}
    void gameTick(uintptr_t manager,float dt)noexcept {
        if(paused_ || !nativeHits() || !std::isfinite(dt) || dt<=0 || dt>.25f)return;
        uintptr_t actual{};if(!sc::readMemory(base_+0x3d77ef0,actual) || actual!=manager || !actual)return;
        gameThreadCalls.fetch_add(1);
        std::unique_lock lock(mutex_,std::try_to_lock);if(!lock)return;
        auto now=GetTickCount64();Vital player;
        if(!combatActive_ || !fresh(now,controlAt_) || report_.epoch!=epoch_ || report_.hero!=heroId_ ||
           report_.session!=session_ || !fresh(now,report_.tick) || !read(hero(),player) || player.hp<=0 ||
           player.chr!=hero_ || player.data!=heroData_ || player.handle!=heroHandle_)return;
        commands(player,true);
    }
    // Present may request a release; zero-wait locking never stalls rendering.
    void releaseIfInactive(bool on){if(on)return;std::unique_lock lock(mutex_,std::try_to_lock);if(lock)release();}
    void observe(uintptr_t physics)noexcept{
        if(!ready_)return;uintptr_t chr{},modules{},actual{};
        if(!sc::readMemory(physics+8,chr) || !sc::readMemory(chr+0x1ff8,modules) ||
           !sc::readMemory(modules+0x68,actual) || actual!=physics || chr==hero())return;
        uintptr_t data{};uint32_t handle{};
        if(!sc::readMemory(modules+0x18,data) || data<65536 || !sc::readMemory(chr+8,handle) || handle==0xffffffff)return;
        std::unique_lock lock(mutex_,std::try_to_lock);if(!lock)return;
        auto now=GetTickCount64();Actor *slot=nullptr;
        for(auto &a:actors_){if(a.chr==chr && a.data==data && a.handle==handle){slot=&a;break;}}
        if(!slot){for(auto &a:actors_)if(!a.chr || now-a.seen>2000){slot=&a;break;}}
        if(!slot)return;
        if(slot->chr!=chr || slot->data!=data || slot->handle!=handle)*slot={chr,data,physics,handle,++nextId_,now};
        slot->seen=now;observed.fetch_add(1);
    }
    void tick(uint64_t epoch,bool active)noexcept {
        if(!ready_)return;std::unique_lock lock(mutex_,std::try_to_lock);if(!lock)return;
        auto now=GetTickCount64();Vital player;
        controlAt_=now;combatActive_=active;
        if(epoch_!=epoch){release();epoch_=epoch;hero_=0;actors_={};session_=ackCommand_=0;damage_=heal_=0;}
        if(!read(hero(),player)){release();return;}
        if(hero_!=player.chr || heroData_!=player.data || heroHandle_!=player.handle){release();
            hero_=player.chr;heroData_=player.data;heroHandle_=player.handle;heroId_=++nextId_;
            session_=ackCommand_=0;damage_=heal_=0;dead_=false;}
        if(dead_ && player.hp>0){
            // Resurrection may retain ChrIns/data/handle. Give the new life a
            // new identity so pre-death reports and queued hits cannot replay.
            heroId_=++nextId_;session_=ackCommand_=0;damage_=heal_=0;
        }
        dead_=player.hp==0;
        CombatReport next;if(memory_->combatReport.read(next) && validCombat(next))report_=next;
        bool peer=active && player.hp>0 && report_.epoch==epoch && report_.hero==heroId_ && fresh(now,report_.tick);
        protect(player,peer && report_.invulnerable);
        if(peer){
            if(session_!=report_.session){session_=report_.session;ackCommand_=0;damage_=heal_=0;}
            if(report_.damage>=damage_ && report_.heal>=heal_){
                auto target=damageHp(player.hp,player.maxHp,report_.invulnerable?0:report_.damage-damage_,report_.heal-heal_);
                if(target==player.hp || setHp(player,target)){damage_=report_.damage;heal_=report_.heal;read(hero_,player);}
            }
            if(!nativeHits() && !paused_)commands(player,false);
        }
        if(now-publishAt_<50)return;publishAt_=now;
        CombatState out;out.sequence=++sequence_;out.tick=now;out.epoch=epoch;out.hero=heroId_;
        out.hp=player.hp;out.maxHp=player.maxHp;out.flags=1|(peer && report_.invulnerable?2:0)|(postureReady_?4:0);
        out.posture=player.posture;out.maxPosture=player.maxPosture;
        out.ackSession=session_;out.ackDamage=damage_;out.ackHeal=heal_;out.ackCommand=ackCommand_;
        if(active)for(auto &a:actors_){if(!a.chr)continue;
            // A stationary actor may stop receiving movement callbacks. Keep it
            // while the current handle table still resolves the same entity.
            if(!resolves(a.chr,a.handle)){a={};continue;}
            Vital v;if(!read(a.chr,v) || v.data!=a.data || v.handle!=a.handle || sc::length(v.position-player.position)>64)continue;
            refreshStage(a,v);
            auto &p=out.actors[out.count++];p.id=a.id;p.position=v.position;p.hp=v.hp;p.maxHp=v.maxHp;p.team=v.team;
            // EMEDF Enemy, StrongEnemy and hostile NPC teams; allies remain
            // individually attackable but never attract the added monster goal.
            bool hostile=v.team==6 || v.team==7 || v.team==9 || v.team==13 || v.team==21 ||
                v.team==23 || v.team==24 || v.team==27;
            p.flags=(hostile?1:0)|((v.bits&8)?2:0)|((v.bits&4)?4:0)|((v.bits&16)?8:0);
            p.posture=v.posture;p.maxPosture=v.maxPosture;p.bossNode=v.bossNode;
            p.stage=a.stage;
        }
        publishedActors=out.count;memory_->combatState.write(out);
    }
};
} // namespace bridge
