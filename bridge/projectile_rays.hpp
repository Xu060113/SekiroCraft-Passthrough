#pragma once
#include "session.hpp"
namespace bridge {
constexpr size_t projectileRaySlots=64;
struct alignas(8) ProjectileRay {uint64_t id{};sc::Vec3 start{},delta{};};
struct alignas(8) ProjectileRayHit {uint64_t id{};sc::Vec3 position{},normal{};uint32_t hit{},reserved{};};
struct alignas(8) ProjectileRays {
    uint64_t sequence{},tick{},epoch{};uint32_t count{},reserved{};
    std::array<ProjectileRay,projectileRaySlots> rays{};
};
struct alignas(8) ProjectileHits {
    uint64_t sequence{},tick{},epoch{};uint32_t count{},reserved{};
    std::array<ProjectileRayHit,projectileRaySlots> hits{};
};
static_assert(sizeof(ProjectileRay)==32 && sizeof(ProjectileRayHit)==40);
static_assert(sizeof(ProjectileRays)==2080 && sizeof(ProjectileHits)==2592);
inline bool validRays(const ProjectileRays &p){
    if(!p.sequence || !p.tick || !p.epoch || p.count>projectileRaySlots || p.reserved)return false;
    for(size_t i=0;i<p.count;++i)if(!p.rays[i].id || !sc::finite(p.rays[i].start) ||
        !sc::finite(p.rays[i].delta) || sc::length(p.rays[i].start)>150000 || sc::length(p.rays[i].delta)>16)return false;
    return true;
}
inline bool validHits(const ProjectileHits &p){
    if(!p.sequence || !p.tick || !p.epoch || p.count>projectileRaySlots || p.reserved)return false;
    for(size_t i=0;i<p.count;++i){const auto &h=p.hits[i];if(!h.id || h.hit>1 || h.reserved ||
        (h.hit && (!sc::finite(h.position) || !sc::finite(h.normal) || sc::length(h.position)>150000 ||
            sc::length(h.normal)<.5f || sc::length(h.normal)>1.5f)))return false;}
    return true;
}
}
