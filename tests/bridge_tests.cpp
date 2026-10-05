#include "../bridge/shared_memory.hpp"
#include "../bridge/latest_frame.hpp"
#include "../src/hotkey.hpp"
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
    bridge::PeerStatus peer;
    require(host.readStatus(peer) && peer.flagsFor(c.epoch, GetTickCount64()) == 3,
            "cache a valid peer heartbeat");
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
    require(!host.readStatus(peer) && peer.flagsFor(c.epoch, peer.tickMs) == 3,
            "busy IPC preserves the cached connection instead of flashing the host");
    require(peer.flagsFor(c.epoch, peer.tickMs + 351) == 0 && peer.flagsFor(456, peer.tickMs) == 0,
            "cached connection still expires and cannot cross a session");
    require(GetTickCount64() - before < 100, "render-side IPC never waits");
    release = true;
    holder.join();
    CloseHandle(gate);
    require(mc.status(0, c.epoch) && host.readStatus(peer) && peer.flagsFor(c.epoch, GetTickCount64()) == 0,
            "explicit bridge off clears the cached connection immediately");
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
    bridge::LatestFrame mailbox;
    auto first = std::make_shared<bridge::Frame>();
    first->meta.sequence = 1;
    std::shared_ptr<bridge::Frame> displayed;
    mailbox.store(first);
    require(mailbox.take(displayed) && displayed == first, "receive an atomic frame snapshot");
    require(!mailbox.take(displayed) && displayed == first, "an empty mailbox retains the last frame");
    mailbox.store(nullptr);
    require(mailbox.take(displayed) && !displayed, "explicit frame reset clears the render snapshot");
    std::atomic<bool> finished = false;
    std::thread publisher([&] {
        for (uint64_t i = 1; i <= 10000; ++i) {
            auto next = std::make_shared<bridge::Frame>();
            next->meta.sequence = i;
            next->pixels.assign(16, uint8_t(i));
            mailbox.store(std::move(next));
        }
        finished = true;
    });
    uint64_t lastSnapshot{};
    bool intact = true;
    do {
        if (mailbox.take(displayed)) {
            intact = intact && displayed && displayed->meta.sequence > lastSnapshot &&
                     displayed->pixels.size() == 16 &&
                     std::all_of(displayed->pixels.begin(), displayed->pixels.end(),
                                 [&](uint8_t p) { return p == uint8_t(displayed->meta.sequence); });
            lastSnapshot = displayed ? displayed->meta.sequence : 0;
        }
    } while (!finished);
    publisher.join();
    if (mailbox.take(displayed))
        lastSnapshot = displayed->meta.sequence;
    require(intact && lastSnapshot == 10000, "concurrent atomic handoff keeps complete ordered snapshots");
    bridge::KeyEdge hotkey;
    bridge::NativeAnimation idle{100,200,0,true},finisher{100,200,790060,true};
    bridge::ActionHandoff handoff;require(handoff.begin(1000,idle),"valid native action starts");
    require(handoff.update(1100,finisher,true),"native animation confirms action without a motion heuristic");
    require(!handoff.begin(1200,idle),"held/repeated action does not restart the ownership clock");
    require(handoff.update(4000,finisher,true),"long finisher remains native until animation ends");
    require(handoff.update(4016,idle,true) && !handoff.update(4032,idle,true),"control returns within two samples without a 6.5s minimum");
    handoff.begin(5000,idle);require(!handoff.update(5500,idle,true),"rejected request does not freeze MC for seconds");
    handoff.begin(6000,idle);require(!handoff.update(6016,finisher,false),"death/focus loss releases handoff");
    handoff.begin(7000,idle);require(!handoff.update(28000,finisher,true),"stuck action has bounded ownership");
    handoff.begin(29000,idle);auto respawn=finisher;respawn.hero=101;
    require(!handoff.update(29016,respawn,true),"new actor identity cannot retain old action ownership");
    require(!handoff.begin(30000,{}),"unreadable native state cannot start a blind timed handoff");
    handoff.begin(31000,idle);handoff.update(31016,finisher,true);
    require(handoff.update(92000,finisher,true,true),"native menu pause does not consume the action watchdog");
    require(handoff.update(92016,idle,true) && !handoff.update(92032,idle,true),"closing a menu retains animation completion tracking");
    bridge::FrameMeta guiMeta;guiMeta.aspect=16.f/9;guiMeta.guiGeneration=8;
    auto guiDisplay=bridge::GuiDisplay::fit(guiMeta,1920,1200,100);
    auto guiCenter=guiDisplay.point(640,400,1280,800);
    require(std::abs(guiCenter[0]-.5f)<.001f && std::abs(guiCenter[1]-.5f)<.001f,"GUI maps logical client pixels to the physical drawn viewport");
    auto guiBar=guiDisplay.point(640,0,1280,800);
    require(guiBar[1]<0,"black bars remain outside MC widgets");
    guiMeta.aspect=4.f/3;auto resized=bridge::GuiDisplay::fit(guiMeta,1920,1080,101);
    auto guiEdge=resized.point(160,360,1280,720);
    require(std::abs(guiEdge[0])<.001f,"GUI coordinates use the displayed frame aspect instead of a newer camera");
    bridge::ProjectileRays rays;rays.sequence=1;rays.tick=100;rays.epoch=123;rays.count=1;rays.rays[0]={9,{1,2,3},{0,-3,0}};
    require(bridge::validRays(rays) && host.projectileRays.write(rays),"projectile ray packet transport");
    bridge::ProjectileRays readRays;require(mc.projectileRays.read(readRays) && readRays.rays[0].id==9,"projectile query identity survives IPC");
    rays.rays[0].delta.x=NAN;require(!bridge::validRays(rays),"nonfinite projectile path rejected");
    bridge::ProjectileHits hits;hits.sequence=1;hits.tick=100;hits.epoch=123;hits.count=1;hits.hits[0]={9,{1,0,3},{0,1,0},1,0};
    require(bridge::validHits(hits) && mc.projectileHits.write(hits),"native projectile contact transport");
    hits.hits[0].normal={0,0,0};require(!bridge::validHits(hits),"invalid native contact normal rejected");
    require(hotkey.update(true, true), "physical and message key edges toggle once");
    require(!hotkey.update(true, false) && !hotkey.update(true, true),
            "a held key and its delayed message cannot toggle back off");
    require(!hotkey.update(false, false) && hotkey.update(true, true),
            "releasing and pressing permits the next toggle");
    require(!hotkey.update(false, false) && hotkey.update(false, true) && !hotkey.update(false, false),
            "a fast message-only tap is consumed once");
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
