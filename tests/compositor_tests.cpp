#include "../src/compositor.hpp"
#include "../bridge/session.hpp"
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
    auto read = [&] {
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
        std::memcpy(value.data(), static_cast<uint8_t *>(mapped.pData) + 16 * mapped.RowPitch + 16 * 4, 4);
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
    std::cout << checks << " D3D11 composite checks passed\n";
}
