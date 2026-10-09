#pragma once
#include "combat.hpp"
namespace bridge {
constexpr uint32_t actorShapesCapability=4096;
// Independent extension: frame/control and combat-state-v3 retain their ABI.
struct alignas(8) ActorShape {
    uint64_t id{},stage{};
    float width=.6f,height=1.8f,yOffset{};
    uint32_t source{}; // 0: human fallback, 1: live native character capsule
};
struct alignas(8) ActorShapes {
    uint64_t sequence{},tick{},epoch{};
    uint32_t count{},reserved{};
    std::array<ActorShape,actorSlots> actors{};
};
static_assert(sizeof(ActorShape)==32 && sizeof(ActorShapes)==2080 && offsetof(ActorShapes,actors)==32);
inline bool validActorShape(const ActorShape &a) {
    return a.id && a.stage && std::isfinite(a.width) && a.width>=.04f && a.width<=64 &&
        std::isfinite(a.height) && a.height>=.04f && a.height<=64 &&
        std::isfinite(a.yOffset) && std::abs(a.yOffset)<=32 && a.source<=1;
}
inline bool validActorShapes(const ActorShapes &p) {
    if(!p.sequence || !p.tick || !p.epoch || p.count>actorSlots || p.reserved)return false;
    for(size_t i=0;i<p.count;++i){
        if(!validActorShape(p.actors[i]))return false;
        for(size_t j=0;j<i;++j)if(p.actors[j].id==p.actors[i].id)return false;
    }
    return true;
}
} // namespace bridge
