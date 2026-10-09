#pragma once
#include "math.hpp"
namespace sc {
struct ActorBody {float width=.6f,height=1.8f,yOffset{};bool native{};};
// 1.06 SprjChrPhysicsModule. Read-only; caller also fingerprints the getters,
// the native configuration stores and the executable before enabling this.
template<class Read> ActorBody readActorBody(uintptr_t base,uintptr_t chr,uintptr_t physics,Read read) {
    ActorBody out;uintptr_t vt{},owner{};float height{},radius{};
    if(!base || !read(physics,vt) || vt!=base+0x2a89040 || !read(physics+8,owner) || owner!=chr ||
       !read(physics+0xdc,height) || !read(physics+0xe0,radius) ||
       !std::isfinite(height) || height<=0 || height>64 ||
       !std::isfinite(radius) || radius<.02f || radius>32)return out;
    out.width=radius*2;out.height=std::max(height,out.width);out.native=true;
    // NpcParam hitYOffset describes the displayed model's vertical offset.
    // Read the current row by the live NPC ID, never a guessed Boss name/HP.
    uintptr_t definition{},repo{},record{},inner{},table{};int32_t npcId{};uint16_t count{};
    if(!read(chr+0x30,definition) || !read(definition+0x628,npcId) || npcId<0 ||
       !read(base+0x3d978b0,repo) || !read(repo+0x228,record) || !read(record+0x70,inner) ||
       !read(inner+0x70,table) || !read(table+0xa,count) || count==0 || count>32768)return out;
    // Param rows are sorted by signed ID; bounded binary search, no cached
    // pointer surviving scene reloads. The 64-bit row offset is at header +8.
    size_t low=0,high=count;
    while(low<high){size_t mid=low+(high-low)/2;int32_t id{};
        if(!read(table+0x40+mid*0x18,id))return out;
        if(id<npcId){low=mid+1;continue;}if(id>npcId){high=mid;continue;}
        uint64_t offset{};float y{};
        if(read(table+0x48+mid*0x18,offset) && offset>=0x40+size_t(count)*0x18 && offset<0x10000000 &&
           read(table+offset+0x1c,y) && std::isfinite(y) && std::abs(y)<=32)out.yOffset=y;
        break;
    }
    return out;
}
} // namespace sc
