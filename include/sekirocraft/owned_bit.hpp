#pragma once
#include <windows.h>
#include <cstdint>

namespace sc {
// Own only one bit, and restore it only while the byte still carries our value.
// This does not overwrite unrelated engine/debug flags or change page protection.
class OwnedBit {
  public:
    OwnedBit() = default;
    OwnedBit(const OwnedBit &) = delete;
    OwnedBit &operator=(const OwnedBit &) = delete;
    ~OwnedBit() { release(); }
    bool acquire(uintptr_t address, uint8_t mask) {
        if (!mask || (mask & (mask - 1)) || address < 65536)
            return false;
        if (address_) {
            uint8_t current{};
            SIZE_T got{};
            return address_ == address && mask_ == mask &&
                   ReadProcessMemory(GetCurrentProcess(), reinterpret_cast<void *>(address_), &current, 1,
                                     &got) &&
                   got == 1 && (current & mask_) != 0;
        }
        uint8_t value{};
        SIZE_T n{};
        if (!ReadProcessMemory(GetCurrentProcess(), reinterpret_cast<void *>(address), &value, 1, &n) ||
            n != 1 || (value & mask))
            return false;
        auto next = uint8_t(value | mask);
        if (!WriteProcessMemory(GetCurrentProcess(), reinterpret_cast<void *>(address), &next, 1, &n) ||
            n != 1)
            return false;
        address_ = address;
        mask_ = mask;
        return true;
    }
    bool release() {
        if (!address_)
            return true;
        uint8_t value{};
        SIZE_T n{};
        if (!ReadProcessMemory(GetCurrentProcess(), reinterpret_cast<void *>(address_), &value, 1, &n) ||
            n != 1)
            return false;
        if (value & mask_) {
            auto next = uint8_t(value & ~mask_);
            if (!WriteProcessMemory(GetCurrentProcess(), reinterpret_cast<void *>(address_), &next, 1, &n) ||
                n != 1)
                return false;
        }
        address_ = 0;
        mask_ = 0;
        return true;
    }
    bool active() const { return address_ != 0; }

  private:
    uintptr_t address_{};
    uint8_t mask_{};
};
} // namespace sc
