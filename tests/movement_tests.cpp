#include "../src/movement_hook.hpp"
#include <iostream>
#include <stdexcept>
extern "C" int scTestMove(void *, const float *);
extern "C" void scTestStore();
extern "C" void scTestClobber(uintptr_t, float *) noexcept;
extern "C" int scTestCameraMove(void *,const float *);
extern "C" void scTestCameraStore();
extern "C" void scTestCameraClobber(uintptr_t) noexcept;
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
        scCameraHandler=scTestCameraClobber;
        check(MH_CreateHook(reinterpret_cast<void*>(scTestCameraStore),reinterpret_cast<void*>(scCameraEntry),
            &scCameraContinue)==MH_OK && MH_EnableHook(reinterpret_cast<void*>(scTestCameraStore))==MH_OK,
            "actual camera mid-instruction detour");
        check(scTestCameraMove(module.data(),values)==1 && module[0]==0x34 && module[1]==0x12,
            "camera callback receives RDI while all live CPU state survives");
        std::memcpy(position,module.data()+128,16);
        check(position[0]==1 && position[1]==2 && position[2]==3 && position[3]==1,"camera callback preserves native SIMD store");
        bridge::PlayerPacket player;player.sequence=1;player.epoch=7;player.flags=1;
        player.eye={0,1.62f,0};player.yaw=37;player.forward={0,1,0};
        player.fov=1.1f;player.aspect=16.f/9;player.nearZ=.05f;player.farZ=500;
        check(bridge::validPlayer(player),"vertical pitch retains yaw");
        auto pose=bridge::playerCameraPose(player);
        check(sc::cameraFromNativePose(pose,{1.1f,16.f/9,.05f,500},{}).has_value(),"look straight up has an invertible native camera");
        check(std::abs(pose.at(0,0)+std::cos(37.f*3.14159265f/180))<.0001f,"vertical view keeps MC horizontal heading");
        player.forward={0,0,1};player.yaw=0;
        check(!bridge::validPlayer(player),"inconsistent camera yaw and forward rejected before native writes");
        bridge::InputPacket events;events.tick=1;events.epoch=7;events.sequence=2;
        events.events[0]={2,0,1,1,.25f,.75f};events.events[1]={2,0,0,1,.26f,.76f};
        check(bridge::validInput(events),"complete quick click and individual locations");
        events.events[1].code=4;check(!bridge::validInput(events),"invalid mouse button rejected");
        auto center=bridge::guiPosition(960,600,1920,1200,16.f/9);
        check(std::abs(center[0]-.5f)<.0001f && std::abs(center[1]-.5f)<.0001f,
            "GUI coordinates use real client size even when the render buffer differs");
        auto corner=bridge::guiPosition(480,330,1920,1200,16.f/9);
        check(std::abs(corner[0]-.25f)<.0001f && std::abs(corner[1]-.25f)<.0001f,
            "GUI clicks respect vertical letterbox margins");
        MH_DisableHook(MH_ALL_HOOKS); MH_Uninitialize();
        std::cout << "PASS " << checks << " player draw, collision shapes, mailbox and real x64 movement detour checks\n";
        return 0;
    } catch (const std::exception &e) { std::cerr << "FAIL " << e.what() << "\n"; return 1; }
}
