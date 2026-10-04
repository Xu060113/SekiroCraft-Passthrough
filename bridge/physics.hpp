#pragma once
#include "protocol.hpp"
#include "session.hpp"
#include "../include/sekirocraft/geometry.hpp"
#include <windows.h>
#include <cstring>
#include <algorithm>
#include <string>

namespace bridge {
constexpr uint32_t constraintCapability = 64, flightCapability = 128;
constexpr size_t maxShapes = 4096;
struct Shape { sc::Vec3 min, max; };
struct alignas(8) PhysicsPacket {
    uint64_t tick{}, controlTick{}, epoch{};
    uint32_t flags{}, count{}; // 1: shapes complete, 2: flying, 4: creative
    sc::Vec3 origin{};
    float radius = .3f, height = 1.8f;
    uint32_t reserved{};
    uint64_t sequence{};
    Shape coverage{}; // Fully sampled region, in native coordinates.
    std::array<Shape, maxShapes> shapes{};
};
static_assert(sizeof(Shape) == 24 && offsetof(PhysicsPacket, shapes) == 88);
static_assert(sizeof(PhysicsPacket) == 98392);
inline bool validPhysics(const PhysicsPacket &p) {
    if (!p.sequence || !p.epoch || p.flags & ~7u || p.count > maxShapes || !sc::finite(p.origin) ||
        p.radius < .02f || p.radius > 3 || !std::isfinite(p.radius) || p.height < .1f ||
        p.height > 20 || !std::isfinite(p.height)) return false;
    for (size_t i = 0; i < p.count; ++i) {
        const auto &s = p.shapes[i];
        if (!sc::finite(s.min) || !sc::finite(s.max) || s.min.x >= s.max.x ||
            s.min.y >= s.max.y || s.min.z >= s.max.z || sc::length(s.min - p.origin) > 100 ||
            sc::length(s.max - p.origin) > 100) return false;
    }
    if (!sc::finite(p.coverage.min) || !sc::finite(p.coverage.max)) return false;
    if (p.flags & 1) for (int axis=0;axis<3;++axis) {
        auto coord=[](sc::Vec3 v,int a){return a==0?v.x:a==1?v.y:v.z;};
        float low=coord(p.coverage.min,axis),high=coord(p.coverage.max,axis);
        if(low>=high || high-low>100) return false;
    }
    return true;
}
class PhysicsChannel {
    HANDLE mapping_{}, mutex_{};
    PhysicsPacket *packet_{};
    struct Lock {
        HANDLE held{};
        explicit Lock(HANDLE h) { auto r = h ? WaitForSingleObject(h, 0) : WAIT_FAILED;
            if (r == WAIT_OBJECT_0 || r == WAIT_ABANDONED) held = h; }
        ~Lock() { if (held) ReleaseMutex(held); }
        explicit operator bool() const { return held != nullptr; }
    };
  public:
    ~PhysicsChannel() { close(); }
    bool open(const std::wstring &channel) {
        close();
        auto name = L"Local\\SekiroBridge-" + channel + L"-physics-v2";
        mutex_ = CreateMutexW(nullptr, FALSE, (name + L"-lock").c_str());
        mapping_ = CreateFileMappingW(INVALID_HANDLE_VALUE, nullptr, PAGE_READWRITE, 0,
                                     sizeof(PhysicsPacket), name.c_str());
        if (!mapping_ || !mutex_) { close(); return false; }
        packet_ = static_cast<PhysicsPacket *>(MapViewOfFile(mapping_, FILE_MAP_ALL_ACCESS, 0, 0,
                                                            sizeof(PhysicsPacket)));
        return packet_ != nullptr;
    }
    void close() {
        if (packet_) UnmapViewOfFile(packet_);
        if (mapping_) CloseHandle(mapping_);
        if (mutex_) CloseHandle(mutex_);
        packet_ = nullptr; mapping_ = mutex_ = nullptr;
    }
    bool write(const PhysicsPacket &p) {
        if (!packet_ || !validPhysics(p)) return false;
        Lock lock(mutex_); if (!lock) return false;
        std::memcpy(packet_, &p, sizeof(p)); return true;
    }
    bool read(PhysicsPacket &p) {
        if (!packet_) return false;
        Lock lock(mutex_); if (!lock) return false;
        PhysicsPacket next; std::memcpy(&next, packet_, sizeof(next));
        if (!validPhysics(next)) return false;
        p = next; return true;
    }
};
// Sweep the player's bounding box through true MC voxel boxes. Y first permits
// landing, then the horizontal axes slide along walls. Native terrain remains
// the engine's responsibility; these shapes are never inserted into Havok.
inline bool coversBody(const PhysicsPacket &p,sc::Vec3 position,float radius,float height) {
    return position.x-radius>=p.coverage.min.x && position.x+radius<=p.coverage.max.x &&
           position.y>=p.coverage.min.y && position.y+height<=p.coverage.max.y &&
           position.z-radius>=p.coverage.min.z && position.z+radius<=p.coverage.max.z;
}
inline sc::Vec3 constrain(sc::Vec3 from, sc::Vec3 to, const PhysicsPacket &p,
                          float radius,float height) {
    Shape body{{from.x - radius, from.y, from.z - radius},
               {from.x + radius, from.y + height, from.z + radius}};
    sc::Vec3 delta = to - from;
    float d[3]{delta.x, delta.y, delta.z};
    auto coord = [](sc::Vec3 &v, int axis) -> float & { return axis == 0 ? v.x : axis == 1 ? v.y : v.z; };
    for (int axis : {1, 0, 2}) {
        int a = (axis + 1) % 3, b = (axis + 2) % 3;
        for (size_t i = 0; i < p.count; ++i) {
            auto box = p.shapes[i];
            if (coord(body.max, a) <= coord(box.min, a) + .0001f ||
                coord(body.min, a) >= coord(box.max, a) - .0001f ||
                coord(body.max, b) <= coord(box.min, b) + .0001f ||
                coord(body.min, b) >= coord(box.max, b) - .0001f) continue;
            float low = coord(box.min, axis) - coord(body.max, axis);
            float high = coord(box.max, axis) - coord(body.min, axis);
            if (d[axis] > 0 && low >= -.001f) d[axis] = std::min(d[axis], std::max(0.f, low));
            if (d[axis] < 0 && high <= .001f) d[axis] = std::max(d[axis], std::min(0.f, high));
        }
        coord(body.min, axis) += d[axis]; coord(body.max, axis) += d[axis];
    }
    return from + sc::Vec3{d[0], d[1], d[2]};
}
inline sc::Vec3 constrain(sc::Vec3 from,sc::Vec3 to,const PhysicsPacket &p) {
    return constrain(from,to,p,p.radius,p.height);
}
// Shared by the actual NPC callback and offline tests. Native teleports and
// actors outside a complete MC region keep the original engine movement.
inline sc::Vec3 constrainNpc(sc::Vec3 from,sc::Vec3 to,const PhysicsPacket &p,
                             uint64_t epoch,uint64_t now) {
    if(!(p.flags&1) || p.epoch!=epoch || !fresh(now,p.tick,250) || !fresh(now,p.controlTick) ||
       !sc::finite(from) || !sc::finite(to) || sc::length(to-from)>3 ||
       !coversBody(p,from,p.radius,p.height) || !coversBody(p,to,p.radius,p.height))return to;
    return constrain(from,to,p);
}
} // namespace bridge
