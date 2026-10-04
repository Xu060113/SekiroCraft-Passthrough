#include "../src/compositor.hpp"
#include <iostream>

int checks{};
void require(bool value, const char *name) {
    ++checks;
    if (!value) { std::cerr << "FAIL " << name << '\n'; std::exit(1); }
}
int main() {
    ComPtr<ID3D11Device> device;
    ComPtr<ID3D11DeviceContext> context;
    require(SUCCEEDED(D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_WARP, nullptr, 0, nullptr, 0,
                D3D11_SDK_VERSION, &device, nullptr, &context)), "real WARP overlay device");
    bridge::Compositor renderer;
    require(renderer.init(device.Get()), "overlay shaders compiled");
    D3D11_TEXTURE2D_DESC desc{};
    desc.Width = desc.Height = 32; desc.MipLevels = desc.ArraySize = desc.SampleDesc.Count = 1;
    desc.Format = DXGI_FORMAT_R8G8B8A8_UNORM; desc.BindFlags = D3D11_BIND_RENDER_TARGET;
    ComPtr<ID3D11Texture2D> color, depth;
    ComPtr<ID3D11RenderTargetView> target;
    ComPtr<ID3D11DepthStencilView> dsv;
    require(SUCCEEDED(device->CreateTexture2D(&desc, nullptr, &color)) &&
            SUCCEEDED(device->CreateRenderTargetView(color.Get(), nullptr, &target)), "native color target");
    desc.Format = DXGI_FORMAT_D32_FLOAT; desc.BindFlags = D3D11_BIND_DEPTH_STENCIL;
    require(SUCCEEDED(device->CreateTexture2D(&desc, nullptr, &depth)) &&
            SUCCEEDED(device->CreateDepthStencilView(depth.Get(), nullptr, &dsv)), "native depth target");
    bridge::Frame frame;
    frame.meta.sequence = frame.meta.epoch = 1; frame.meta.width = frame.meta.height = 32;
    frame.meta.flags = bridge::Overlay | bridge::BottomUp; frame.meta.forward[2] = 1;
    frame.meta.fovY = 1.1f; frame.meta.aspect = 1; frame.meta.nearZ = .05f; frame.meta.farZ = 100;
    constexpr size_t plane = 32 * 32 * 4;
    frame.pixels.resize(plane * 3);
    for (size_t i = 0; i < 32 * 32; ++i) {
        float farDepth = 1;
        std::memcpy(frame.pixels.data() + plane + i * 4, &farDepth, 4);
        frame.pixels[plane * 2 + i * 4 + 1] = frame.pixels[plane * 2 + i * 4 + 3] = 128;
    }
    require(renderer.upload(context.Get(), frame), "fresh inventory pixels uploaded");
    float blue[]{0, 0, 1, 1};
    auto nativeTarget = target.Get(); context->OMSetRenderTargets(1, &nativeTarget, dsv.Get());
    D3D11_VIEWPORT nativeViewport{2, 3, 21, 22, 0, 1}; context->RSSetViewports(1, &nativeViewport);
    auto read = [&](UINT x, UINT y) {
        D3D11_TEXTURE2D_DESC staging{}; color->GetDesc(&staging);
        staging.BindFlags = 0; staging.Usage = D3D11_USAGE_STAGING;
        staging.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
        ComPtr<ID3D11Texture2D> copy;
        require(SUCCEEDED(device->CreateTexture2D(&staging, nullptr, &copy)), "HUD readback texture");
        context->CopyResource(copy.Get(), color.Get());
        D3D11_MAPPED_SUBRESOURCE mapped{};
        require(SUCCEEDED(context->Map(copy.Get(), 0, D3D11_MAP_READ, 0, &mapped)), "HUD GPU readback");
        std::array<uint8_t, 4> pixel;
        std::memcpy(pixel.data(), static_cast<uint8_t *>(mapped.pData) + y * mapped.RowPitch + x * 4, 4);
        context->Unmap(copy.Get(), 0); return pixel;
    };
    sc::Camera missing;
    context->ClearRenderTargetView(target.Get(), blue);
    require(!renderer.draw(context.Get(), target.Get(), nullptr, missing, 32, 32, false, false),
            "missing world depth and camera reject only the world");
    require(renderer.drawOverlay(context.Get(), target.Get(), 32, 32), "inventory draws without scene depth or camera");
    auto pixel = read(16, 16);
    require(pixel[1] == 128 && pixel[2] == 127, "inventory blends once above native blue scene");
    require(renderer.submitted == 0 && renderer.overlaySubmitted == 1,
            "HUD success does not conceal a missing world composite");
    D3D11_VIEWPORT restored{}; UINT viewportCount = 1; context->RSGetViewports(&viewportCount, &restored);
    ComPtr<ID3D11RenderTargetView> restoredTarget; ComPtr<ID3D11DepthStencilView> restoredDepth;
    context->OMGetRenderTargets(1, &restoredTarget, &restoredDepth);
    require(restored.TopLeftX == 2 && restored.Height == 22 && restoredTarget == target && restoredDepth == dsv,
            "independent HUD restores native viewport, target and depth");
    sc::Camera camera{sc::identity(), sc::perspective(1.1f, 1, .05f, 100), {}, {0, 0, 1}, true};
    context->ClearRenderTargetView(target.Get(), blue);
    context->ClearDepthStencilView(dsv.Get(), D3D11_CLEAR_DEPTH, 1, 0);
    require(renderer.draw(context.Get(), target.Get(), dsv.Get(), camera, 32, 32, false, false),
            "paired world pass can exclude HUD");
    pixel = read(16, 16);
    require(pixel[1] == 0 && pixel[2] == 255, "world pass did not draw HUD prematurely");
    require(renderer.drawOverlay(context.Get(), target.Get(), 32, 32), "single final HUD pass");
    pixel = read(16, 16);
    require(pixel[1] == 128 && pixel[2] == 127, "world plus final HUD is not double blended");
    ++frame.meta.sequence;
    std::fill(frame.pixels.begin() + plane * 2, frame.pixels.end(), 0);
    frame.pixels[plane * 2 + 16 * 4] = frame.pixels[plane * 2 + 16 * 4 + 3] = 255;
    size_t upper = plane * 2 + (31 * 32 + 16) * 4;
    frame.pixels[upper + 1] = frame.pixels[upper + 3] = 255;
    require(renderer.upload(context.Get(), frame), "bottom-up inventory markers uploaded");
    context->ClearRenderTargetView(target.Get(), blue);
    require(renderer.drawOverlay(context.Get(), target.Get(), 32, 32), "bottom-up HUD pass");
    auto top = read(16, 0), bottom = read(16, 31), middle = read(16, 16);
    require(top[1] == 255 && bottom[0] == 255, "independent HUD keeps OpenGL vertical orientation");
    require(middle[2] == 255 && middle[0] == 0 && middle[1] == 0,
            "transparent HUD retains the native image");
    std::cout << checks << " independent HUD checks passed\n";
}
