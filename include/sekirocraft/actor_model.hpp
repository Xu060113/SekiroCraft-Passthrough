#pragma once
#include "math.hpp"
#include <vector>
#include <cstring>
namespace sc {
constexpr size_t actorModelParts=128,actorModelBones=1024;
constexpr float actorModelLimit=4096;
struct ActorPart {Vec3 min{},max{};}; // Relative to the current native physics root.
struct ModelMatrix {std::array<float,12> m{};}; // Row-major affine 3x4.
struct ModelBoneBox {std::array<float,4> min{},max{};std::array<uint8_t,32> unused{};};
static_assert(sizeof(ModelMatrix)==48 && sizeof(ModelBoneBox)==64 && sizeof(ActorPart)==24);
struct ActorModelBody {std::vector<ActorPart> parts;uint32_t bones{},source{};}; // 1 bone bounds, 2 conservatively merged.
inline bool validActorPart(const ActorPart &p) {
    return finite(p.min) && finite(p.max) &&
        std::abs(p.min.x)<=actorModelLimit && std::abs(p.min.y)<=actorModelLimit && std::abs(p.min.z)<=actorModelLimit &&
        std::abs(p.max.x)<=actorModelLimit && std::abs(p.max.y)<=actorModelLimit && std::abs(p.max.z)<=actorModelLimit &&
        p.min.x<p.max.x && p.min.y<p.max.y && p.min.z<p.max.z;
}
inline Vec3 modelPoint(const ModelMatrix &m,Vec3 p) {
    return {m.m[0]*p.x+m.m[1]*p.y+m.m[2]*p.z+m.m[3],
            m.m[4]*p.x+m.m[5]*p.y+m.m[6]*p.z+m.m[7],
            m.m[8]*p.x+m.m[9]*p.y+m.m[10]*p.z+m.m[11]};
}
inline ActorPart partUnion(const ActorPart &a,const ActorPart &b) {
    return {{std::min(a.min.x,b.min.x),std::min(a.min.y,b.min.y),std::min(a.min.z,b.min.z)},
            {std::max(a.max.x,b.max.x),std::max(a.max.y,b.max.y),std::max(a.max.z,b.max.z)}};
}
inline float partDistance(const ActorPart &p,Vec3 at) {
    Vec3 delta{std::max({p.min.x-at.x,0.f,at.x-p.max.x}),
               std::max({p.min.y-at.y,0.f,at.y-p.max.y}),std::max({p.min.z-at.z,0.f,at.z-p.max.z})};
    return length(delta);
}
inline float modelDistance(const ActorModelBody &body,Vec3 relative) {
    float result=INFINITY;for(const auto &part:body.parts)result=std::min(result,partDistance(part,relative));return result;
}
inline bool modelHitInRange(const ActorModelBody &body,Vec3 from,Vec3 root,Vec3 impact) {
    return !body.parts.empty() && finite(from) && finite(root) && finite(impact) &&
        modelDistance(body,from-root)<=64 && modelDistance(body,impact-root)<=2;
}
// Only inverted FLVER boxes are unused. Fail the complete snapshot on corrupt
// used geometry, rather than silently dropping a head/limb. Read all arrays in
// bounded bulk copies; identity, pointers, world transform and pose must remain
// stable across both reads. No game pointer survives this function.
template<class Read,class Bulk> ActorModelBody readActorModel(uintptr_t base,uintptr_t chr,Vec3 root,Read read,Bulk bulk) {
    ActorModelBody result;uintptr_t model{},resource{},owner{},vt{},data{},matrices{},boxes{},matrixVt{};
    uint16_t count{};uint32_t poseCount{};ModelMatrix world{};
    if(!base || !finite(root) || !read(chr+0x48,model) || !read(chr+0x30,resource) || !resource ||
       !read(model,vt) || vt!=base+0x29f7ce8 || !read(model+8,owner) ||
       !read(model+0x18,data) || !read(data,vt) || vt!=base+0x2b56800 ||
       !read(model+0x60,matrixVt) || matrixVt!=base+0x2b579d8 ||
       !read(data+0x410,count) || count==0 || count>actorModelBones ||
       !read(model+0x74,poseCount) || poseCount!=count || !read(model+0x78,matrices) ||
       !read(data+0x418,boxes) || !read(model+0x30,world))return result;
    std::vector<ModelMatrix> pose(count),second(count);std::vector<ModelBoneBox> bounds(count);
    if(!bulk(matrices,pose.data(),pose.size()*sizeof(ModelMatrix)) ||
       !bulk(boxes,bounds.data(),bounds.size()*sizeof(ModelBoneBox)) ||
       !bulk(matrices,second.data(),second.size()*sizeof(ModelMatrix)) ||
       std::memcmp(pose.data(),second.data(),pose.size()*sizeof(ModelMatrix)))return result;
    uintptr_t current{};uint16_t currentCount{};uint32_t currentPose{};ModelMatrix currentWorld{};
    if(!read(chr+0x48,current) || current!=model || !read(chr+0x30,current) || current!=resource ||
       !read(model+8,current) || current!=owner || !read(model+0x18,current) || current!=data ||
       !read(data+0x410,currentCount) || currentCount!=count || !read(model+0x74,currentPose) || currentPose!=count ||
       !read(model+0x78,current) || current!=matrices || !read(data+0x418,current) || current!=boxes ||
       !read(model+0x30,currentWorld) || std::memcmp(&world,&currentWorld,sizeof(world)))return result;
    for(float f:world.m)if(!std::isfinite(f))return result;
    std::vector<ActorPart> parts;
    for(size_t i=0;i<count;++i){const auto &b=bounds[i];
        if(b.min[0]>b.max[0] || b.min[1]>b.max[1] || b.min[2]>b.max[2])continue;
        for(int j=0;j<3;++j)if(!std::isfinite(b.min[j]) || !std::isfinite(b.max[j]) ||
            std::abs(b.min[j])>actorModelLimit || std::abs(b.max[j])>actorModelLimit)return result;
        for(float f:pose[i].m)if(!std::isfinite(f))return result;
        ActorPart part{{INFINITY,INFINITY,INFINITY},{-INFINITY,-INFINITY,-INFINITY}};
        // Transform the eight original corners through BOTH matrices. Taking
        // an intermediate AABB would inflate rotating limbs a second time.
        for(unsigned corner=0;corner<8;++corner){Vec3 local{(corner&1)?b.max[0]:b.min[0],
            (corner&2)?b.max[1]:b.min[1],(corner&4)?b.max[2]:b.min[2]};
            auto p=modelPoint(world,modelPoint(pose[i],local))-root;
            if(!finite(p))return result;
            part.min={std::min(part.min.x,p.x),std::min(part.min.y,p.y),std::min(part.min.z,p.z)};
            part.max={std::max(part.max.x,p.x),std::max(part.max.y,p.y),std::max(part.max.z,p.z)};
        }
        // A small tolerance covers flat mesh bounds and inter-tick animation.
        part.min=part.min-Vec3{.03f,.03f,.03f};part.max=part.max+Vec3{.03f,.03f,.03f};
        if(!validActorPart(part))return result;parts.push_back(part);
    }
    if(parts.empty())return result;
    result.bones=count;result.source=1;
    if(parts.size()>actorModelParts){
        // Preserve EVERY bone if a modded model exceeds the fixed IPC budget.
        // Merge consecutive FLVER groups; this deliberately adds some empty
        // space but never truncates the last bones (often hands/tail).
        std::vector<ActorPart> merged;size_t stride=(parts.size()+actorModelParts-1)/actorModelParts;
        for(size_t i=0;i<parts.size();i+=stride){auto part=parts[i];
            for(size_t j=i+1;j<std::min(i+stride,parts.size());++j)part=partUnion(part,parts[j]);merged.push_back(part);}
        parts=std::move(merged);result.source=2;
    }
    result.parts=std::move(parts);return result;
}
} // namespace sc
