#pragma once
#include <array>
#include <cstdint>

namespace sc {
struct CinematicState {
    uintptr_t instance{};
    int32_t step{-1}, next{-1};
    uint32_t flags{};
    bool valid{};
    bool playing() const {
        // SprjRemoMan: Init=0, RegistWait=1, Prologue through Finish=2..8.
        // RegistWait is normally idle; a queued Prologue already owns the scene.
        // Finish clears its active bit and schedules state -1 after cleanup.
        // Do not keep hiding MC if that completed Finish sample is retained.
        return valid && ((step >= 2 && step <= 7) ||
                         (step == 8 && (next >= 0 || (flags & 1))) ||
                         (step == 1 && next == 2));
    }
};

// Read only. These RVAs were traced from the supported 1.06 loaded image's
// SprjRemoImp runtime owner, SprjRemoMan STEP names and state getters. They are unrelated
// to Wolf's attack/grapple animation IDs. The caller must supply the SHA-gated
// base. Both code fingerprints and object types must match before following it.
template<class Read> CinematicState readCinematic(uintptr_t base, Read read) {
    if (!base) return {};
    constexpr std::array<uint8_t, 4> stepCode{0x8b,0x41,0x40,0xc3};
    constexpr std::array<uint8_t, 10> flagsCode{0x8b,0x81,0x44,0x01,0,0,0x83,0xe0,1,0xc3};
    std::array<uint8_t, 4> a{};
    std::array<uint8_t, 10> b{};
    uintptr_t owner{}, ownerVtable{}, instance{}, vtable{}, again{};
    CinematicState s;
    if (!read(base+0xec6e50,a) || a!=stepCode ||
        !read(base+0xec7330,b) || b!=flagsCode ||
        !read(base+0x3d8dc08,owner) || owner<65536 ||
        !read(owner,ownerVtable) || ownerVtable!=base+0x2b41520 ||
        !read(owner+8,instance) || instance<65536 ||
        !read(instance,vtable) || vtable!=base+0x2b3f9a8 ||
        !read(instance+0x40,s.step) || !read(instance+0x44,s.next) ||
        !read(instance+0x144,s.flags) || !read(owner+8,again) || again!=instance ||
        s.step < -1 || s.step > 9 || s.next < -1 || s.next > 9) return {};
    s.instance=instance;
    s.valid=true;
    return s;
}

// A transient read failure must not flash a HUD in the middle of a movie.
// Real idle samples release immediately; absence of the manager releases after
// the ordinary transport freshness window, so unloads cannot latch forever.
class CinematicGate {
    bool playing_{};
    uint64_t lastValid_{};
    uint64_t minimumSequence_{}, minimumTick_{};
  public:
    bool update(const CinematicState &s, uint64_t now, uint64_t sequence) {
        bool next=playing_;
        if (s.valid) { next=s.playing(); lastValid_=now; }
        else if (now<lastValid_ || now-lastValid_>350) next=false;
        if (next!=playing_) { minimumSequence_=sequence; minimumTick_=now; }
        playing_=next;
        return playing_;
    }
    bool playing() const { return playing_; }
    uint64_t minimumSequence() const { return minimumSequence_; }
    uint64_t minimumTick() const { return minimumTick_; }
    bool accepts(uint64_t sequence) const { return !playing_ && sequence>=minimumSequence_; }
};
}
