#include "../bridge/shared_memory.hpp"
#include <algorithm>
#include <iostream>
int wmain(int argc, wchar_t **argv) {
    if (argc != 2)
        return 2;
    bridge::SharedMemory m;
    if (!m.open(argv[1]))
        return 3;
    bridge::Control c;
    c.sequence = 1;
    c.epoch = 987;
    c.flags = bridge::Scene | bridge::Focus;
    c.forward[2] = 1;
    c.command = 77;
    c.textSequence = 1;
    c.text[0] = 0x4e2d;
    bridge::InputPacket input;input.epoch=c.epoch;input.sequence=2;input.dx=17;input.dy=-9;
    input.events[0]={2,0,1,1,.25f,.75f,0,0};
    input.events[1]={2,0,0,1,.26f,.76f,0,0}; // complete quick click between two MC polls
    bridge::TerrainPacket terrain;terrain.sequence=1;terrain.epoch=c.epoch;
    terrain.center={12,4,8};terrain.hits[40]=1;terrain.heights[40]=3.5f;
    auto deadline = GetTickCount64() + 7000;
    while (GetTickCount64() < deadline) {
        c.tickMs = GetTickCount64();
        m.writeControl(c);
        input.tick=c.tickMs;terrain.tick=c.tickMs;
        m.input.write(input);m.terrain.write(terrain);
        auto f = m.readFrame(0, c.epoch);
        bridge::PhysicsPacket physics;
        bridge::PlayerPacket player;
        if (f && m.physics.read(physics) && m.player.read(player) && bridge::validPlayer(player)) {
            bool ok = f->pixels.size() == 4 * 4 * 12 &&
                      std::all_of(f->pixels.begin(), f->pixels.end(), [](uint8_t b) { return b == 0x6b; });
            if (!ok)
                return 4;
            if (physics.sequence != 99 || physics.epoch != c.epoch || physics.flags != 7 ||
                physics.count != 1 || physics.radius != .3f || physics.height != 1.8f ||
                physics.shapes[0].max.y != .5f) return 6;
            if(player.sequence!=77 || player.flags!=5 || player.position.y!=4 ||
               player.eye.y!=5.62f || player.forward.z!=1 || player.velocity.x!=4)return 7;
            std::cout << "Cross-process frame, physics, complete click stream, terrain and MC player/camera ABI passed\n";
            return 0;
        }
        Sleep(5);
    }
    std::cerr << "JNI peer timed out\n";
    return 5;
}
