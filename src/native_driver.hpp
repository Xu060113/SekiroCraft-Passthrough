#pragma once
#include "MinHook.h"
#include "sekirocraft/host.hpp"
#include "../bridge/shared_memory.hpp"
#include <mutex>
#include <atomic>
extern "C" void scCameraEntry();
extern "C" void *scCameraContinue;
extern "C" void (*scCameraHandler)(uintptr_t) noexcept;
namespace bridge {
class NativeDriver {
    uintptr_t base_{};
    SharedMemory *memory_{};
    std::mutex mutex_;
    Control control_{};
    PlayerPacket player_{};
    bool enabled_{}, installed_{}, rayReady_{};
    uint64_t retry_{}, terrainTick_{}, terrainSequence_{};
    static inline NativeDriver *instance_{};
    using CastRay=bool(*)(uintptr_t,uint32_t,const float*,const float*,float*,float*,float*,uintptr_t*);
    static void cameraCallback(uintptr_t camera) noexcept {if(instance_)instance_->camera(camera);}
    bool current(uint64_t now) {
        PlayerPacket next;
        if(memory_->player.read(next) && validPlayer(next))player_=next;
        return enabled_ && (player_.flags&1) && player_.epoch==control_.epoch &&
            fresh(now,player_.tick,150) && fresh(now,player_.controlTick) &&
            sc::length(player_.position-sc::Vec3{control_.player[0],control_.player[1],control_.player[2]})<5;
    }
    void terrain(uint64_t now,sc::Vec3 center) {
        if(!rayReady_ || now-terrainTick_<75)return;
        terrainTick_=now;
        uintptr_t manager{},world{};
        if(!sc::readMemory(base_+0x3d6d640,manager) || !sc::readMemory(manager+0x98,world) || world<65536)return;
        TerrainPacket p; p.sequence=++terrainSequence_; p.tick=now; p.epoch=control_.epoch;p.center=center;
        auto cast=reinterpret_cast<CastRay>(base_+0x94cc50);
        // SekiroTool identifies this routine; the loaded 1.06 disassembly confirms
        // stack arg 5 is the aligned world hit position (the engine's caller at
        // 0xefbbcc subtracts the start from it); arg 6 is the normal.
        // Run on the player physics thread, never Present or the IPC worker.
        for(int z=0;z<9;++z)for(int x=0;x<9;++x){
            alignas(16) float start[4]{center.x+(x-4)*.5f,center.y+1.25f,center.z+(z-4)*.5f,1};
            alignas(16) float direction[4]{0,-10,0,0},normal[4]{},hit[4]{};
            float fraction{};uintptr_t object{};
            bool found=cast(world,0x4e,start,direction,hit,normal,&fraction,&object);
            auto i=z*9+x;
            if(found && std::isfinite(hit[1]) && std::abs(hit[1]-center.y)<10 &&
               std::isfinite(normal[1]) && normal[1]>.25f){p.hits[i]=1;p.heights[i]=hit[1];}
        }
        if(validTerrain(p)){
            terrainSamples.fetch_add(1);
            terrainHits=std::count(p.hits.begin(),p.hits.end(),uint8_t(1));
            memory_->terrain.write(p);
        }
    }
  public:
    std::atomic<uint64_t> cameraCalls{},controlledMoves{};
    std::atomic<uint64_t> terrainSamples{},terrainHits{};
    void initialize(uintptr_t base,SharedMemory &memory){base_=base;memory_=&memory;instance_=this;
        scCameraHandler=cameraCallback;}
    bool ready()const{return installed_ && rayReady_;}
    void tryInstall(){
        if(installed_ || !base_ || GetTickCount64()-retry_<1000)return;retry_=GetTickCount64();
        const std::array<uint8_t,7> expected{0xc6,0x05,0xfe,0x44,0x62,0x03,0};
        std::array<uint8_t,7> actual{};
        if(!sc::readMemory(base_+0x73537b,actual) || actual!=expected)return;
        const std::array<uint8_t,16> ray{0x48,0x8b,0xc4,0x55,0x56,0x57,0x41,0x54,0x41,0x55,0x41,0x56,0x41,0x57,0x48,0x8d};
        std::array<uint8_t,16> code{};
        rayReady_=sc::readMemory(base_+0x94cc50,code) && code==ray;
        if(!rayReady_)return;
        auto target=reinterpret_cast<void*>(base_+0x73537b);
        if(MH_CreateHook(target,reinterpret_cast<void*>(scCameraEntry),&scCameraContinue)!=MH_OK)return;
        installed_=MH_EnableHook(target)==MH_OK;
        if(!installed_)MH_RemoveHook(target);
        sc::log("MC native camera / terrain adapter="+std::to_string(ready()));
    }
    void update(const Control &c,bool enabled){std::unique_lock lock(mutex_,std::try_to_lock);
        if(lock){control_=c;enabled_=enabled && ready();}}
    bool target(sc::Vec3 &position) {
        std::unique_lock lock(mutex_,std::try_to_lock);if(!lock)return false;
        auto now=GetTickCount64();
        if(!enabled_ || !fresh(now,control_.tickMs))return false;
        bool have=current(now);
        terrain(now,have?player_.position:sc::Vec3{control_.player[0],control_.player[1],control_.player[2]});
        if(!have)return false;
        position=player_.position;controlledMoves.fetch_add(1);return true;
    }
    void camera(uintptr_t camera) noexcept {
        std::unique_lock lock(mutex_,std::try_to_lock);if(!lock || !current(GetTickCount64()))return;
        uintptr_t field{},currentCamera{};
        if(!sc::readMemory(base_+0x3d5c0a0,field) || !sc::readMemory(field+0x30,currentCamera) || camera!=currentCamera)return;
        auto pose=playerCameraPose(player_);
        std::array<float,4> lens{player_.fov,player_.aspect,player_.nearZ,player_.farZ};
        SIZE_T wrote{};
        if(WriteProcessMemory(GetCurrentProcess(),reinterpret_cast<void*>(camera+0x10),&pose,sizeof(pose),&wrote) &&
           wrote==sizeof(pose)){
            WriteProcessMemory(GetCurrentProcess(),reinterpret_cast<void*>(camera+0x50),&lens,sizeof(lens),&wrote);
            cameraCalls.fetch_add(1);
        }
        // The normal update rewrites this camera next tick when disabled. No
        // globally frozen camera instructions or NPC transforms are changed.
    }
};
}
