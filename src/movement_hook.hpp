#pragma once
#include "MinHook.h"
#include "sekirocraft/host.hpp"
#include "../bridge/physics.hpp"
#include "native_driver.hpp"
#include "native_combat.hpp"
#include <mutex>
#include <atomic>

extern "C" {
void scMovementEntry();
extern void *scMovementContinue;
extern void (*scMovementHandler)(uintptr_t, float *) noexcept;
}
namespace bridge {
// This detour runs at ChrPhysicsModule's final position store, rather than
// teleporting a render-thread mirror that the next physics update overwrites.
class NativeMovement {
    uintptr_t base_{};
    bool installed_{}, canFly_{};
    uint64_t retryTick_{};
    std::mutex mutex_;
    PhysicsChannel *channel_{};
    Control control_{};
    bool active_{};
    NativeDriver *driver_{};
    NativeCombatAdapter *combat_{};
    PhysicsPacket packet_{};
    uintptr_t lastPhysics_{};
    sc::Vec3 last_{};
    uint64_t lastTick_{};
    sc::OwnedBit gravity_, noMove_;
    static inline NativeMovement *instance_{};
    static void callback(uintptr_t physics, float *candidate) noexcept {
        if (instance_) instance_->sample(physics, candidate);
    }
    void releaseFlight() { noMove_.release(); gravity_.release(); }
  public:
    std::atomic<uint64_t> playerCalls{}, correctedMoves{}, flightMoves{};
    bool installed() const { return installed_; }
    bool canFly() const { return canFly_; }
    void driver(NativeDriver &driver){driver_=&driver;}
    void combat(NativeCombatAdapter &combat){combat_=&combat;}
    void initialize(uintptr_t base, PhysicsChannel &channel) {
        base_ = base; channel_ = &channel; instance_ = this;
        scMovementHandler = callback;
    }
    void tryInstall() {
        if (installed_ || !base_ || GetTickCount64() - retryTick_ < 1000) return;
        retryTick_ = GetTickCount64();
        const std::array<uint8_t, 7> expected{0x0f, 0x29, 0xb6, 0x80, 0, 0, 0};
        std::array<uint8_t, 7> actual{};
        if (!sc::readMemory(base_ + 0xbc4796, actual) || actual != expected) return;
        const std::array<uint8_t, 8> timer{0xf3, 0x0f, 0x58, 0x87, 0xd0, 8, 0, 0};
        std::array<uint8_t, 8> timerActual{};
        canFly_ = sc::readMemory(base_ + 0xbc3633, timerActual) && timerActual == timer;
        void *target = reinterpret_cast<void *>(base_ + 0xbc4796);
        if (MH_CreateHook(target, reinterpret_cast<void *>(scMovementEntry), &scMovementContinue) != MH_OK)
            return;
        installed_ = MH_EnableHook(target) == MH_OK;
        if (!installed_) { MH_RemoveHook(target); canFly_ = false; }
        sc::log(installed_ ? "Player movement constraint hook installed; flight timer signature=" +
                            std::to_string(canFly_) : "Player movement hook installation failed");
    }
    void update(const Control &control, bool on) {
        std::unique_lock lock(mutex_, std::try_to_lock); if (!lock) return;
        control_ = control; active_ = on && installed_;
        if (!active_) { releaseFlight(); lastPhysics_ = 0; }
    }
    void sample(uintptr_t physics, float *candidate) noexcept {
        std::unique_lock lock(mutex_, std::try_to_lock); if (!lock) return;
        if(combat_)combat_->observe(physics);
        uintptr_t root{}, hero{}, owner{}, state{}, actualPhysics{};
        // Every callback may also be an NPC. It must match the current player's
        // owner and module, not a cached pointer from an earlier loading screen.
        if (!sc::readMemory(base_ + 0x3d7a1e0, root) || !sc::readMemory(root + 0x88, hero) ||
            !sc::readMemory(physics + 8, owner) || owner != hero ||
            !sc::readMemory(hero + 0x1ff8, state) || !sc::readMemory(state + 0x68, actualPhysics) ||
            actualPhysics != physics) return;
        playerCalls.fetch_add(1, std::memory_order_relaxed);
        uint64_t now = GetTickCount64();
        if(combat_)combat_->tick(control_.epoch,active_ && fresh(now,control_.tickMs));
        if (!active_ || !fresh(now, control_.tickMs)) { releaseFlight(); lastPhysics_ = 0; return; }
        if (driver_ && (control_.capabilities & mcOwnerCapability)) {
            sc::Vec3 target{};
            if (!driver_->target(target) || !canFly_ ||
                !gravity_.acquire(physics+0x92d,1) || !noMove_.acquire(hero+0x1f40,128)) {
                releaseFlight(); return;
            }
            candidate[0]=target.x;candidate[1]=target.y;candidate[2]=target.z;
            float zero{};SIZE_T wrote{};
            WriteProcessMemory(GetCurrentProcess(),reinterpret_cast<void*>(physics+0x8d0),&zero,sizeof(zero),&wrote);
            return;
        }
        if (channel_) channel_->read(packet_); // Retain snapshot on a zero-wait lock miss.
        sc::Vec3 proposed{candidate[0], candidate[1], candidate[2]};
        if (!validPhysics(packet_) || packet_.epoch != control_.epoch || !fresh(now, packet_.tick) ||
            !fresh(now, packet_.controlTick) || !sc::finite(proposed) ||
            sc::length(proposed - packet_.origin) > 3) {
            releaseFlight(); lastPhysics_ = 0; return;
        }
        if (lastPhysics_ != physics || !lastTick_ || now - lastTick_ > 100 ||
            sc::length(proposed - last_) > 3) {
            std::array<float, 4> old{};
            if (!sc::readMemory(physics + 0x80, old)) return;
            last_ = {old[0], old[1], old[2]}; lastPhysics_ = physics; lastTick_ = now;
        }
        float dt = std::clamp(float(now - lastTick_) / 1000, 0.f, .05f);
        bool flying = canFly_ && (control_.flags & Edit) != 0 && (packet_.flags & 6) == 6;
        if (flying) {
            flightMoves.fetch_add(1, std::memory_order_relaxed);
            if (!gravity_.acquire(physics + 0x92d, 1) || !noMove_.acquire(hero + 0x1f40, 128)) {
                releaseFlight(); flying = false;
            }
        } else releaseFlight();
        if (flying) {
            auto key = [&](int vk) { return (control_.keys[vk / 8] & (1 << (vk % 8))) != 0; };
            bool screen = (control_.flags & Menu) != 0;
            auto forward = sc::normalize(sc::Vec3{control_.forward[0], 0, control_.forward[2]});
            sc::Vec3 right{forward.z, 0, -forward.x};
            sc::Vec3 direction{};
            if (!screen) direction = forward * float(int(key('W')) - int(key('S'))) +
                                     right * float(int(key('D')) - int(key('A'))) +
                                     sc::Vec3{0, float(int(key(VK_SPACE)) - int(key(VK_SHIFT))), 0};
            proposed = last_ + sc::normalize(direction) * (key(VK_CONTROL) ? 10.f : 5.f) * dt;
            // Same in-air timer addressed by SekiroTool's no-clip hook. Gravity
            // and movement bits are restored on off, stale data, focus loss, death.
            float zero{}; SIZE_T wrote{};
            WriteProcessMemory(GetCurrentProcess(), reinterpret_cast<void *>(physics + 0x8d0),
                               &zero, sizeof(zero), &wrote);
        }
        if ((packet_.flags & 1) != 0) {
            auto before = proposed;
            proposed = constrain(last_, proposed, packet_);
            if (before.y < last_.y && proposed.y > before.y + .001f) {
                float zero{}; SIZE_T wrote{};
                if (canFly_) WriteProcessMemory(GetCurrentProcess(), reinterpret_cast<void *>(physics + 0x8d0),
                                               &zero, sizeof(zero), &wrote);
            }
        }
        if (sc::finite(proposed) && sc::length(proposed - last_) <= 3) {
            if (sc::length(proposed - sc::Vec3{candidate[0], candidate[1], candidate[2]}) > .0001f)
                correctedMoves.fetch_add(1, std::memory_order_relaxed);
            candidate[0] = proposed.x; candidate[1] = proposed.y; candidate[2] = proposed.z;
            last_ = proposed; lastTick_ = now;
        }
    }
};
} // namespace bridge
