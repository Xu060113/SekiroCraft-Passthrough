#pragma once
#include "MinHook.h"
#include "sekirocraft/host.hpp"
#include "../bridge/shared_memory.hpp"
#include "../bridge/camera_frames.hpp"
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
    CameraFrames cameraFrames_;
    std::atomic<bool> cameraEnabled_{};
    std::atomic<bool> hideNative_{};
    std::atomic<uint64_t> cameraEpoch_{}, controlTick_{};
    bool enabled_{}, installed_{}, rayReady_{};
    uint64_t retry_{}, terrainTick_{}, terrainSequence_{};
    uint64_t terrainEpoch_{};unsigned terrainPatch_{};bool outerPatch_{};
    uint64_t raySequence_{},rayEpoch_{};
    ProjectileHits rayHits_{};
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
    void projectileRays(uint64_t now,uint64_t epoch) {
        if(!rayReady_)return;
        if(rayEpoch_!=epoch){rayEpoch_=epoch;raySequence_=0;rayHits_={};}
        // Retry an unconsumed IPC write without repeating native casts.
        if(rayHits_.sequence && fresh(now,rayHits_.tick))memory_->projectileHits.write(rayHits_);
        ProjectileRays queries;
        if(!memory_->projectileRays.read(queries) || !validRays(queries) || queries.epoch!=epoch ||
            !fresh(now,queries.tick) || queries.sequence==raySequence_)return;
        uintptr_t manager{},world{};
        if(!sc::readMemory(base_+0x3d6d640,manager) || !sc::readMemory(manager+0x98,world) || world<65536)return;
        ProjectileHits result;result.sequence=queries.sequence;result.tick=now;result.epoch=epoch;result.count=queries.count;
        auto cast=reinterpret_cast<CastRay>(base_+0x94cc50);
        for(size_t i=0;i<queries.count;++i){const auto &q=queries.rays[i];auto &r=result.hits[i];r.id=q.id;
            alignas(16) float start[4]{q.start.x,q.start.y,q.start.z,1};
            alignas(16) float delta[4]{q.delta.x,q.delta.y,q.delta.z,0},hit[4]{},normal[4]{};
            float fraction{};uintptr_t object{};
            bool found=sc::length(q.delta)>.00001f && cast(world,0x4e,start,delta,hit,normal,&fraction,&object);
            r.position={hit[0],hit[1],hit[2]};r.normal={normal[0],normal[1],normal[2]};
            // Native filters are not assumed to be a segment clamp: validate the
            // returned point before it is allowed to stop a Minecraft arrow.
            auto length2=sc::dot(q.delta,q.delta);auto t=length2>0?sc::dot(r.position-q.start,q.delta)/length2:0;
            r.hit=found && sc::finite(r.position) && sc::finite(r.normal) && t>=-.001f && t<=1.001f &&
                sc::length(r.position-(q.start+q.delta*t))<.05f && sc::length(r.normal)>.5f && sc::length(r.normal)<1.5f;
            if(!r.hit){r.position={};r.normal={};}
        }
        if(validHits(result)){rayHits_=result;raySequence_=queries.sequence;memory_->projectileHits.write(rayHits_);}
    }
    void terrain(uint64_t now,uint64_t epoch,sc::Vec3 center) {
        if(!rayReady_ || now-terrainTick_<50)return;
        terrainTick_=now;
        if(terrainEpoch_!=epoch){terrainEpoch_=epoch;terrainPatch_=0;outerPatch_=false;}
        uintptr_t manager{},world{};
        if(!sc::readMemory(base_+0x3d6d640,manager) || !sc::readMemory(manager+0x98,world) || world<65536)return;
        center.x=terrainCellCenter(center.x);center.z=terrainCellCenter(center.z);
        // Keep the same 81-ray budget. Refresh the center every 100 ms and rotate
        // eight peripheral patches every 800 ms, within the 1500 ms cell lifetime.
        if(outerPatch_){constexpr int offsets[8][2]{{-4,-4},{0,-4},{4,-4},{-4,0},{4,0},{-4,4},{0,4},{4,4}};
            center.x+=offsets[terrainPatch_][0];center.z+=offsets[terrainPatch_][1];terrainPatch_=(terrainPatch_+1)%8;}
        outerPatch_=!outerPatch_;
        TerrainPacket p; p.sequence=++terrainSequence_; p.tick=now; p.epoch=epoch;p.center=center;
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
    std::atomic<uint64_t> cameraCalls{},controlledMoves{},cameraFrameSequence{};
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
    void receiveFrame(std::shared_ptr<Frame> frame){cameraFrames_.receive(std::move(frame));}
    void takeCameraFrame(std::shared_ptr<Frame> &frame){cameraFrames_.takeDisplayed(frame);}
    void update(const Control &c,bool enabled,bool hideNative=false){std::unique_lock lock(mutex_,std::try_to_lock);
        if(lock){control_=c;enabled_=enabled && ready();
            cameraEpoch_=c.epoch;controlTick_=c.tickMs;cameraEnabled_=enabled_;hideNative_=hideNative;}}
    bool target(sc::Vec3 &position,float *angle=nullptr) {
        std::unique_lock lock(mutex_,std::try_to_lock);if(!lock)return false;
        auto now=GetTickCount64();
        if(!enabled_ || !fresh(now,control_.tickMs))return false;
        bool have=current(now);
        auto center=have?player_.position:sc::Vec3{control_.player[0],control_.player[1],control_.player[2]};
        auto epoch=control_.epoch;
        if(have)position=player_.position;
        if(have && angle){
            float yaw=player_.yaw*3.14159265358979323846f/180;
            *angle=std::atan2(-std::sin(yaw),-std::cos(yaw));
        }
        // Ray work cannot hold the state mutex and make a camera/control update
        // miss its zero-wait lock and fall back to the native wolf camera.
        lock.unlock();
        projectileRays(now,epoch);
        terrain(now,epoch,center);
        if(have)controlledMoves.fetch_add(1);
        return have;
    }
    void camera(uintptr_t camera) noexcept {
        uintptr_t field{},currentCamera{};
        if(!sc::readMemory(base_+0x3d5c0a0,field) || !sc::readMemory(field+0x30,currentCamera) || camera!=currentCamera)return;
        auto now=GetTickCount64();
        // Reassert before scene rendering, after native combat/character updates
        // may have enabled Draw. Present alone is too late for that frame.
        if(hideNative_ && fresh(now,controlTick_)){
            uintptr_t root{},hero{};uint8_t bits{};
            if(sc::readMemory(base_+0x3d7a1e0,root) && sc::readMemory(root+0x88,hero) &&
               sc::readMemory(hero+0x1a11,bits) && (bits&8)){
                bits&=~8u;SIZE_T wrote{};
                WriteProcessMemory(GetCurrentProcess(),reinterpret_cast<void*>(hero+0x1a11),&bits,1,&wrote);
            }
        }
        auto frame=cameraFrames_.select(cameraEpoch_.load(),now);
        if(!cameraEnabled_ || !fresh(now,controlTick_) || !frame){cameraFrames_.applied({});return;}
        const auto &m=frame->meta;
        auto pose=frameCameraPose(m);
        std::array<float,4> lens{m.fovY,m.aspect,m.nearZ,m.farZ};
        SIZE_T wrote{};
        if(WriteProcessMemory(GetCurrentProcess(),reinterpret_cast<void*>(camera+0x10),&pose,sizeof(pose),&wrote) &&
           wrote==sizeof(pose)){
            if(WriteProcessMemory(GetCurrentProcess(),reinterpret_cast<void*>(camera+0x50),&lens,sizeof(lens),&wrote) && wrote==sizeof(lens)){
                cameraCalls.fetch_add(1);cameraFrameSequence=m.sequence;
                cameraFrames_.applied(std::move(frame));
                return;
            }
        }
        cameraFrames_.applied({});
        // The normal update rewrites this camera next tick when disabled. No
        // globally frozen camera instructions or NPC transforms are changed.
    }
};
}
