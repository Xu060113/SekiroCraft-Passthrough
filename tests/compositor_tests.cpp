#include "../src/compositor.hpp"
#include "../bridge/session.hpp"
#include "../src/deferred_scene.hpp"
#include <iostream>
#include <fstream>
int checks{};
void require(bool b, const char *label) {
    ++checks;
    if (!b) {
        std::cerr << "FAIL " << label << "\n";
        std::exit(1);
    }
}
int main() {
    ComPtr<ID3D11Device> device;
    ComPtr<ID3D11DeviceContext> c;
    require(SUCCEEDED(D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_WARP, nullptr, 0, nullptr, 0,
                                        D3D11_SDK_VERSION, &device, nullptr, &c)),
            "real D3D11 WARP device");
    bridge::Compositor renderer;
    require(renderer.init(device.Get()), renderer.error.c_str());
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
    f.meta.sequence = empty.meta.sequence + 1;
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
    std::cout << checks << " D3D11 composite checks passed\n";
}
