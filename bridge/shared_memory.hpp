#pragma once
#ifndef NOMINMAX
#define NOMINMAX
#endif
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include "protocol.hpp"
#include "physics.hpp"
#include "session.hpp"
#include "combat.hpp"
#include "actor_shapes.hpp"
#include "actor_parts.hpp"
#include "native_injury.hpp"
#include "native_action.hpp"
#include "projectile_rays.hpp"
#include <string>
#include <memory>

namespace bridge {
struct alignas(8) Header {
    uint32_t magic{}, version{}, mappingBytes{}, reserved{};
    Control control;
    uint64_t newest{};
    uint32_t mcFlags{}, mcPid{}; // mcFlags: bit0 loaded, bit1 screen open
    uint64_t mcTickMs{}, mcEpoch{};
};
static_assert(sizeof(Header) == 248);
constexpr size_t slotBytes = sizeof(FrameMeta) + maxFrameBytes;
constexpr size_t mappingBytes = sizeof(Header) + slots * slotBytes;
struct PeerStatus {
    uint32_t flags{}, pid{};
    uint64_t tickMs{}, epoch{};
    uint32_t flagsFor(uint64_t hostEpoch, uint64_t now) const {
        return epoch == hostEpoch && fresh(now, tickMs) ? flags : 0;
    }
};
class TryLock {
    HANDLE mutex_{};

  public:
    explicit TryLock(HANDLE mutex) {
        DWORD r = mutex ? WaitForSingleObject(mutex, 0) : WAIT_FAILED;
        if (r == WAIT_OBJECT_0 || r == WAIT_ABANDONED)
            mutex_ = mutex;
    }
    ~TryLock() {
        if (mutex_)
            ReleaseMutex(mutex_);
    }
    explicit operator bool() const { return mutex_ != nullptr; }
};
class SharedMemory {
    HANDLE mapping_{}, mutex_{};
    uint8_t *bytes_{};

  public:
    PhysicsChannel physics;
    SnapshotChannel<InputPacket> input;
    SnapshotChannel<PlayerPacket> player;
    SnapshotChannel<TerrainPacket> terrain;
    SnapshotChannel<CombatState> combatState;
    SnapshotChannel<ActorShapes> actorShapes;
    SnapshotChannel<ActorParts> actorParts;
    SnapshotChannel<CombatReport> combatReport;
    SnapshotChannel<NativeInjuries> nativeInjuries;
    SnapshotChannel<NativeInjuryAck> nativeInjuryAck;
    SnapshotChannel<NativeActionRequest> nativeAction;
    SnapshotChannel<ProjectileRays> projectileRays;
    SnapshotChannel<ProjectileHits> projectileHits;
    ~SharedMemory() { close(); }
    SharedMemory() = default;
    SharedMemory(const SharedMemory &) = delete;
    SharedMemory &operator=(const SharedMemory &) = delete;
    static bool validChannel(const std::wstring &channel) {
        if (channel.empty() || channel.size() > 64)
            return false;
        for (wchar_t c : channel)
            if (!((c >= L'A' && c <= L'Z') || (c >= L'a' && c <= L'z') || (c >= L'0' && c <= L'9') ||
                  c == L'-' || c == L'_'))
                return false;
        return true;
    }
    bool open(const std::wstring &channel) {
        close();
        if (!validChannel(channel))
            return false;
        if (!physics.open(channel)) return false;
        if (!input.open(channel,L"input-v3") || !player.open(channel,L"player-v2") ||
            !terrain.open(channel,L"terrain-v2") || !combatState.open(channel,L"combat-state-v3") ||
            !actorShapes.open(channel,L"actor-shapes-v1") ||
            !actorParts.open(channel,L"actor-parts-v1") ||
            !combatReport.open(channel,L"combat-report-v2") || !nativeInjuries.open(channel,L"native-injuries-v1") ||
            !nativeInjuryAck.open(channel,L"native-injury-ack-v1") || !nativeAction.open(channel,L"native-action-v1") ||
            !projectileRays.open(channel,L"projectile-rays-v1") || !projectileHits.open(channel,L"projectile-hits-v1")) {close();return false;}
        auto name = L"Local\\SekiroBridge-" + channel;
        mutex_ = CreateMutexW(nullptr, FALSE, (name + L"-lock").c_str());
        mapping_ = CreateFileMappingW(INVALID_HANDLE_VALUE, nullptr, PAGE_READWRITE, 0, DWORD(mappingBytes),
                                      name.c_str());
        if (!mapping_ || !mutex_) {
            close();
            return false;
        }
        bytes_ = static_cast<uint8_t *>(MapViewOfFile(mapping_, FILE_MAP_ALL_ACCESS, 0, 0, mappingBytes));
        if (!bytes_) {
            close();
            return false;
        }
        // No render thread waits, including initialization. A busy peer retries next tick.
        TryLock lock(mutex_);
        if (!lock)
            return true;
        auto &h = header();
        if (!h.magic) {
            h.version = version;
            h.mappingBytes = uint32_t(mappingBytes);
            h.magic = magic;
        }
        return h.magic == magic && h.version == version && h.mappingBytes == mappingBytes;
    }
    void close() {
        physics.close();
        input.close(); player.close(); terrain.close();
        combatState.close();combatReport.close();actorShapes.close();
        actorParts.close();
        nativeInjuries.close();nativeInjuryAck.close();
        nativeAction.close();
        projectileRays.close();projectileHits.close();
        if (bytes_)
            UnmapViewOfFile(bytes_);
        if (mapping_)
            CloseHandle(mapping_);
        if (mutex_)
            CloseHandle(mutex_);
        bytes_ = nullptr;
        mapping_ = mutex_ = nullptr;
    }
    Header &header() { return *reinterpret_cast<Header *>(bytes_); }
    bool compatible() const {
        if (!bytes_)
            return false;
        auto &h = *reinterpret_cast<const Header *>(bytes_);
        return h.magic == magic && h.version == version && h.mappingBytes == mappingBytes;
    }
    bool writeControl(const Control &c) {
        if (!valid(c))
            return false;
        TryLock lock(mutex_);
        if (!lock || !compatible())
            return false;
        header().control = c;
        return true;
    }
    bool readControl(Control &out) {
        TryLock lock(mutex_);
        if (!lock || !compatible())
            return false;
        auto c = header().control;
        if (!valid(c))
            return false;
        out = c;
        return true;
    }
    bool status(uint32_t flags, uint64_t epoch) {
        TryLock lock(mutex_);
        if (!lock || !compatible())
            return false;
        auto &h = header();
        h.mcFlags = flags;
        h.mcPid = GetCurrentProcessId();
        h.mcTickMs = GetTickCount64();
        h.mcEpoch = epoch;
        return true;
    }
    bool readStatus(PeerStatus &out) {
        TryLock lock(mutex_);
        if (!lock || !compatible())
            return false;
        auto &h = header();
        out = {h.mcFlags, h.mcPid, h.mcTickMs, h.mcEpoch};
        return true;
    }
    uint32_t readStatus(uint64_t epoch) {
        PeerStatus peer;
        return readStatus(peer) ? peer.flagsFor(epoch, GetTickCount64()) : 0;
    }
    bool publish(const FrameMeta &meta, std::span<const uint8_t> pixels) {
        if (!valid(meta) || pixels.size() != frameBytes(meta))
            return false;
        TryLock lock(mutex_);
        if (!lock || !compatible())
            return false;
        auto &h = header();
        if (meta.epoch != h.control.epoch || !fresh(GetTickCount64(), h.control.tickMs) ||
            !(h.control.flags & Scene) || !fresh(GetTickCount64(), meta.controlTickMs))
            return false;
        // A producer restart may reset its counter. Allocate the sequence in the shared mapping.
        auto slot = bytes_ + sizeof(Header) + ((h.newest + 1) % slots) * slotBytes;
        FrameMeta m = meta;
        m.sequence = h.newest + 1;
        m.tickMs = GetTickCount64();
        std::memcpy(slot + sizeof(m), pixels.data(), pixels.size());
        std::memcpy(slot, &m, sizeof(m));
        h.newest = m.sequence;
        return true;
    }
    std::shared_ptr<Frame> readFrame(uint64_t after, uint64_t epoch) {
        TryLock lock(mutex_);
        if (!lock || !compatible())
            return {};
        auto &h = header();
        if (!h.newest || h.newest == after)
            return {};
        auto slot = bytes_ + sizeof(Header) + (h.newest % slots) * slotBytes;
        FrameMeta m;
        std::memcpy(&m, slot, sizeof(m));
        if (!valid(m) || m.sequence != h.newest || m.epoch != epoch || !fresh(GetTickCount64(), m.tickMs) ||
            !fresh(GetTickCount64(), m.controlTickMs))
            return {};
        auto frame = std::make_shared<Frame>();
        frame->meta = m;
        frame->pixels.assign(slot + sizeof(m), slot + sizeof(m) + frameBytes(m));
        return frame;
    }
};
} // namespace bridge
