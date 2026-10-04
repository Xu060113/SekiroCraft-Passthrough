#include "../src/movement_hook.hpp"
#include <iostream>
#include <stdexcept>
extern "C" int scTestMove(void *, const float *);
extern "C" void scTestStore();
extern "C" void scTestClobber(uintptr_t, float *) noexcept;
int main() {
    int checks{};
    auto check = [&](bool ok, const char *why) { ++checks; if (!ok) throw std::runtime_error(why); };
    try {
        uint8_t flags = 0b10101000;
        {
            sc::OwnedBit draw;
            check(draw.acquire(reinterpret_cast<uintptr_t>(&flags), 8, false) && flags == 0b10100000,
                  "disable player draw bit only");
            flags |= 1;
            check(draw.release() && flags == 0b10101001, "restore draw preserving foreign flags");
        }
        bridge::PhysicsPacket p;
        p.tick = p.controlTick = GetTickCount64(); p.epoch = 7; p.sequence = 1; p.flags = 1; p.count = 1;
        p.shapes[0] = {{1, 0, -1}, {2, 3, 1}};
        check(bridge::validPhysics(p), "shape validation");
        auto wall = bridge::constrain({0, 0, 0}, {2, 0, .5}, p);
        check(std::abs(wall.x - .7f) < .001f && std::abs(wall.z - .5f) < .001f, "wall sweep with sliding");
        p.shapes[0] = {{-1, 0, -1}, {1, 1, 1}};
        auto floor = bridge::constrain({0, 2, 0}, {0, -2, 0}, p);
        check(std::abs(floor.y - 1) < .001f, "landing does not tunnel through a block");
        auto ceiling = bridge::constrain({0, -3, 0}, {0, 2, 0}, p);
        check(std::abs(ceiling.y + 1.8f) < .001f, "headroom ceiling");
        p.shapes[0] = {{-1, 0, -1}, {1, .5f, 1}};
        auto slab = bridge::constrain({0, 2, 0}, {0, 0, 0}, p);
        check(std::abs(slab.y - .5f) < .001f, "slab voxel shape retained");
        p.shapes[0].max.x = NAN;
        check(!bridge::validPhysics(p), "reject nonfinite shapes");
        p.shapes[0].max.x = 1;
        bridge::PhysicsChannel producer, consumer;
        std::wstring name = L"physics-fixture-" + std::to_wstring(GetCurrentProcessId());
        check(producer.open(name) && consumer.open(name), "separate physics channel");
        check(producer.write(p), "publish physics");
        bridge::PhysicsPacket decoded;
        check(consumer.read(decoded) && decoded.count == 1 && decoded.epoch == 7 &&
              decoded.shapes[0].max.y == .5f, "cross-channel snapshot");
        check(MH_Initialize() == MH_OK, "hook fixture initialize");
        scMovementHandler = scTestClobber;
        check(MH_CreateHook(reinterpret_cast<void *>(scTestStore), reinterpret_cast<void *>(scMovementEntry),
                            &scMovementContinue) == MH_OK && MH_EnableHook(reinterpret_cast<void *>(scTestStore)) == MH_OK,
              "actual mid-instruction detour");
        alignas(16) std::array<uint8_t, 256> module{};
        alignas(16) float values[]{1, 2, 3, 1};
        check(scTestMove(module.data(), values) == 1, "preserve live CPU flags and volatile registers");
        float position[4], vector[4];
        std::memcpy(position, module.data() + 128, 16); std::memcpy(vector, module.data() + 144, 16);
        check(position[0] == 5 && position[1] == 2 && position[2] == 3 && position[3] == 1,
              "replace only candidate xyz and resume native store");
        check(vector[0] == 1 && vector[1] == 2 && vector[2] == 3 && vector[3] == 1,
              "preserve SIMD register across arbitrary callback");
        MH_DisableHook(MH_ALL_HOOKS); MH_Uninitialize();
        std::cout << "PASS " << checks << " player draw, collision shapes, mailbox and real x64 movement detour checks\n";
        return 0;
    } catch (const std::exception &e) { std::cerr << "FAIL " << e.what() << "\n"; return 1; }
}
