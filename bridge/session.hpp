#pragma once
#include "protocol.hpp"
#include "../include/sekirocraft/math.hpp"
#include <windows.h>
#include <string>

namespace bridge {
constexpr uint32_t mcOwnerCapability = 256, terrainCapability = 512;
constexpr size_t inputSlots = 128, terrainSide = 9;
inline float terrainCellCenter(float value) { return std::floor(value * 2) * .5f + .25f; }
inline bool mouseButtonEvent(UINT message, uint32_t &button, uint32_t &action) {
    switch (message) {
    case WM_LBUTTONDOWN: case WM_LBUTTONDBLCLK: button=0;action=1;return true;
    case WM_LBUTTONUP: button=0;action=0;return true;
    case WM_RBUTTONDOWN: case WM_RBUTTONDBLCLK: button=1;action=1;return true;
    case WM_RBUTTONUP: button=1;action=0;return true;
    case WM_MBUTTONDOWN: case WM_MBUTTONDBLCLK: button=2;action=1;return true;
    case WM_MBUTTONUP: button=2;action=0;return true;
    default: return false;
    }
}
inline std::array<float,2> guiPosition(float x,float y,float width,float height,float aspect) {
    if(width<=0 || height<=0 || !std::isfinite(aspect) || aspect<=0)return {};
    float sw=std::min(width,height*aspect),sh=sw/aspect;
    return {(x-(width-sw)/2)/sw,(y-(height-sh)/2)/sh};
}
struct InputEvent {
    uint32_t kind{}, code{}, action{}, mods{}; // 1 key, 2 button, 3 scroll, 4 text
    float x{}, y{};
    int32_t amount{};
    uint32_t reserved{};
    uint64_t tick{}, guiGeneration{};
};
struct alignas(8) InputPacket {
    uint64_t tick{}, epoch{}, sequence{};
    int64_t dx{}, dy{}; // cumulative DirectInput mouse counts, not cursor position
    float mouseX{},mouseY{};
    uint64_t guiGeneration{},pointerTick{};
    std::array<InputEvent, inputSlots> events{};
};
struct alignas(8) PlayerPacket {
    uint64_t sequence{}, tick{}, controlTick{}, epoch{};
    uint32_t flags{}; // 1 active MC owner, 2 airborne flight, 4 GUI open
    float yaw{}; // MC camera yaw in degrees, keeps a right vector at vertical pitch
    sc::Vec3 position{}, eye{}, forward{0,0,1};
    float fov{}, aspect{}, nearZ{}, farZ{};
    sc::Vec3 velocity{};
};
static_assert(sizeof(InputEvent)==48 && sizeof(InputPacket) == 6208 && sizeof(PlayerPacket) == 104);
struct GuiDisplay {
    uint64_t generation{},tick{};
    float x{},y{},width{},height{},surfaceWidth{},surfaceHeight{};
    static GuiDisplay fit(const FrameMeta &m,float width,float height,uint64_t now) {
        if(width<=0 || height<=0 || !std::isfinite(m.aspect) || m.aspect<=0)return {};
        auto w=std::min(width,height*m.aspect),h=w/m.aspect;
        return {m.guiGeneration,now,(width-w)/2,(height-h)/2,w,h,width,height};
    }
    std::array<float,2> point(float px,float py,float clientWidth,float clientHeight)const {
        if(!generation || width<=0 || height<=0 || clientWidth<=0 || clientHeight<=0)return {-1,-1};
        return {(px*surfaceWidth/clientWidth-x)/width,(py*surfaceHeight/clientHeight-y)/height};
    }
};
struct alignas(8) TerrainPacket {
    uint64_t sequence{}, tick{}, epoch{};
    sc::Vec3 center{};
    float spacing = .5f;
    std::array<float, terrainSide * terrainSide> heights{};
    std::array<uint8_t, terrainSide * terrainSide> hits{};
    uint8_t padding[3]{};
};
static_assert(sizeof(TerrainPacket) == 448);
inline bool validPlayer(const PlayerPacket &p) {
    if (!p.sequence || !p.epoch || p.flags & ~7u || !sc::finite(p.position) ||
        !sc::finite(p.velocity) || !std::isfinite(p.yaw) || std::abs(p.yaw)>360 ||
        sc::length(p.velocity)>100 || sc::length(p.position)>150000 ||
        sc::length(p.eye-p.position)>10) return false;
    float yaw=p.yaw*3.14159265358979323846f/180;
    sc::Vec3 right{-std::cos(yaw),0,std::sin(yaw)},horizontal{-std::sin(yaw),0,-std::cos(yaw)};
    if(std::abs(sc::dot(p.forward,right))>.005f || sc::dot(p.forward,horizontal)<-.001f)return false;
    FrameMeta m; m.sequence=p.sequence; m.epoch=p.epoch; m.width=1; m.height=1;
    std::memcpy(m.eye,&p.eye,12); std::memcpy(m.forward,&p.forward,12);
    m.fovY=p.fov; m.aspect=p.aspect; m.nearZ=p.nearZ; m.farZ=p.farZ;
    return valid(m);
}
inline sc::Mat4 playerCameraPose(const PlayerPacket &p) {
    float yaw=p.yaw*3.14159265358979323846f/180;
    auto forward=sc::normalize(p.forward);
    sc::Vec3 right{-std::cos(yaw),0,std::sin(yaw)};
    sc::Vec3 up{forward.y*right.z-forward.z*right.y,forward.z*right.x-forward.x*right.z,
                forward.x*right.y-forward.y*right.x};
    sc::Mat4 pose{};
    pose.at(0,0)=right.x;pose.at(0,1)=right.y;pose.at(0,2)=right.z;
    pose.at(1,0)=up.x;pose.at(1,1)=up.y;pose.at(1,2)=up.z;
    pose.at(2,0)=forward.x;pose.at(2,1)=forward.y;pose.at(2,2)=forward.z;
    pose.at(3,0)=p.eye.x;pose.at(3,1)=p.eye.y;pose.at(3,2)=p.eye.z;pose.at(3,3)=1;
    return pose;
}
inline sc::Mat4 frameCameraPose(const FrameMeta &m) {
    PlayerPacket camera;
    std::memcpy(&camera.eye,m.eye,12);std::memcpy(&camera.forward,m.forward,12);
    camera.yaw=(m.flags & ExplicitYaw) ? std::bit_cast<float>(m.reserved) :
        std::atan2(-m.forward[0],-m.forward[2])*180/3.14159265358979323846f;
    return playerCameraPose(camera);
}
inline bool validInput(const InputPacket &p) {
    if (!p.tick || !p.epoch || !std::isfinite(p.mouseX) || !std::isfinite(p.mouseY)) return false;
    auto first=p.sequence>inputSlots?p.sequence-inputSlots:0;
    for (auto i=first;i<p.sequence;++i) {
        const auto &e=p.events[i%inputSlots];
        if (e.kind<1 || e.kind>4 || !std::isfinite(e.x) || !std::isfinite(e.y) ||
            (e.kind==1 && (e.code>=256 || e.action>2)) ||
            (e.kind==2 && (e.code>2 || e.action>1)) || (e.mods & ~7u)) return false;
    }
    return true;
}
inline bool validTerrain(const TerrainPacket &p) {
    if (!p.sequence || !p.epoch || !sc::finite(p.center) || p.spacing!=.5f) return false;
    for (size_t i=0;i<p.heights.size();++i)
        if (p.hits[i]>1 || (p.hits[i] && (!std::isfinite(p.heights[i]) ||
            std::abs(p.heights[i]-p.center.y)>12))) return false;
    return true;
}
template<class T> class SnapshotChannel {
    HANDLE mapping_{}, mutex_{}; T *value_{};
    struct Lock {
        HANDLE held{};
        explicit Lock(HANDLE h) { auto r=h?WaitForSingleObject(h,0):WAIT_FAILED;
            if(r==WAIT_OBJECT_0 || r==WAIT_ABANDONED) held=h; }
        ~Lock(){if(held)ReleaseMutex(held);} explicit operator bool()const{return held!=nullptr;}
    };
  public:
    ~SnapshotChannel(){close();}
    bool open(const std::wstring &channel,const wchar_t *suffix) {
        close(); auto name=L"Local\\SekiroBridge-"+channel+L"-"+suffix;
        mutex_=CreateMutexW(nullptr,FALSE,(name+L"-lock").c_str());
        mapping_=CreateFileMappingW(INVALID_HANDLE_VALUE,nullptr,PAGE_READWRITE,0,sizeof(T),name.c_str());
        if(mapping_ && mutex_) value_=static_cast<T*>(MapViewOfFile(mapping_,FILE_MAP_ALL_ACCESS,0,0,sizeof(T)));
        if(!value_){close();return false;} return true;
    }
    void close(){if(value_)UnmapViewOfFile(value_);if(mapping_)CloseHandle(mapping_);if(mutex_)CloseHandle(mutex_);
        value_=nullptr;mapping_=mutex_=nullptr;}
    bool write(const T &v){if(!value_)return false;Lock lock(mutex_);if(!lock)return false;
        std::memcpy(value_,&v,sizeof(T));return true;}
    bool read(T &v){if(!value_)return false;Lock lock(mutex_);if(!lock)return false;
        std::memcpy(&v,value_,sizeof(T));return true;}
};
} // namespace bridge
