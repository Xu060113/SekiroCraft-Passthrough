#pragma once
#include "combat.hpp"
#include "../include/sekirocraft/actor_model.hpp"
namespace bridge {
constexpr uint32_t actorPartsCapability=8192;
struct alignas(8) ActorPartsEntry {
    uint64_t id{},stage{};
    uint32_t count{},bones{},source{},reserved{};
    std::array<sc::ActorPart,sc::actorModelParts> parts{};
};
struct alignas(8) ActorParts {
    uint64_t sequence{},tick{},epoch{};uint32_t count{},reserved{};
    std::array<ActorPartsEntry,actorSlots> actors{};
};
static_assert(sizeof(ActorPartsEntry)==3104 && offsetof(ActorPartsEntry,parts)==32 && sizeof(ActorParts)==198688);
inline bool validActorParts(const ActorParts &p) {
    if(!p.sequence || !p.tick || !p.epoch || p.count>actorSlots || p.reserved)return false;
    for(size_t i=0;i<p.count;++i){const auto &a=p.actors[i];
        if(!a.id || !a.stage || !a.count || a.count>sc::actorModelParts || !a.bones || a.bones>sc::actorModelBones ||
           a.count>a.bones || a.source<1 || a.source>2 || a.reserved)return false;
        for(size_t j=0;j<a.count;++j)if(!sc::validActorPart(a.parts[j]))return false;
        for(size_t j=0;j<i;++j)if(p.actors[j].id==a.id)return false;
    }
    return true;
}
} // namespace bridge
