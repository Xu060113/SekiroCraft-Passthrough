#include "../include/sekirocraft/actor_shape.hpp"
#include "../include/sekirocraft/host.hpp"
#include "../bridge/actor_shapes.hpp"
#include <iostream>
#include <stdexcept>
#include <unordered_map>

int main(){int checks{};auto check=[&](bool ok,const char *message){++checks;if(!ok)throw std::runtime_error(message);};
    // Bounded sparse address space exercises the production reader without
    // launching/injecting into the game or permitting out-of-fixture reads.
    std::unordered_map<uintptr_t,std::vector<uint8_t>> memory;
    auto put=[&](uintptr_t address,auto value){auto &bytes=memory[address];bytes.resize(sizeof(value));std::memcpy(bytes.data(),&value,sizeof(value));};
    auto read=[&](uintptr_t address,auto &value){auto at=memory.find(address);if(at==memory.end() || at->second.size()!=sizeof(value))return false;
        std::memcpy(&value,at->second.data(),sizeof(value));return true;};
    constexpr uintptr_t base=0x140000000,chr=0x300000000,physics=0x400000000,definition=0x500000000;
    put(physics,base+0x2a89040);put(physics+8,chr);put(physics+0xdc,5.4f);put(physics+0xe0,1.5f);
    auto body=sc::readActorBody(base,chr,physics,read);
    check(body.native && body.width==3 && body.height==5.4f && body.yOffset==0,"large Boss uses native radius/height, not a 0.6 x 1.8 actor");
    put(physics+0xdc,1.f);body=sc::readActorBody(base,chr,physics,read);
    check(body.native && body.width==3 && body.height==3,"short capsule includes its spherical extent");
    put(physics+0xdc,5.4f);put(physics+8,chr+8);body=sc::readActorBody(base,chr,physics,read);
    check(!body.native && body.width==.6f && body.height==1.8f,"foreign owner cannot supply another Boss shape");put(physics+8,chr);
    put(physics,base+0x2a89048);check(!sc::readActorBody(base,chr,physics,read).native,"unknown physics vtable uses bounded fallback");put(physics,base+0x2a89040);
    for(float invalid:{NAN,INFINITY,-1.f,0.f,65.f}){put(physics+0xdc,invalid);check(!sc::readActorBody(base,chr,physics,read).native,"invalid height never creates a giant/NaN world query");}
    put(physics+0xdc,5.4f);
    for(float invalid:{NAN,INFINITY,-1.f,0.f,33.f}){put(physics+0xe0,invalid);check(!sc::readActorBody(base,chr,physics,read).native,"invalid radius rejected");}
    put(physics+0xe0,1.5f);
    constexpr uintptr_t repo=0x600000000,record=0x700000000,inner=0x800000000,table=0x900000000;
    put(chr+0x30,definition);put(definition+0x628,int32_t(2000));put(base+0x3d978b0,repo);put(repo+0x228,record);
    put(record+0x70,inner);put(inner+0x70,table);put(table+0xa,uint16_t(3));
    put(table+0x40,int32_t(1000));put(table+0x58,int32_t(2000));put(table+0x70,int32_t(3000));
    put(table+0x60,uint64_t(0x1000));put(table+0x101c,.75f);
    body=sc::readActorBody(base,chr,physics,read);check(body.native && body.yOffset==.75f,"displayed-model offset comes from this NPC's current Param row");
    put(table+0x101c,-.5f);check(sc::readActorBody(base,chr,physics,read).yOffset==-.5f,"negative model offset is retained");
    put(table+0x101c,NAN);check(sc::readActorBody(base,chr,physics,read).yOffset==0,"invalid model offset cannot move a valid body into the void");
    put(table+0x60,uint64_t(8));check(sc::readActorBody(base,chr,physics,read).yOffset==0,"row header cannot be mistaken for param data");
    put(table+0xa,uint16_t(65535));check(sc::readActorBody(base,chr,physics,read).native,"malformed param table does not discard valid physics dimensions");
    bridge::ActorShapes packet;packet.sequence=8;packet.tick=1000;packet.epoch=7;packet.count=1;packet.actors[0]={19,2,3,5.4f,.75f,1};
    check(bridge::validActorShapes(packet),"native body transport validates");packet.actors[0].width=NAN;check(!bridge::validActorShapes(packet),"NaN body packet rejected");packet.actors[0].width=3;
    packet.count=2;packet.actors[1]=packet.actors[0];check(!bridge::validActorShapes(packet),"duplicate native identity cannot select inconsistent bounds");packet.count=1;
    packet.actors[0].stage=0;check(!bridge::validActorShapes(packet),"body packet without stage rejected");packet.actors[0].stage=2;
    packet.actors[0].source=2;check(!bridge::validActorShapes(packet),"unknown native shape source rejected");packet.actors[0].source=1;
    packet.count=65;check(!bridge::validActorShapes(packet),"body ring capacity enforced");
    std::cout<<checks<<" native actor body identity, size, offset and transport checks passed\n";
}
