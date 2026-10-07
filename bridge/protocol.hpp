#pragma once
#include <array>
#include <bit>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <span>
#include <vector>

namespace bridge {
// Fixed little-endian ABI. Java Protocol.java is its other implementation.
constexpr uint32_t magic = 0x31504253; // SBP1
constexpr uint32_t version = 3, maxWidth = 1920, maxHeight = 1080;
constexpr uint32_t maxPixels = maxWidth * maxHeight, slots = 3;
constexpr uint32_t maxFrameBytes = maxPixels * 12;
enum Flags : uint32_t { Scene = 1, Focus = 2, Edit = 4, Menu = 8, Reset = 16, NativeDead = 32, NativeAction = 64, NativeUI=128, NativeGrapple=256, NativeCinematic=512 };
enum Capabilities : uint32_t {
    CameraSync = 1,
    Input = 2,
    DepthComposite = 4,
    GroundColumns = 8,
    NativeBlockCollision = 16,
    NativeCombat = 32
};
enum FrameFlags : uint32_t { BottomUp = 1, Overlay = 2, ExplicitYaw = 4 };
struct alignas(8) Control {
    uint64_t sequence{}, tickMs{}, epoch{};
    uint32_t flags{}, capabilities = CameraSync | Input | DepthComposite;
    float player[3]{}, eye[3]{}, forward[3]{};
    float fovY = 1.1f, aspect = 16.f / 9, nearZ = .05f, farZ = 500;
    float yOffset = 128, scale = 1;
    uint32_t width = 1280, height = 720;
    std::array<uint8_t, 32> keys{}; // Windows VK bit set
    float mouseX{}, mouseY{};       // normalized client position
    int32_t wheel{};
    uint32_t buttons{}; // left=1 right=2 middle=4 X1=8 X2=16
    uint32_t command{}; // edge counted: test block request
    uint32_t reserved{};
    uint64_t textSequence{};
    std::array<uint32_t, 8> text{}; // circular UTF-32 stream; never replayed by the peer
};
static_assert(sizeof(Control) == 200);
static_assert(offsetof(Control, keys) == 100);
struct alignas(8) FrameMeta {
    uint64_t sequence{}, tickMs{}, epoch{}, hostSequence{};
    uint32_t width{}, height{}, flags = BottomUp | Overlay, reserved{};
    float eye[3]{}, forward[3]{};
    float fovY{}, aspect{}, nearZ{}, farZ{};
    uint64_t controlTickMs{};
    uint64_t guiGeneration{};
    uint32_t guiWidth{}, guiHeight{}; // Captured Screen's scaled dimensions.
};
static_assert(sizeof(FrameMeta) == 112);
struct Frame {
    FrameMeta meta;
    std::vector<uint8_t> pixels; // RGBA world, float32 GL depth [0,1], RGBA overlay
};
inline bool valid(const FrameMeta &m) {
    if ((m.guiGeneration && (!m.guiWidth || !m.guiHeight || m.guiWidth>16384 || m.guiHeight>16384)) ||
        !m.sequence || !m.epoch || !m.width || !m.height || m.width > maxWidth || m.height > maxHeight ||
        m.width * uint64_t(m.height) > maxPixels || (m.flags & ~(BottomUp | Overlay | ExplicitYaw)))
        return false;
    if ((m.flags & ExplicitYaw) && (!std::isfinite(std::bit_cast<float>(m.reserved)) ||
        std::abs(std::bit_cast<float>(m.reserved))>360)) return false;
    for (auto f : m.eye)
        if (!std::isfinite(f) || std::abs(f) > 1e6f)
            return false;
    float norm = 0;
    for (auto f : m.forward) {
        if (!std::isfinite(f))
            return false;
        norm += f * f;
    }
    return norm > .99f && norm < 1.01f && std::isfinite(m.fovY) && m.fovY > .025f && m.fovY < 3.05f &&
           std::isfinite(m.aspect) && m.aspect >= .7f && m.aspect <= 4 && std::isfinite(m.nearZ) &&
           m.nearZ > 0 && std::isfinite(m.farZ) && m.farZ > m.nearZ && m.farZ <= 100000;
}
inline bool valid(const Control &c) {
    if (!c.sequence || !c.epoch || c.width == 0 || c.height == 0 || c.width > maxWidth ||
        c.height > maxHeight || !std::isfinite(c.yOffset) || std::abs(c.yOffset) > 1e5f ||
        !std::isfinite(c.scale) || c.scale < .1f || c.scale > 10)
        return false;
    if (!(c.flags & Scene))
        return true;
    FrameMeta m;
    m.sequence = c.sequence;
    m.epoch = c.epoch;
    m.width = c.width;
    m.height = c.height;
    std::memcpy(m.eye, c.eye, 12);
    std::memcpy(m.forward, c.forward, 12);
    m.fovY = c.fovY;
    m.aspect = c.aspect;
    m.nearZ = c.nearZ;
    m.farZ = c.farZ;
    for (auto f : c.player)
        if (!std::isfinite(f) || std::abs(f) > 1e5f)
            return false;
    return valid(m);
}
inline bool fresh(uint64_t now, uint64_t stamp, uint64_t maxAge = 350) {
    return stamp != 0 && now >= stamp && now - stamp <= maxAge;
}
inline uint64_t frameBytes(const FrameMeta &m) {
    return uint64_t(m.width) * m.height * 12;
}
} // namespace bridge
