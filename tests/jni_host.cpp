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
    auto deadline = GetTickCount64() + 7000;
    while (GetTickCount64() < deadline) {
        c.tickMs = GetTickCount64();
        m.writeControl(c);
        auto f = m.readFrame(0, c.epoch);
        if (f) {
            bool ok = f->pixels.size() == 4 * 4 * 12 &&
                      std::all_of(f->pixels.begin(), f->pixels.end(), [](uint8_t b) { return b == 0x6b; });
            if (!ok)
                return 4;
            std::cout << "Cross-process C++ -> Java JNI -> C++ color/depth/overlay passed\n";
            return 0;
        }
        Sleep(5);
    }
    std::cerr << "JNI peer timed out\n";
    return 5;
}
