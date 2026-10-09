#include "../include/sekirocraft/actor_model.hpp"
#include "../bridge/actor_parts.hpp"
#include <iostream>
#include <map>
#include <stdexcept>
#include <memory>
#include <cfloat>
int main(){int checks{};auto check=[&](bool ok,const char *what){++checks;if(!ok)throw std::runtime_error(what);};
    std::map<uintptr_t,std::vector<uint8_t>> memory;
    auto bytes=[&](uintptr_t a,const void *p,size_t n){auto &v=memory[a];v.resize(n);std::memcpy(v.data(),p,n);};
    auto put=[&](uintptr_t a,const auto &value){bytes(a,&value,sizeof(value));};
    auto bulk=[&](uintptr_t at,void *dst,size_t n){auto v=memory.find(at);if(v==memory.end() || v->second.size()!=n)return false;std::memcpy(dst,v->second.data(),n);return true;};
    auto read=[&](uintptr_t at,auto &value){return bulk(at,&value,sizeof(value));};
    constexpr uintptr_t base=0x140000000,chr=0x300000000,model=0x400000000,resource=0x500000000,data=0x600000000,matrices=0x700000000,boxes=0x800000000;
    put(chr+0x48,model);put(chr+0x30,resource);put(model,base+0x29f7ce8);put(model+8,uintptr_t(0));
    put(model+0x18,data);put(data,base+0x2b56800);put(model+0x60,base+0x2b579d8);put(data+0x410,uint16_t(3));put(model+0x74,uint32_t(3));
    put(model+0x78,matrices);put(data+0x418,boxes);
    sc::ModelMatrix identity{{1,0,0,0,0,1,0,0,0,0,1,0}},world=identity;world.m[3]=-300;world.m[7]=-260;world.m[11]=700;put(model+0x30,world);
    std::array<sc::ModelMatrix,3> pose{identity,identity,identity};pose[1].m[3]=80;pose[1].m[7]=8;
    std::array<sc::ModelBoneBox,3> bounds{};bounds[0].min={-.5f,0,-.5f,0};bounds[0].max={.5f,2,.5f,0};
    bounds[1].min={-1,-1,-1,0};bounds[1].max={1,1,1,0};bounds[2].min.fill(FLT_MAX);bounds[2].max.fill(-FLT_MAX);
    auto save=[&]{bytes(matrices,pose.data(),sizeof(pose));bytes(boxes,bounds.data(),sizeof(bounds));};save();
    auto body=sc::readActorModel(base,chr,{-300,-260,700},read,bulk);
    check(body.parts.size()==2 && body.bones==3 && body.source==1,"all used bones retained; empty FLVER bone ignored");
    check(body.parts[1].min.x>78.9f && body.parts[1].max.x>81,"far limb remains covered beyond old 12/64 metre root guards");
    check(sc::modelHitInRange(body,{-219,-252,700},{-300,-260,700},{-220,-252,700}),"nearby large Boss limb is hittable when its root is far away");
    check(!sc::modelHitInRange(body,{-219,-252,700},{-300,-260,700},{-260,-252,700}),"air between root and arm cannot supply a native impact");
    check(!sc::modelHitInRange(body,{-130,-252,700},{-300,-260,700},{-220,-252,700}),"distance to nearest part remains bounded");
    pose[1].m={0,0,-1,80,0,1,0,8,1,0,0,0};bounds[1].min={-2,-1,-.2f,0};bounds[1].max={2,1,.2f,0};save();
    body=sc::readActorModel(base,chr,{-300,-260,700},read,bulk);
    check(body.parts.size()==2 && body.parts[1].max.x-body.parts[1].min.x<.5f && body.parts[1].max.z-body.parts[1].min.z>4,"animated rotation applied before world bounds");
    world.m={0,0,-1,-300,0,1,0,-260,1,0,0,700};put(model+0x30,world);
    body=sc::readActorModel(base,chr,{-300,-260,700},read,bulk);
    check(body.parts[1].min.z>78.9f && body.parts[1].max.x-body.parts[1].min.x>4,"model-to-world rotation composes with current bone pose");
    put(model,base+0x29f7cf0);check(sc::readActorModel(base,chr,{-300,-260,700},read,bulk).parts.empty(),"unknown model class rejected");put(model,base+0x29f7ce8);
    put(model+0x74,uint32_t(2));check(sc::readActorModel(base,chr,{-300,-260,700},read,bulk).parts.empty(),"pose count mismatch rejected");put(model+0x74,uint32_t(3));
    put(data+0x410,uint16_t(1025));check(sc::readActorModel(base,chr,{-300,-260,700},read,bulk).parts.empty(),"bone buffer capacity enforced");put(data+0x410,uint16_t(3));
    auto stale=[&](uintptr_t at,auto &v){bool ok=read(at,v);if(at==model+0x30)put(model+0x78,matrices+16);return ok;};
    check(sc::readActorModel(base,chr,{-300,-260,700},stale,bulk).parts.empty(),"scene reload pointer change cannot reuse old bones");put(model+0x78,matrices);
    int reads{};auto moving=[&](uintptr_t at,void *dst,size_t n){bool ok=bulk(at,dst,n);if(at==matrices && ++reads==1){pose[0].m[3]=2;save();}return ok;};
    check(sc::readActorModel(base,chr,{-300,-260,700},read,moving).parts.empty(),"mixed animation snapshots rejected");pose[0]=identity;save();
    bounds[1].min[0]=NAN;save();check(sc::readActorModel(base,chr,{-300,-260,700},read,bulk).parts.empty(),"corrupt used bone fails whole shape rather than dropping a limb");bounds[1].min[0]=-2;save();
    auto packet=std::make_unique<bridge::ActorParts>();packet->sequence=4;packet->tick=100;packet->epoch=8;packet->count=1;
    auto &entry=packet->actors[0];entry.id=9;entry.stage=2;entry.count=2;entry.bones=3;entry.source=1;
    body=sc::readActorModel(base,chr,{-300,-260,700},read,bulk);std::copy(body.parts.begin(),body.parts.end(),entry.parts.begin());
    check(bridge::validActorParts(*packet),"multipart transport layout validates");entry.parts[1].max.y=NAN;check(!bridge::validActorParts(*packet),"nonfinite IPC part rejected");entry.parts[1]=entry.parts[0];
    packet->count=2;packet->actors[1]=entry;check(!bridge::validActorParts(*packet),"duplicate Boss identities rejected");packet->count=1;
    entry.count=129;check(!bridge::validActorParts(*packet),"multipart IPC capacity enforced");entry.count=2;
    entry.source=3;check(!bridge::validActorParts(*packet),"unknown part source rejected");entry.source=1;
    std::vector<sc::ModelMatrix> many(257,identity);std::vector<sc::ModelBoneBox> lots(257,bounds[0]);
    for(size_t i=0;i<many.size();++i)many[i].m[3]=float(i);put(model+0x74,uint32_t(257));put(data+0x410,uint16_t(257));
    bytes(matrices,many.data(),many.size()*sizeof(many[0]));bytes(boxes,lots.data(),lots.size()*sizeof(lots[0]));
    body=sc::readActorModel(base,chr,{-300,-260,700},read,bulk);
    check(body.source==2 && body.parts.size()<=128,"oversized models use bounded conservative merging");
    check(sc::modelDistance(body,{0,1,256})==0 && sc::modelDistance(body,{0,1,0})==0,"merged overflow preserves first AND last bones");
    std::cout<<checks<<" animated model parts, native hit range and bounded transport checks passed\n";
}
