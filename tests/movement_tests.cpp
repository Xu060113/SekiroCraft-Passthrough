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
            flags |= 8;
            check(draw.acquire(reinterpret_cast<uintptr_t>(&flags),8,false,true) && !(flags&8),
                  "engine attack resets draw bit, owned visibility reasserts hiding");
            check(draw.release() && flags == 0b10101001, "restore draw preserving foreign flags");
        }
        {flags=1;sc::OwnedBit draw;draw.acquire(reinterpret_cast<uintptr_t>(&flags),8,false,true);
            flags|=8;draw.acquire(reinterpret_cast<uintptr_t>(&flags),8,false,true);draw.release();
            check(flags==1,"originally hidden model remains hidden after release");}
        bridge::PhysicsPacket p;
        p.tick = p.controlTick = GetTickCount64(); p.epoch = 7; p.sequence = 1; p.flags = 1; p.count = 1;
        p.coverage={{-10,-10,-10},{10,10,10}};
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
        auto npcFloor=bridge::constrainNpc({0,2,0},{0,0,0},p,7,p.tick);
        check(std::abs(npcFloor.y-.5f)<.001f,"NPC lands on actual slab shape");
        check(bridge::constrainNpc({0,2,0},{0,0,0},p,8,p.tick).y==0,"previous-world NPC shapes ignored");
        check(bridge::constrainNpc({0,2,0},{0,0,0},p,7,p.tick+251).y==0,"stale NPC collision expires");
        check(bridge::constrainNpc({0,2,0},{0,-8,0},p,7,p.tick).y==-8,"scripted NPC teleport retained");
        check(bridge::constrainNpc({9.9f,2,0},{9.9f,0,0},p,7,p.tick).y==0,"NPC entire body must be inside coverage");
        p.flags=0;check(bridge::constrainNpc({0,2,0},{0,0,0},p,7,p.tick).y==0,"incomplete snapshots never become a partial wall");p.flags=1;
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
        // Run the production NPC callback against verified fake module chains,
        // not just the standalone sweep. The same hook still excludes teleports.
        {
            std::vector<uint8_t> image(0x3d7a200),root(0x100),npc(0x2100),modules(0x80),physics(0x1000);
            auto ptr=[](auto &v){return reinterpret_cast<uintptr_t>(v.data());};
            auto put=[](auto &v,size_t at,auto value){std::memcpy(v.data()+at,&value,sizeof(value));};
            auto base=ptr(image);
            const uint8_t store[]{0x0f,0x29,0xb6,0x80,0,0,0};
            const uint8_t timer[]{0xf3,0x0f,0x58,0x87,0xd0,8,0,0};
            std::memcpy(image.data()+0xbc4796,store,7);
            std::fill_n(image.data()+0xbc479d,32,uint8_t(0x90));image[0xbc47bd]=0xc3;
            std::memcpy(image.data()+0xbc3633,timer,8);
            DWORD old{};check(VirtualProtect(image.data()+0xbc4000,4096,PAGE_EXECUTE_READWRITE,&old),"executable NPC hook fixture");
            put(image,0x3d7a1e0,ptr(root));put(root,0x88,base+0x1000);
            put(physics,8,ptr(npc));put(npc,0x1ff8,ptr(modules));put(modules,0x68,ptr(physics));
            put(physics,0x80,sc::Vec3{0,1,0});
            p.shapes[0]={{1,0,-1},{2,3,1}};p.tick=p.controlTick=GetTickCount64();producer.write(p);
            bridge::Control c;c.epoch=7;c.tickMs=p.tick;c.capabilities=bridge::mcOwnerCapability;
            bridge::NativeMovement movement;movement.initialize(base,consumer);movement.tryInstall();
            check(movement.installed(),"install actual production movement hook in fixture");movement.update(c,true);
            float candidate[4]{2,1,.5f,1};movement.sample(ptr(physics),candidate);
            check(std::abs(candidate[0]-.7f)<.001f && candidate[2]==.5f && movement.npcCorrections==1,
                  "production callback constrains NPC while MC owns player");
            put(modules,0x68,uintptr_t(0));candidate[0]=2;movement.sample(ptr(physics),candidate);
            check(candidate[0]==2,"reject owner/module mismatch before NPC writes");
            MH_DisableHook(reinterpret_cast<void*>(base+0xbc4796));MH_RemoveHook(reinterpret_cast<void*>(base+0xbc4796));
            VirtualProtect(image.data()+0xbc4000,4096,old,&old);
        }
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
        for(auto messages : {std::array<UINT,4>{WM_LBUTTONDOWN,WM_LBUTTONUP,WM_LBUTTONDBLCLK,WM_LBUTTONUP},
                std::array<UINT,4>{WM_RBUTTONDOWN,WM_RBUTTONUP,WM_RBUTTONDBLCLK,WM_RBUTTONUP},
                std::array<UINT,4>{WM_MBUTTONDOWN,WM_MBUTTONUP,WM_MBUTTONDBLCLK,WM_MBUTTONUP}}){
            unsigned presses{},releases{};uint32_t button{},action{};
            for(auto message:messages){check(bridge::mouseButtonEvent(message,button,action),"record each Windows click edge");
                if(action)++presses;else ++releases;}
            check(presses==2 && releases==2,"a Windows double click remains two complete clicks");
        }
        check(bridge::terrainCellCenter(.01f)==bridge::terrainCellCenter(.49f) &&
              bridge::terrainCellCenter(-.01f)==bridge::terrainCellCenter(-.49f),
              "slope samples stay at fixed world cells while the player moves");
        check(bridge::terrainCellCenter(-.51f)==-.75f && bridge::terrainCellCenter(.51f)==.75f,
              "negative terrain coordinates use floor instead of truncation");
        bridge::CameraFrames cameraFrames;
        auto completed=std::make_shared<bridge::Frame>();
        auto &meta=completed->meta;meta.sequence=1;meta.epoch=7;meta.tickMs=meta.controlTickMs=1000;
        meta.width=meta.height=1;meta.eye[1]=1.62f;meta.forward[2]=1;
        meta.fovY=1.1f;meta.aspect=16.f/9;meta.nearZ=.05f;meta.farZ=500;
        cameraFrames.receive(completed);
        auto selected=cameraFrames.select(7,1010);
        check(selected==completed,"native camera selects an already completed image");
        cameraFrames.applied(selected);
        std::shared_ptr<bridge::Frame> displayed;
        cameraFrames.takeDisplayed(displayed);
        auto newer=std::make_shared<bridge::Frame>(*completed);newer->meta.sequence=2;newer->meta.eye[1]+=1;
        cameraFrames.receive(newer);cameraFrames.takeDisplayed(displayed);
        check(displayed==completed,"a new jump image cannot replace the rendered native scene image");
        selected=cameraFrames.select(7,1020);
        check(selected==newer && displayed==completed,"selecting a new camera does not publish before native writes succeed");
        cameraFrames.applied(selected);cameraFrames.takeDisplayed(displayed);
        check(displayed==newer,"native scene and MC image advance together after applying the camera");
        auto recordedPose=bridge::frameCameraPose(newer->meta);
        check(recordedPose.at(3,1)==newer->meta.eye[1],"completed image owns the displayed jump camera height");
        check(!cameraFrames.select(8,1020) && !cameraFrames.select(7,1400),"stale and previous-scene images cannot drive camera");
        cameraFrames.applied({});cameraFrames.takeDisplayed(displayed);
        check(!displayed,"failed camera update explicitly clears paired image");
        MH_DisableHook(MH_ALL_HOOKS); MH_Uninitialize();
        std::cout << "PASS " << checks << " player draw, collision shapes, mailbox and real x64 movement detour checks\n";
        return 0;
    } catch (const std::exception &e) { std::cerr << "FAIL " << e.what() << "\n"; return 1; }
}
