#include "../bridge/shared_memory.hpp"
#include <iostream>
#include <thread>
#include <atomic>
#include <fstream>
#include <algorithm>
int checks{};
void require(bool b, const char *name) {
    ++checks;
    if (!b) {
        std::cerr << "FAIL " << name << "\n";
        std::exit(1);
    }
}
bridge::Control pose() {
    bridge::Control c;
    c.sequence = 1;
    c.epoch = 123;
    c.tickMs = GetTickCount64();
    c.flags = bridge::Scene | bridge::Focus;
    c.forward[2] = 1;
    return c;
}
bridge::FrameMeta metadata(const bridge::Control &c) {
    bridge::FrameMeta m;
    m.sequence = 1;
    m.epoch = c.epoch;
    m.controlTickMs = c.tickMs;
    m.width = 4;
    m.height = 4;
    m.forward[2] = 1;
    m.fovY = c.fovY;
    m.aspect = c.aspect;
    m.nearZ = c.nearZ;
    m.farZ = c.farZ;
    return m;
}
int main(int argc, char **argv) {
    bridge::SharedMemory host, mc;
    auto name = L"fixture-" + std::to_wstring(GetCurrentProcessId());
    require(!host.open(L"../invalid"), "invalid channel rejected");
    require(host.open(name), "host mapping");
    require(mc.open(name), "peer mapping");
    auto c = pose();
    require(host.writeControl(c), "write control");
    bridge::Control got;
    require(mc.readControl(got) && got.epoch == 123 && got.forward[2] == 1, "control ABI round trip");
    require(
        (c.capabilities & (bridge::NativeBlockCollision | bridge::NativeCombat | bridge::GroundColumns)) == 0,
        "unsupported capabilities never claimed");
    require(!bridge::fresh(9, 10) && !bridge::fresh(1000, 1) && bridge::fresh(100, 99),
            "shared boot clock freshness");
    auto bad = c;
    bad.eye[0] = NAN;
    require(!host.writeControl(bad), "NaN control rejected");
    bad = c;
    bad.scale = 0;
    require(!host.writeControl(bad), "invalid scale rejected");
    auto m = metadata(c);
    std::vector<uint8_t> pixels(bridge::frameBytes(m), 42);
    require(!mc.publish(m, {pixels.data(), pixels.size() - 1}), "truncated frame rejected");
    auto invalid = m;
    invalid.width = bridge::maxWidth + 1;
    require(!mc.publish(invalid, pixels), "oversized frame rejected");
    invalid = m;
    invalid.flags = 1024;
    require(!mc.publish(invalid, pixels), "unknown frame flags rejected");
    invalid = m;
    invalid.epoch = 456;
    require(!mc.publish(invalid, pixels), "wrong epoch rejected");
    require(mc.publish(m, pixels), "publish frame");
    auto f = host.readFrame(0, c.epoch);
    require(f && f->meta.sequence == 1 && f->pixels == pixels, "color/depth/overlay round trip");
    require(!host.readFrame(1, c.epoch) && !host.readFrame(0, 456), "duplicate and stale epoch ignored");
    require(mc.status(3, c.epoch) && host.readStatus(c.epoch) == 3 && host.readStatus(456) == 0,
            "peer status tied to epoch");
    for (int i = 0; i < 8; ++i) {
        c.tickMs = GetTickCount64();
        require(host.writeControl(c), "refresh heartbeat");
        m.controlTickMs = c.tickMs;
        require(mc.publish(m, pixels), "slot wrap/restarted producer");
    }
    f = host.readFrame(1, c.epoch);
    require(f && f->meta.sequence == 9, "mapping owns sequence across producer restarts");
    HANDLE gate = CreateMutexW(nullptr, FALSE, (L"Local\\SekiroBridge-" + name + L"-lock").c_str());
    std::atomic<bool> locked = false, release = false;
    std::thread holder([&] {
        WaitForSingleObject(gate, INFINITE);
        locked = true;
        while (!release)
            Sleep(1);
        ReleaseMutex(gate);
    });
    while (!locked)
        Sleep(1);
    auto before = GetTickCount64();
    require(!host.writeControl(c) && !host.readFrame(0, c.epoch), "busy peer skips work");
    require(GetTickCount64() - before < 100, "render-side IPC never waits");
    release = true;
    holder.join();
    CloseHandle(gate);
    c.epoch = 456;
    c.tickMs = GetTickCount64();
    require(host.writeControl(c), "new session");
    require(!host.readFrame(0, c.epoch), "previous session image cannot leak");
    m = metadata(c);
    m.controlTickMs = 1;
    require(!mc.publish(m, pixels), "old captured control rejected even with new publication time");
    m = metadata(c);
    std::atomic<bool> stop = false;
    std::thread producer([&] {
        for (int i = 0; i < 100; ++i) {
            std::fill(pixels.begin(), pixels.end(), uint8_t(i));
            mc.publish(m, pixels);
            Sleep(1);
        }
        stop = true;
    });
    uint64_t previous = 0;
    int observed = 0;
    while (!stop) {
        auto frame = host.readFrame(previous, c.epoch);
        if (frame) {
            previous = frame->meta.sequence;
            ++observed;
            require(std::all_of(frame->pixels.begin(), frame->pixels.end(),
                                [&](uint8_t p) { return p == frame->pixels[0]; }),
                    "concurrent frame has no torn planes");
        }
        Sleep(1);
    }
    producer.join();
    require(observed > 0, "concurrent publisher and receiver exercised");
    if (argc > 1) {
        c = pose();
        c.player[0] = 12.5;
        c.player[1] = -4;
        c.player[2] = 8;
        c.command = 77;
        c.textSequence = 2;
        c.text[0] = 0x4e2d;
        c.text[1] = 0x6587;
        std::ofstream out(argv[1], std::ios::binary);
        out.write(reinterpret_cast<char *>(&c), sizeof(c));
        require(out.good(), "write cross-language binary fixture");
    }
    std::cout << checks << " bridge checks passed\n";
}
