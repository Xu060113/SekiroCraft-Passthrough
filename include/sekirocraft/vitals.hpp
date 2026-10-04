#pragma once
#include <array>
#include <cstdint>
namespace sc {
struct Vitals {
    int32_t hp{}, maxHp{}, posture{}, maxPosture{};
    bool valid{};
};
template <class Read> Vitals readVitals(uintptr_t base, bool supported, Read &&read) {
    Vitals result;
    if (!supported || base < 65536)
        return result;
    uintptr_t root{}, hero{}, state{}, health{};
    // Read-only Sekiro 1.06 state-module chain from sekiro-coop SDK live.rs.
    if (!read(base + 0x3d7a1e0, root) || !read(root + 0x88, hero) || !read(hero + 0x1ff8, state) ||
        !read(state + 0x18, health))
        return result;
    std::array<int32_t, 8> values{};
    if (!read(health + 0x130, values))
        return result;
    result = {values[0], values[1], values[6], values[7], false};
    result.valid = result.hp >= 0 && result.maxHp > 0 && result.maxHp <= 10000000 &&
                   result.hp <= result.maxHp && result.posture >= 0 && result.maxPosture > 0 &&
                   result.maxPosture <= 10000000 && result.posture <= result.maxPosture;
    return result;
}
} // namespace sc
