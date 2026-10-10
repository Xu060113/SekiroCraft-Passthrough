#include "../src/compositor.hpp"
#include "../bridge/session.hpp"
#include "../src/deferred_scene.hpp"
#include "../bridge/camera_frames.hpp"
#include <iostream>
#include <fstream>
#include <thread>
int checks{};
void require(bool b, const char *label) {
    ++checks;
    if (!b) {
        std::cerr << "FAIL " << label << "\n";
        std::exit(1);
    }
}
int main(int argc, char **argv) {
    {
        bridge::RecordingGate gate;std::mutex present;std::atomic<int> recorded{};
        std::unique_lock busyPresent(present);
        std::thread worker([&]{gate.run(true,present,[&]{++recorded;});});worker.join();
        require(recorded==1,"deferred recording survives a busy Present without losing the command list");
        bool immediate=true;
        std::thread immediateWorker([&]{immediate=gate.run(false,present,[&]{++recorded;});});immediateWorker.join();
        require(!immediate && recorded==1,"immediate capture remains zero-wait while Present owns context");
        busyPresent.unlock();
        require(gate.run(false,present,[&]{++recorded;}) && recorded==2,"immediate recording resumes after Present");
    }
    ComPtr<ID3D11Device> device;
    ComPtr<ID3D11DeviceContext> c;
    require(SUCCEEDED(D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_WARP, nullptr, 0, nullptr, 0,
                                        D3D11_SDK_VERSION, &device, nullptr, &c)),
            "real D3D11 WARP device");
    bridge::Compositor renderer;
    require(renderer.init(device.Get()), renderer.error.c_str());
    require(bridge::opaqueScenePass(c.Get()),"default opaque depth state may nominate native geometry");
    D3D11_DEPTH_STENCIL_DESC opaqueDesc{};opaqueDesc.DepthEnable=TRUE;opaqueDesc.DepthWriteMask=D3D11_DEPTH_WRITE_MASK_ALL;opaqueDesc.DepthFunc=D3D11_COMPARISON_LESS;
    ComPtr<ID3D11DepthStencilState> opaqueState,particleState;
    require(SUCCEEDED(device->CreateDepthStencilState(&opaqueDesc,&opaqueState)),"create real opaque depth state");
    opaqueDesc.DepthWriteMask=D3D11_DEPTH_WRITE_MASK_ZERO;
    require(SUCCEEDED(device->CreateDepthStencilState(&opaqueDesc,&particleState)),"create real particle depth state");
    c->OMSetDepthStencilState(particleState.Get(),0);
    require(!bridge::opaqueScenePass(c.Get()),"depth-reading attack trails cannot replace the opaque scene camera");
    c->OMSetDepthStencilState(opaqueState.Get(),0);
    D3D11_BLEND_DESC effects{};effects.RenderTarget[0].BlendEnable=TRUE;effects.RenderTarget[0].SrcBlend=D3D11_BLEND_SRC_ALPHA;
    effects.RenderTarget[0].DestBlend=D3D11_BLEND_INV_SRC_ALPHA;effects.RenderTarget[0].BlendOp=D3D11_BLEND_OP_ADD;
    effects.RenderTarget[0].SrcBlendAlpha=D3D11_BLEND_ONE;effects.RenderTarget[0].DestBlendAlpha=D3D11_BLEND_ZERO;
    effects.RenderTarget[0].BlendOpAlpha=D3D11_BLEND_OP_ADD;effects.RenderTarget[0].RenderTargetWriteMask=D3D11_COLOR_WRITE_ENABLE_ALL;
    ComPtr<ID3D11BlendState> effectsState;
    require(SUCCEEDED(device->CreateBlendState(&effects,&effectsState)),"create real translucent attack FX blend state");
    c->OMSetBlendState(effectsState.Get(),nullptr,~0u);
    require(!bridge::opaqueScenePass(c.Get()),"blended attack FX cannot nominate a replacement occlusion depth even when writing depth");
    c->OMSetBlendState(nullptr,nullptr,~0u);c->OMSetDepthStencilState(nullptr,0);
    require(bridge::opaqueScenePass(c.Get()),"opaque scene classification resumes after attack FX");
    bridge::SceneDraw invalidCandidate;invalidCandidate.score=10000000;
    require(!bridge::validSceneCandidate(invalidCandidate),"unpaired high-count draws cannot evict a valid MC world snapshot");
    D3D11_TEXTURE2D_DESC d{};
    d.Width = d.Height = 32;
    d.ArraySize = d.MipLevels = d.SampleDesc.Count = 1;
    d.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    d.Usage = D3D11_USAGE_DEFAULT;
    d.BindFlags = D3D11_BIND_RENDER_TARGET;
    ComPtr<ID3D11Texture2D> output, depth;
    ComPtr<ID3D11RenderTargetView> target;
    ComPtr<ID3D11DepthStencilView> dsv;
    require(SUCCEEDED(device->CreateTexture2D(&d, nullptr, &output)) &&
                SUCCEEDED(device->CreateRenderTargetView(output.Get(), nullptr, &target)),
            "host color target");
    d.Format = DXGI_FORMAT_R32_TYPELESS;
    d.BindFlags = D3D11_BIND_DEPTH_STENCIL;
    D3D11_DEPTH_STENCIL_VIEW_DESC vd{};
    vd.Format = DXGI_FORMAT_D32_FLOAT;
    vd.ViewDimension = D3D11_DSV_DIMENSION_TEXTURE2D;
    require(SUCCEEDED(device->CreateTexture2D(&d, nullptr, &depth)) &&
                SUCCEEDED(device->CreateDepthStencilView(depth.Get(), &vd, &dsv)),
            "native scene depth");
    sc::Camera camera{sc::identity(), sc::perspective(1.1f, 1, .05f, 100), {0, 0, 0}, {0, 0, 1}, true};
    bridge::Frame f;
    f.meta.sequence = 1;
    f.meta.epoch = 99;
    f.meta.width = f.meta.height = 32;
    f.meta.forward[2] = 1;
    f.meta.fovY = 1.1;
    f.meta.aspect = 1;
    f.meta.nearZ = .05;
    f.meta.farZ = 100;
    f.pixels.resize(32 * 32 * 12);
    auto plane = 32 * 32 * 4;
    float z = 3, mcDepth = (100.f - 100.f * .05f / z) / (100.f - .05f);
    for (int i = 0; i < 32 * 32; ++i) {
        f.pixels[i * 4] = 255;
        f.pixels[i * 4 + 3] = 255;
        std::memcpy(f.pixels.data() + plane + i * 4, &mcDepth, 4);
    }
    {
        bridge::NativeSceneReadiness readiness;
        bridge::CameraFrames cameras;
        bridge::SceneDraw startup{dsv,{0,0,32,32,0,1},camera,{},120};
        require(!readiness.ready(true,true,false,1),"cold start waits for native geometry");
        require(!cameras.select(99,1000),"cold start has no MC image or applied camera");
        // Run the first unpaired scene through the real deferred metadata path.
        bridge::SceneRecording recording;recording.record(1,startup);
        ComPtr<ID3D11DeviceContext> bootContext;
        require(SUCCEEDED(device->CreateDeferredContext(0,&bootContext)),"create cold-start native recording context");
        ComPtr<ID3D11CommandList> bootList;
        require(SUCCEEDED(bootContext->FinishCommandList(FALSE,&bootList)) &&
                bridge::DeferredScene::attach(bootList.Get(),1,std::move(recording.draws)),"finish first native scene without an MC image");
        c->ExecuteCommandList(bootList.Get(),TRUE);
        auto metadata=bridge::DeferredScene::read(bootList.Get());
        for(const auto &draw:metadata->draws)readiness.observe(draw,1);
        require(readiness.ready(true,true,false,1),"executed unpaired native scene starts the bridge");
        require(!bridge::validSceneCandidate(startup),"bootstrap geometry cannot be used as a paired render snapshot");
        bridge::SceneDraw clear;clear.depth=dsv;clear.clearsDepth=true;
        readiness.observe(clear,1);
        require(readiness.ready(true,true,false,2),"native clear does not deadlock first MC frame export");
        auto malformed=startup;malformed.camera.valid=false;
        readiness.observe(malformed,4);
        require(!readiness.ready(true,true,false,4),"invalid camera cannot keep a stale native scene alive");
        readiness.observe(startup,4);
        require(!readiness.ready(false,true,false,4) && !readiness.ready(true,false,false,4),"menu or unloaded player does not start the bridge");
        require(readiness.ready(true,true,true,10),"native death UI retains the previously observed scene");
        require(!readiness.ready(true,true,false,3),"backward frame index cannot reuse scene readiness");
        bridge::Control control;control.sequence=1;control.epoch=99;control.tickMs=1000;
        control.flags=readiness.ready(true,true,false,4)?bridge::Scene|bridge::Focus:0;
        control.forward[2]=1;
        require(bridge::valid(control) && (control.flags&bridge::Scene),"initial native control permits the peer to produce its first frame");
        auto first=std::make_shared<bridge::Frame>(f);
        first->meta.tickMs=first->meta.controlTickMs=1000;
        cameras.receive(first);auto selected=cameras.select(99,1000);
        require(selected==first,"first completed MC image enters the native camera after bootstrap");
        cameras.applied(selected);std::shared_ptr<bridge::Frame> displayed;cameras.takeDisplayed(displayed);
        startup.frame=displayed;
        require(bridge::validSceneCandidate(startup) && bridge::Compositor::matchesCamera(displayed->meta,startup.camera),"bootstrap transitions to the strict paired world path");
        auto mismatch=startup;mismatch.camera.eye.x+=1;
        require(!bridge::Compositor::matchesCamera(mismatch.frame->meta,mismatch.camera),"startup fix does not allow mismatched frames to composite");
        readiness.reset();
        require(!readiness.ready(true,true,true,5),"resize clears readiness even during native death");
        startup.frame.reset();readiness.observe(startup,5);
        require(readiness.ready(true,true,false,5),"resize can bootstrap again without an existing MC image");
    }
    require(renderer.upload(c.Get(), f), "upload real MC frame planes");
    auto read = [&](UINT x = 16, UINT y = 16) {
        D3D11_TEXTURE2D_DESC staging{};
        output->GetDesc(&staging);
        staging.BindFlags = 0;
        staging.Usage = D3D11_USAGE_STAGING;
        staging.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
        ComPtr<ID3D11Texture2D> copy;
        require(SUCCEEDED(device->CreateTexture2D(&staging, nullptr, &copy)), "readback target");
        c->CopyResource(copy.Get(), output.Get());
        D3D11_MAPPED_SUBRESOURCE mapped{};
        require(SUCCEEDED(c->Map(copy.Get(), 0, D3D11_MAP_READ, 0, &mapped)), "offline GPU readback");
        std::array<uint8_t, 4> value;
        std::memcpy(value.data(), static_cast<uint8_t *>(mapped.pData) + y * mapped.RowPitch + x * 4, 4);
        c->Unmap(copy.Get(), 0);
        return value;
    };
    float blue[]{0, 0, 1, 1};
    auto rt = target.Get();
    c->OMSetRenderTargets(1, &rt, dsv.Get());
    D3D11_VIEWPORT viewport{2, 3, 21, 22, 0, 1};
    c->RSSetViewports(1, &viewport);
    c->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_LINELIST);
    for (bool reverse : {false, true})
        for (float hostZ : {2.f, 5.f}) {
            camera.projection = sc::perspective(1.1f, 1, .05f, 100);
            if (reverse) {
                camera.projection.at(2, 2) = 1 - camera.projection.at(2, 2);
                camera.projection.at(3, 2) = -camera.projection.at(3, 2);
            }
            float hostDepth = camera.projection.at(2, 2) + camera.projection.at(3, 2) / hostZ;
            c->ClearRenderTargetView(target.Get(), blue);
            c->ClearDepthStencilView(dsv.Get(), D3D11_CLEAR_DEPTH, hostDepth, 0);
            require(renderer.draw(c.Get(), target.Get(), dsv.Get(), camera, 32, 32),
                    "depth composite submitted");
            auto pixel = read();
            require(hostZ < z ? pixel[2] > 250 && pixel[0] < 5 : pixel[0] > 250 && pixel[2] < 5,
                    "normal/reverse Z two-way occlusion");
            D3D11_VIEWPORT restored{};
            UINT count = 1;
            c->RSGetViewports(&count, &restored);
            D3D11_PRIMITIVE_TOPOLOGY topology;
            c->IAGetPrimitiveTopology(&topology);
            require(restored.TopLeftX == 2 && restored.Height == 22 &&
                        topology == D3D11_PRIMITIVE_TOPOLOGY_LINELIST,
                    "native viewport/topology restored");
            ComPtr<ID3D11RenderTargetView> oldRT;
            ComPtr<ID3D11DepthStencilView> oldDSV;
            c->OMGetRenderTargets(1, &oldRT, &oldDSV);
            require(oldRT.Get() == target.Get() && oldDSV.Get() == dsv.Get(),
                    "native render targets restored");
        }
    for (int i = 0; i < 32 * 32; ++i) {
        f.pixels[plane * 2 + i * 4 + 1] = 128;
        f.pixels[plane * 2 + i * 4 + 3] = 128;
    }
    ++f.meta.sequence;
    require(renderer.upload(c.Get(), f), "upload translucent HUD");
    c->ClearRenderTargetView(target.Get(), blue);
    c->ClearDepthStencilView(dsv.Get(), D3D11_CLEAR_DEPTH, 1, 0);
    require(renderer.draw(c.Get(), target.Get(), dsv.Get(), camera, 32, 32), "overlay draw");
    auto pixel = read();
    require(pixel[1] >= 126 && pixel[1] <= 130 && pixel[2] >= 125 && pixel[2] <= 130,
            "premultiplied overlay blends once above scene");
    auto empty = f;
    ++empty.meta.sequence;
    std::fill(empty.pixels.begin(), empty.pixels.end(), 0);
    for (int i = 0; i < 32 * 32; ++i) {
        float farDepth = 1;
        std::memcpy(empty.pixels.data() + plane + i * 4, &farDepth, 4);
    }
    require(renderer.upload(c.Get(), empty), "upload transparent void world and HUD");
    c->ClearRenderTargetView(target.Get(), blue);
    require(renderer.draw(c.Get(), target.Get(), dsv.Get(), camera, 32, 32),
            "composite a transparent MC background");
    pixel = read();
    require(pixel[0] < 5 && pixel[1] < 5 && pixel[2] > 250,
            "empty MC background preserves the native image instead of blacking it out");
    auto glow = empty;
    ++glow.meta.sequence;
    for (int i = 0; i < 32 * 32; ++i) {
        glow.pixels[i * 4] = 96;
        glow.pixels[i * 4 + 1] = 48;
        std::memcpy(glow.pixels.data() + plane + i * 4, &mcDepth, 4);
    }
    require(renderer.upload(c.Get(), glow), "upload depth-bearing sword emission with zero coverage");
    c->ClearRenderTargetView(target.Get(), blue);
    c->ClearDepthStencilView(dsv.Get(), D3D11_CLEAR_DEPTH,
                            camera.projection.at(2,2) + camera.projection.at(3,2) / 5, 0);
    require(renderer.draw(c.Get(), target.Get(), dsv.Get(), camera, 32, 32, false, false),
            "sword emission composites against the paired native scene");
    pixel = read();
    require(pixel[0] >= 94 && pixel[0] <= 98 && pixel[1] >= 46 && pixel[1] <= 50 && pixel[2] > 250,
            "sword glow adds to native color without darkening or covering the terrain");
    c->ClearRenderTargetView(target.Get(), blue);
    c->ClearDepthStencilView(dsv.Get(), D3D11_CLEAR_DEPTH,
                            camera.projection.at(2,2) + camera.projection.at(3,2) / 2, 0);
    require(renderer.draw(c.Get(), target.Get(), dsv.Get(), camera, 32, 32, false, false),
            "sword emission uses native wall occlusion");
    pixel = read();
    require(pixel[0] < 5 && pixel[1] < 5 && pixel[2] > 250,
            "sword glow behind a native wall remains invisible");
    ++glow.meta.sequence;
    for (int i = 0; i < 32 * 32; ++i) {
        float cleared = 1;
        std::memcpy(glow.pixels.data() + plane + i * 4, &cleared, 4);
    }
    require(renderer.upload(c.Get(), glow), "upload glow with missing depth to reproduce old color-only capture");
    c->ClearRenderTargetView(target.Get(), blue);
    c->ClearDepthStencilView(dsv.Get(), D3D11_CLEAR_DEPTH, 1, 0);
    require(renderer.draw(c.Get(), target.Get(), dsv.Get(), camera, 32, 32, false, false),
            "invalid sword depth still fails closed");
    pixel = read();
    require(pixel[0] < 5 && pixel[1] < 5 && pixel[2] > 250,
            "depthless color is not flattened onto every native surface");
    f.meta.sequence = glow.meta.sequence + 1;
    require(renderer.upload(c.Get(), f), "restore nonempty HUD fixture");
    camera.eye.x = 3;
    require(!renderer.draw(c.Get(), target.Get(), dsv.Get(), camera, 32, 32),
            "camera discontinuity drops frame");
    camera.eye.x = .05f;
    require(renderer.draw(c.Get(), target.Get(), dsv.Get(), camera, 32, 32, true),
            "translation reprojection shader executes");
    auto bad = f;
    bad.meta.width = 0;
    require(!renderer.upload(c.Get(), bad), "bad upload is rejected");
    auto vertical=f;
    ++vertical.meta.sequence;vertical.meta.flags|=bridge::ExplicitYaw;
    vertical.meta.reserved=std::bit_cast<uint32_t>(37.f);
    vertical.meta.forward[0]=0;vertical.meta.forward[1]=1;vertical.meta.forward[2]=0;
    bridge::PlayerPacket player;player.yaw=37;player.forward={0,1,0};
    auto pose=bridge::playerCameraPose(player);
    camera.view=*sc::inverse(pose);camera.eye={};camera.forward={0,1,0};
    require(renderer.upload(c.Get(),vertical),"vertical capture carries yaw without a gimbal singularity");
    c->ClearRenderTargetView(target.Get(),blue);
    c->ClearDepthStencilView(dsv.Get(),D3D11_CLEAR_DEPTH,camera.projection.at(2,2)+camera.projection.at(3,2)/5,0);
    require(renderer.draw(c.Get(),target.Get(),dsv.Get(),camera,32,32,true),"vertical reprojection shader executes");
    pixel=read();
    require(pixel[0]>120 && pixel[1]>120 && pixel[2]<10,"look straight up retains world geometry and HUD");
    // Completed-frame camera ownership removes the need to reconstruct moving
    // entities from an older depth map. Exercise separate walk/jump poses and a
    // moving avatar silhouette with sharp edges, rather than a full-screen plane.
    auto paired = f;
    paired.meta.sequence = vertical.meta.sequence;
    paired.meta.flags = bridge::Overlay | bridge::ExplicitYaw;
    paired.meta.reserved = std::bit_cast<uint32_t>(180.f);
    paired.meta.forward[0] = paired.meta.forward[1] = 0;
    paired.meta.forward[2] = 1;
    camera.projection = sc::perspective(1.1f, 1, .05f, 100);
    int previousAvatarX = -1;
    for (int step = 0; step < 3; ++step) {
        ++paired.meta.sequence;
        paired.meta.eye[0] = step * .35f;
        paired.meta.eye[1] = step == 1 ? .8f : 0.f;
        paired.meta.eye[2] = step * .1f;
        player.yaw = 180;
        player.forward = {0, 0, 1};
        player.eye = {paired.meta.eye[0], paired.meta.eye[1], paired.meta.eye[2]};
        auto pairedPose = bridge::playerCameraPose(player);
        camera.view = *sc::inverse(pairedPose);
        camera.eye = player.eye;
        camera.forward = {0, 0, 1};
        std::fill(paired.pixels.begin(), paired.pixels.end(), 0);
        int avatarX = step == 1 ? 23 : 15;
        for (int y = 0; y < 32; ++y)
            for (int x = 0; x < 32; ++x) {
                int i = y * 32 + x;
                bool block = x >= 5 && x <= 10 && y >= 8 && y <= 23;
                bool avatar = x >= avatarX && x < avatarX + 4 && y >= 10 && y <= 21;
                float pixelDepth = block || avatar ? mcDepth : 1.f;
                std::memcpy(paired.pixels.data() + plane + i * 4, &pixelDepth, 4);
                if (block || avatar) {
                    paired.pixels[i * 4 + (avatar ? 1 : 0)] = 255;
                    paired.pixels[i * 4 + 3] = 255;
                }
            }
        require(renderer.upload(c.Get(), paired), "upload paired movement capture");
        require(bridge::Compositor::matchesCamera(paired.meta, camera),
                "completed frame camera matches during walking and jumping");
        c->ClearRenderTargetView(target.Get(), blue);
        c->ClearDepthStencilView(dsv.Get(), D3D11_CLEAR_DEPTH,
                                camera.projection.at(2, 2) + camera.projection.at(3, 2) / 5, 0);
        require(renderer.draw(c.Get(), target.Get(), dsv.Get(), camera, 32, 32, false),
                "paired movement frame bypasses temporal reprojection");
        auto avatar = read(avatarX + 1, 16);
        require(avatar[1] > 250 && avatar[0] < 5 && avatar[2] < 5,
                "current moving avatar renders once at the captured position");
        auto outsideBlock = read(4, 16), insideBlock = read(5, 16);
        require(outsideBlock[2] > 250 && outsideBlock[0] < 5 &&
                    insideBlock[0] > 250 && insideBlock[2] < 5,
                "walking and jumping preserve the sharp captured block boundary");
        if (previousAvatarX >= 0) {
            auto oldAvatar = read(previousAvatarX + 1, 16);
            require(oldAvatar[2] > 250 && oldAvatar[1] < 5,
                    "previous avatar silhouette is not retained as a movement trail");
        }
        previousAvatarX = avatarX;
        auto mismatched = camera;
        mismatched.eye.y += .03f;
        require(!renderer.draw(c.Get(), target.Get(), dsv.Get(), mismatched, 32, 32, false),
                "paired path rejects the next jump camera with an older completed image");
        mismatched = camera;
        mismatched.projection.at(0, 0) *= 1.02f;
        require(!bridge::Compositor::matchesCamera(paired.meta, mismatched),
                "paired path rejects mismatched sprint FOV");
    }
    auto rollMismatch = camera;
    rollMismatch.view.at(0, 0) = -rollMismatch.view.at(0, 0);
    rollMismatch.view.at(1, 1) = -rollMismatch.view.at(1, 1);
    require(!bridge::Compositor::matchesCamera(paired.meta, rollMismatch),
            "paired path checks camera right axis as well as its forward direction");
    ComPtr<ID3D11DeviceContext> deferred;
    require(SUCCEEDED(device->CreateDeferredContext(0, &deferred)),
            "real D3D11 deferred recording context");
    auto recordList = [&](float hostZ, uint64_t sequence) {
        auto captured = std::make_shared<bridge::Frame>(paired);
        captured->meta.sequence = sequence;
        bridge::SceneRecording recording;
        bridge::SceneDraw clear;
        clear.depth = dsv;
        clear.clearsDepth = true;
        recording.record(1, clear);
        bridge::SceneDraw draw{dsv, viewport, camera, captured, 120};
        recording.record(1, draw);
        recording.record(1, draw);
        require(recording.draws.size() == 2 && recording.draws[0].clearsDepth &&
                    recording.draws[1].score == 240,
                "deferred capture keeps clear ordering and combines the same camera draws");
        deferred->ClearDepthStencilView(dsv.Get(), D3D11_CLEAR_DEPTH,
                                        camera.projection.at(2, 2) + camera.projection.at(3, 2) / hostZ, 0);
        ComPtr<ID3D11CommandList> list;
        // Calling the same standard COM slots used by the installed hooks also
        // verifies their ABI against an actual D3D11 implementation.
        using Finish = HRESULT(STDMETHODCALLTYPE *)(ID3D11DeviceContext *, BOOL, ID3D11CommandList **);
        auto table = *reinterpret_cast<void ***>(deferred.Get());
        auto finish = reinterpret_cast<Finish>(table[114]);
        require(SUCCEEDED(finish(deferred.Get(), FALSE, &list)), "FinishCommandList slot 114 ABI");
        require(bridge::DeferredScene::attach(list.Get(), 7, std::move(recording.draws)),
                "command list owns its completed recording metadata");
        return list;
    };
    auto firstList = recordList(2, paired.meta.sequence + 1);
    auto secondList = recordList(5, paired.meta.sequence + 2);
    auto executeList = [&](ID3D11CommandList *list, uint64_t expectedSequence, bool visible) {
        auto metadata = bridge::DeferredScene::read(list);
        require(metadata && metadata->resourceEpoch == 7 && metadata->draws.size() == 2,
                "command list returns its own snapshot rather than the latest recorded one");
        using Execute = void(STDMETHODCALLTYPE *)(ID3D11DeviceContext *, ID3D11CommandList *, BOOL);
        auto table = *reinterpret_cast<void ***>(c.Get());
        reinterpret_cast<Execute>(table[58])(c.Get(), list, TRUE);
        const auto &draw = metadata->draws.back();
        require(draw.frame && draw.frame->meta.sequence == expectedSequence,
                "actual execution order selects the matching MC image");
        require(renderer.upload(c.Get(), *draw.frame), "upload command-list-bound image");
        c->ClearRenderTargetView(target.Get(), blue);
        require(renderer.draw(c.Get(), target.Get(), draw.depth.Get(), draw.camera, 32, 32, false),
                "composite after real deferred depth commands execute");
        auto blockPixel = read(7, 16);
        require(visible ? blockPixel[0] > 250 && blockPixel[2] < 5
                        : blockPixel[2] > 250 && blockPixel[0] < 5,
                "command-list-bound frame uses the depth actually executed on the GPU");
    };
    executeList(secondList.Get(), paired.meta.sequence + 2, true);
    executeList(firstList.Get(), paired.meta.sequence + 1, false);
    executeList(secondList.Get(), paired.meta.sequence + 2, true);
    auto retained = bridge::DeferredScene::read(firstList.Get());
    require(retained && retained->resourceEpoch != 8, "resize epoch rejects older command-list metadata");
    firstList.Reset();
    require(retained->AddRef() == 2 && retained->Release() == 1,
            "releasing a command list releases its private snapshot reference");
    retained.Reset();
    bridge::SceneRecording abandoned;
    abandoned.record(3, bridge::SceneDraw{dsv, viewport, camera, {}, 120});
    abandoned.record(4, bridge::SceneDraw{dsv, viewport, camera, {}, 150});
    require(abandoned.draws.size() == 1 && abandoned.draws[0].score == 150,
            "missed finish boundary cannot merge the old recording into a later command list");
    bridge::DepthSnapshot fixedDepth;
    c->ExecuteCommandList(secondList.Get(), TRUE);
    require(fixedDepth.capture(c.Get(), dsv.Get()) && fixedDepth.captures == 1,
            "production helper copies an executed scene depth into an owned snapshot");
    require(!fixedDepth.capture(deferred.Get(), dsv.Get()),
            "depth snapshots cannot be copied during deferred recording");
    auto renderFixedDepth = [&](bool visible) {
        require(renderer.upload(c.Get(), paired), "restore the paired frame for fixed-depth checks");
        c->ClearRenderTargetView(target.Get(), blue);
        require(renderer.draw(c.Get(), target.Get(), fixedDepth.view(), camera, 32, 32, false),
                "render from the captured immutable scene depth");
        auto fixedPixel = read(7, 16);
        require(visible ? fixedPixel[0] > 250 && fixedPixel[2] < 5
                        : fixedPixel[2] > 250 && fixedPixel[0] < 5,
                "captured depth keeps the expected block occlusion");
    };
    // The game can record next-frame clears while Present holds its state lock.
    // Recording is not execution and cannot invalidate the current snapshot.
    deferred->ClearDepthStencilView(dsv.Get(), D3D11_CLEAR_DEPTH,
                                    camera.projection.at(2, 2) + camera.projection.at(3, 2) / 2, 0);
    renderFixedDepth(true);
    ComPtr<ID3D11CommandList> unknownList;
    require(SUCCEEDED(deferred->FinishCommandList(FALSE, &unknownList)), "finish unmarked native command list");
    require(!bridge::DeferredScene::read(unknownList.Get()), "unmarked command list is reported as unknown");
    c->ExecuteCommandList(unknownList.Get(), TRUE);
    renderFixedDepth(true);
    c->ClearRenderTargetView(target.Get(), blue);
    require(renderer.draw(c.Get(), target.Get(), dsv.Get(), camera, 32, 32, false),
            "read original depth after the unknown native command executes");
    pixel = read(7, 16);
    require(pixel[2] > 250 && pixel[0] < 5,
            "unknown list changed original native depth but not the owned scene snapshot");
    ComPtr<ID3D11CommandList> emptyList;
    require(SUCCEEDED(deferred->FinishCommandList(FALSE, &emptyList)) &&
                bridge::DeferredScene::attach(emptyList.Get(), 7, {}),
            "empty UI command list has an empty recording");
    c->ExecuteCommandList(emptyList.Get(), TRUE);
    renderFixedDepth(true);
    require(fixedDepth.captures == 1, "unknown and empty lists require no repeated depth copy");
    require(fixedDepth.capture(c.Get(), dsv.Get()) && fixedDepth.captures == 2,
            "the next known scene replaces the owned depth snapshot");
    renderFixedDepth(false);
    fixedDepth.reset();
    require(!fixedDepth.view(), "resize releases the owned depth snapshot view");
    camera={sc::identity(),sc::perspective(1.1f,1,.05f,100),{0,0,0},{0,0,1},true};
    float farScene=camera.projection.at(2,2)+camera.projection.at(3,2)/5;
    float nearEffect=camera.projection.at(2,2)+camera.projection.at(3,2)/2;
    bridge::SceneRecording effectRecording;
    auto effectFrame=std::make_shared<bridge::Frame>(f);
    deferred->ClearDepthStencilView(dsv.Get(),D3D11_CLEAR_DEPTH,farScene,0);
    effectRecording.record(1,{dsv,{0,0,32,32,0,1},camera,effectFrame,1000});
    require(effectRecording.sealDepth(deferred.Get(),dsv.Get()),"opaque depth copy records before a depth-writing attack effect");
    require(!effectRecording.sealDepth(deferred.Get(),dsv.Get()),"consecutive FX draws do not allocate another scene depth copy");
    deferred->ClearDepthStencilView(dsv.Get(),D3D11_CLEAR_DEPTH,nearEffect,0);
    ComPtr<ID3D11CommandList> effectList;
    require(SUCCEEDED(deferred->FinishCommandList(FALSE,&effectList)),"finish geometry and attack FX in one native command list");
    c->ExecuteCommandList(effectList.Get(),TRUE);
    require(renderer.upload(c.Get(),f),"restore full world fixture for attack FX occlusion");
    c->ClearRenderTargetView(target.Get(),blue);
    require(renderer.draw(c.Get(),target.Get(),effectRecording.draws[0].cleanDepth.Get(),camera,32,32,false,false),"compose MC against pre-FX depth from the same deferred list");
    require(read()[0]>250,"MC remains visible when an attack effect later overwrites native depth");
    c->ClearRenderTargetView(target.Get(),blue);
    require(renderer.draw(c.Get(),target.Get(),dsv.Get(),camera,32,32,false,false),"compare contaminated attack effect depth");
    require(read()[2]>250,"contaminated original depth reproduces the old MC disappearance");
    bridge::SceneDraw laterGeometry{dsv,{0,0,32,32,0,1},camera,effectFrame,2000};
    require(bridge::inheritsCleanDepth(effectRecording.draws[0],laterGeometry),
            "later opaque batch inherits the pre-FX copy instead of contaminated native depth");
    laterGeometry.cleanDepth=effectRecording.draws[0].cleanDepth;
    c->ClearRenderTargetView(target.Get(),blue);
    require(renderer.draw(c.Get(),target.Get(),laterGeometry.cleanDepth.Get(),camera,32,32,false,false),
            "compose later native geometry using the inherited clean depth");
    require(read()[0]>250,"MC blocks and avatar stay visible after an attack FX and subsequent opaque batch");
    require(!bridge::inheritsCleanDepth(effectRecording.draws[0],laterGeometry,true),
            "a real native depth clear starts a new geometry pass");
    auto changedFrame=laterGeometry;changedFrame.frame=std::make_shared<bridge::Frame>(f);
    require(!bridge::inheritsCleanDepth(effectRecording.draws[0],changedFrame),
            "different capture cannot inherit another camera's depth");
    auto changedDepth=laterGeometry;changedDepth.depth.Reset();
    require(!bridge::inheritsCleanDepth(effectRecording.draws[0],changedDepth),
            "different native depth attachment does not reuse a sealed pass");
    bridge::SceneRecording continued;
    continued.record(1,effectRecording.draws[0]);
    auto followup=laterGeometry;followup.cleanDepth.Reset();
    continued.record(1,followup);
    require(continued.draws.size()==1 && continued.draws[0].cleanDepth==laterGeometry.cleanDepth,
            "same-list later geometry retains the pre-effect snapshot");
    bridge::SceneDraw realClear;realClear.depth=dsv;realClear.clearsDepth=true;
    continued.record(1,realClear);continued.record(1,followup);
    require(continued.draws.size()==3 && !continued.draws.back().cleanDepth,
            "same-list clear boundary releases the old scene snapshot for the next pass");
    bridge::SceneDraw pureEffect;pureEffect.depth=dsv;pureEffect.effectWrite=true;
    bridge::SceneRecording effectsOnly;effectsOnly.record(1,pureEffect);
    effectsOnly.record(1,pureEffect);
    require(effectsOnly.draws.size()==1,"consecutive particles share one metadata boundary without unbounded markers");
    require(!bridge::validNativeSceneDraw(pureEffect) && !bridge::validSceneCandidate(pureEffect),
            "a pure FX list cannot nominate scene readiness, camera or world depth");
    require(SUCCEEDED(deferred->FinishCommandList(FALSE,&effectList)) &&
            bridge::DeferredScene::attach(effectList.Get(),7,std::move(effectsOnly.draws)),
            "deferred effect-only command list retains depth-write metadata");
    auto fxMetadata=bridge::DeferredScene::read(effectList.Get());
    require(fxMetadata && fxMetadata->draws.size()==1 && fxMetadata->draws[0].effectWrite &&
            fxMetadata->draws[0].depth.Get()==dsv.Get(),
            "execution can seal a prior scene even when this list has no opaque geometry");
    bridge::SceneRecording separated;separated.record(1,laterGeometry);separated.record(1,pureEffect);
    separated.record(1,followup);
    require(separated.draws.size()==3 && !separated.draws.back().cleanDepth,
            "effect markers retain command ordering rather than merging with an opaque batch");
    if (argc > 1) {
        std::ifstream file(argv[1], std::ios::binary);
        auto captured = empty;
        captured.meta.sequence = paired.meta.sequence + 100;
        file.read(reinterpret_cast<char *>(captured.pixels.data()), plane * 2);
        require(file.gcount() == plane * 2, "load production GL-exported sword color/depth planes");
        require(renderer.upload(c.Get(), captured), "upload real GL sword capture into D3D11 compositor");
        for (float nativeZ : {5.f, 2.f}) {
            c->ClearRenderTargetView(target.Get(), blue);
            c->ClearDepthStencilView(dsv.Get(), D3D11_CLEAR_DEPTH,
                camera.projection.at(2,2) + camera.projection.at(3,2) / nativeZ, 0);
            require(renderer.draw(c.Get(), target.Get(), dsv.Get(), camera, 32, 32, false, false),
                "compose actual GL sword emission against native depth");
            auto actual = read();
            require(nativeZ > 3 ? actual[0] >= 100 && actual[0] <= 104 && actual[1] >= 62 && actual[1] <= 66 && actual[2] > 250
                               : actual[0] < 5 && actual[1] < 5 && actual[2] > 250,
                "GL-to-D3D sword glow is visible in front and occluded behind native geometry");
        }
    }
    std::cout << checks << " D3D11 composite checks passed\n";
}
