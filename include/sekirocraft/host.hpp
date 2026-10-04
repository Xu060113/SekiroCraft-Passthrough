#pragma once
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include "math.hpp"
#include "owned_bit.hpp"
#include "vitals.hpp"
#include <windows.h>
#include <bcrypt.h>
#include <filesystem>
#include <fstream>
#include <mutex>
#include <sstream>
#include <string>

namespace sc {
inline std::filesystem::path dataRoot;
inline std::mutex logMutex;
inline void log(const std::string &s) {
    std::lock_guard guard(logMutex);
    if (dataRoot.empty())
        return;
    std::error_code ec;
    std::filesystem::create_directories(dataRoot, ec);
    std::ofstream out(dataRoot / L"passthrough.log", std::ios::app);
    SYSTEMTIME t;
    GetLocalTime(&t);
    out << t.wHour << ":" << t.wMinute << ":" << t.wSecond << " " << s << "\n";
}
template <class T> inline bool readMemory(uintptr_t addr, T &value) {
    SIZE_T got = 0;
    return addr > 65536 &&
           ReadProcessMemory(GetCurrentProcess(), reinterpret_cast<void *>(addr), &value, sizeof(T), &got) &&
           got == sizeof(T);
}
inline std::string sha256(const std::filesystem::path &path) {
    BCRYPT_ALG_HANDLE alg{};
    BCRYPT_HASH_HANDLE hash{};
    if (BCryptOpenAlgorithmProvider(&alg, BCRYPT_SHA256_ALGORITHM, nullptr, 0) < 0)
        return {};
    DWORD size{}, got{};
    BCryptGetProperty(alg, BCRYPT_OBJECT_LENGTH, reinterpret_cast<PUCHAR>(&size), sizeof(size), &got, 0);
    std::vector<uint8_t> object(size);
    std::array<uint8_t, 32> digest{};
    std::ifstream file(path, std::ios::binary);
    bool ok = file.good() && BCryptCreateHash(alg, &hash, object.data(), size, nullptr, 0, 0) >= 0;
    std::array<char, 65536> block{};
    while (ok && file) {
        file.read(block.data(), block.size());
        auto n = file.gcount();
        if (n && BCryptHashData(hash, reinterpret_cast<PUCHAR>(block.data()), ULONG(n), 0) < 0)
            ok = false;
    }
    if (file.bad())
        ok = false;
    if (ok)
        ok = BCryptFinishHash(hash, digest.data(), digest.size(), 0) >= 0;
    if (hash)
        BCryptDestroyHash(hash);
    BCryptCloseAlgorithmProvider(alg, 0);
    if (!ok)
        return {};
    std::string result;
    constexpr char digits[] = "0123456789abcdef";
    for (auto b : digest) {
        result += digits[b >> 4];
        result += digits[b & 15];
    }
    return result;
}
struct PlayerSnapshot {
    Vec3 position{};
    uintptr_t generation{};
    bool valid{};
};
class GameHost {
  public:
    bool initialize() {
        wchar_t path[32768]{};
        GetModuleFileNameW(nullptr, path, 32768);
        executable_ = path;
        base_ = reinterpret_cast<uintptr_t>(GetModuleHandleW(nullptr));
        IMAGE_DOS_HEADER dos{};
        IMAGE_NT_HEADERS64 nt{};
        bool header = readMemory(base_, dos) && dos.e_magic == IMAGE_DOS_SIGNATURE && dos.e_lfanew > 0 &&
                      dos.e_lfanew < 4096 && readMemory(base_ + dos.e_lfanew, nt) &&
                      nt.Signature == IMAGE_NT_SIGNATURE;
        auto digest = sha256(executable_);
        supported_ = header && nt.FileHeader.Machine == IMAGE_FILE_MACHINE_AMD64 &&
                     nt.OptionalHeader.SizeOfImage == 70066176 &&
                     digest == "637aca527538c0ec6e1f136c8ed66046e95dfbdbb1f51926e134d9916398b856";
        log("executable=" + executable_.string() + " sha256=" + digest +
            " supported=" + (supported_ ? "yes" : "no"));
        return supported_;
    }
    PlayerSnapshot player() const {
        PlayerSnapshot s;
        if (!supported_)
            return s;
        uintptr_t root{}, a{}, b{};
        // These are documented 1.06 offsets; access is read-only and gated by the
        // exact local executable fingerprint, not merely the version label.
        if (!readMemory(base_ + 0x3d7a1e0, root) || !readMemory(root + 0x48, a) || !readMemory(a + 0x28, b))
            return s;
        std::array<float, 4> p{};
        if (!readMemory(b + 0x80, p))
            return s;
        s.position = {p[0], p[1], p[2]};
        s.generation = b;
        s.valid = finite(s.position) && std::abs(s.position.x) < 100000.f &&
                  std::abs(s.position.y) < 100000.f && std::abs(s.position.z) < 100000.f;
        return s;
    }
    bool supported() const { return supported_; }
    uintptr_t base() const { return supported_ ? base_ : 0; }
    Vitals vitals() const {
        return readVitals(base_, supported_,
                          [](uintptr_t address, auto &value) { return readMemory(address, value); });
    }
    bool avatarVisibility(bool hideOriginal) {
        if (!hideOriginal)
            return avatarHide_.release();
        // ElaDiDu's 1.06 practice table: player ChrIns + 0x1a11, bit 3 = Draw.
        // DebugFlags.player_hide is AI concealment, not mesh visibility.
        if (!supported_ || !player().valid)
            return false;
        uintptr_t root{}, hero{};
        if (!readMemory(base_ + 0x3d7a1e0, root) || !readMemory(root + 0x88, hero) || hero < 65536)
            return false;
        return avatarHide_.acquire(hero + 0x1a11, 8, false);
    }
    std::optional<Camera> camera(const PlayerSnapshot &player) const {
        if (!supported_ || !player.valid)
            return {};
        uintptr_t field{}, render{}, perspectiveCamera{};
        // Verified read-only in the running local 1.06 executable. FieldArea's
        // global also matched the unique loaded-code signature. GameRend owns
        // SprjPersCam, whose pose and lens live at +0x10 and +0x50 respectively.
        if (!readMemory(base_ + 0x3d5c0a0, field) || !readMemory(field + 0x20, render) ||
            !readMemory(render + 0x18, perspectiveCamera))
            return {};
        Mat4 pose{};
        std::array<float, 4> lens{};
        if (!readMemory(perspectiveCamera + 0x10, pose) || !readMemory(perspectiveCamera + 0x50, lens))
            return {};
        return cameraFromNativePose(pose, lens, player.position);
    }

  private:
    uintptr_t base_{};
    bool supported_{};
    std::filesystem::path executable_;
    OwnedBit avatarHide_;
};
} // namespace sc
