#include "sekirocraft/cinematic.hpp"
#include <cstring>
#include <iostream>
#include <unordered_map>
#include <vector>
#include <cstdlib>
namespace {
int checks{};
void check(bool ok,const char *name){++checks;if(!ok){std::cerr<<"FAIL "<<name<<'\n';std::exit(1);}}
struct Memory {
    std::unordered_map<uintptr_t,std::vector<uint8_t>> data;
    template<class T> void put(uintptr_t address,T value){
        auto &v=data[address];v.resize(sizeof(T));std::memcpy(v.data(),&value,sizeof(T));
    }
    template<class T> bool read(uintptr_t address,T &value){
        auto i=data.find(address);if(i==data.end() || i->second.size()!=sizeof(T))return false;
        std::memcpy(&value,i->second.data(),sizeof(T));return true;
    }
};
}
int main(){
    constexpr uintptr_t base=0x140000000, owner=0x300000000, instance=0x200000000;
    Memory m;
    m.put(base+0xec6e50,std::array<uint8_t,4>{0x8b,0x41,0x40,0xc3});
    m.put(base+0xec7330,std::array<uint8_t,10>{0x8b,0x81,0x44,0x01,0,0,0x83,0xe0,1,0xc3});
    m.put(base+0x3d8dc08,owner);m.put(owner,base+0x2b41520);m.put(owner+8,instance);
    m.put(instance,base+0x2b3f9a8);m.put(instance+0x44,int32_t(-1));m.put(instance+0x144,uint32_t(1));
    auto sample=[&]{return sc::readCinematic(base,[&](uintptr_t a,auto &v){return m.read(a,v);});};
    for(int32_t step=-1;step<=9;++step){m.put(instance+0x40,step);auto s=sample();
        check(s.valid,"known native states are readable");
        check(s.playing()==(step>=2 && step<=8),"only movie lifecycle states hide the MC presentation");
    }
    m.put(instance+0x40,int32_t(1));m.put(instance+0x44,int32_t(2));
    check(sample().playing(),"queued prologue hides the HUD before the first movie frame");
    m.put(instance+0x40,int32_t(8));m.put(instance+0x44,int32_t(-1));m.put(instance+0x144,uint32_t(0));
    check(!sample().playing(),"completed Finish with inactive flags cannot latch the MC overlay off");
    m.put(instance+0x144,uint32_t(1));
    check(sample().playing(),"Finish still holding a native movie remains hidden");
    m.put(instance+0x40,int32_t(700));check(!sample().valid,"unexpected layout does not claim a movie");
    m.put(instance+0x40,int32_t(4));m.put(instance,base+0x2b3eaf8);
    check(!sample().valid,"SprjRemoIns is not the SprjRemoMan lifecycle manager");
    m.put(instance,base+0x2b3f9a8);m.put(owner+8,uintptr_t(0));
    check(!sample().valid,"destroyed movie manager is not dereferenced");m.put(owner+8,instance);
    m.put(base+0x3d8dc08,uintptr_t(123));check(!sample().valid,"wrong runtime owner rejected");m.put(base+0x3d8dc08,owner);
    m.put(base+0xec6e50,std::array<uint8_t,4>{});check(!sample().valid,"changed getter fingerprint rejected");
    check(!sc::readCinematic(0,[&](uintptr_t a,auto &v){return m.read(a,v);}).valid,"unsupported host stays inactive");
    sc::CinematicGate gate;sc::CinematicState idle{instance,1,1,0,true},movie{instance,4,4,1,true};
    check(!gate.update(idle,100,10) && gate.accepts(1),"ordinary gameplay has no frame barrier");
    check(gate.update(movie,110,11) && !gate.accepts(11),"movie entry blocks both old and new overlays");
    check(gate.update({},200,12),"temporary failed read cannot flash MC in a movie");
    check(!gate.update(idle,220,13),"skip/finish releases on native idle without a fixed movie duration");
    check(!gate.accepts(12) && gate.accepts(13) && gate.minimumTick()==220,"resume rejects pre-movie HUD and player snapshots");
    check(gate.update(movie,300,14),"back-to-back movies reenter normally");
    check(!gate.update({},651,15),"unload cannot leave the movie gate stuck forever");
    gate.update(movie,700,16);
    check(!gate.update(sc::CinematicState{instance,8,-1,0,true},710,17) && gate.accepts(17),
          "completed native Finish releases the presentation barrier");
    std::cout<<"PASS "<<checks<<" cinematic state and presentation checks\n";
}
