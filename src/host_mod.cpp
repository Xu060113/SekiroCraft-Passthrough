#include "MinHook.h"
#include "backends/imgui_impl_dx11.h"
#include "backends/imgui_impl_win32.h"
#include "imgui.h"
#include "compositor.hpp"
#include "sekirocraft/host.hpp"
#include "../bridge/shared_memory.hpp"
#include "input_capture.hpp"
#include <atomic>
#include <thread>
#include <set>
#include <unordered_map>
extern HMODULE scModule;
extern IMGUI_IMPL_API LRESULT ImGui_ImplWin32_WndProcHandler(HWND, UINT, WPARAM, LPARAM);
namespace {
using Present = HRESULT(STDMETHODCALLTYPE *)(IDXGISwapChain *, UINT, UINT);
using Resize = HRESULT(STDMETHODCALLTYPE *)(IDXGISwapChain *, UINT, UINT, UINT, DXGI_FORMAT, UINT);
using DrawIndexed = void(STDMETHODCALLTYPE *)(ID3D11DeviceContext *, UINT, UINT, INT);
using DrawInstanced = void(STDMETHODCALLTYPE *)(ID3D11DeviceContext *, UINT, UINT, UINT, INT, UINT);
using Targets = void(STDMETHODCALLTYPE *)(ID3D11DeviceContext *, UINT, ID3D11RenderTargetView *const *,
                                          ID3D11DepthStencilView *);
using TargetsUav = void(STDMETHODCALLTYPE *)(ID3D11DeviceContext *, UINT, ID3D11RenderTargetView *const *,
                                             ID3D11DepthStencilView *, UINT, UINT,
                                             ID3D11UnorderedAccessView *const *, const UINT *);
using Viewports = void(STDMETHODCALLTYPE *)(ID3D11DeviceContext *, UINT, const D3D11_VIEWPORT *);
Present originalPresent{};
Resize originalResize{};
DrawIndexed originalDraw[2]{};
DrawInstanced originalInstanced[2]{};
Targets originalTargets[2]{};
TargetsUav originalTargetsUav[2]{};
Viewports originalViewports[2]{};
thread_local bool inMod = false;
std::atomic<bool> ready = false;
std::mutex appMutex;
struct Flag {
    bool previous = inMod;
    Flag() { inMod = true; }
    ~Flag() { inMod = previous; }
};
class LatestFrame {
    std::mutex mutex;
    std::shared_ptr<bridge::Frame> value;

  public:
    std::shared_ptr<bridge::Frame> load() {
        std::unique_lock lock(mutex, std::try_to_lock);
        return lock.owns_lock() ? value : nullptr;
    }
    void store(std::shared_ptr<bridge::Frame> next) {
        std::shared_ptr<bridge::Frame> old;
        {
            std::unique_lock lock(mutex, std::try_to_lock);
            if (!lock.owns_lock())
                return;
            old.swap(value);
            value.swap(next);
        }
    }
};
std::atomic<int32_t> pendingWheel{};
std::atomic<uint64_t> textSequence{};
std::array<std::atomic<uint32_t>, 8> text{};
std::array<std::atomic<bool>, 256> pendingKeys{};
using WarpCursor = BOOL(WINAPI *)(int, int);
using Clip = BOOL(WINAPI *)(const RECT *);
WarpCursor originalWarp{};
Clip originalClip{};
BOOL WINAPI hookWarp(int x, int y) {
    return !inMod && sc::input::capture ? TRUE : originalWarp(x, y);
}
BOOL WINAPI hookClip(const RECT *rect) {
    return !inMod && sc::input::capture ? TRUE : originalClip(rect);
}
struct App {
    sc::GameHost host;
    bridge::SharedMemory memory;
    bridge::Compositor compositor;
    LatestFrame latest;
    std::atomic<uint64_t> epoch{};
    std::atomic<uint32_t> mcStatus{};
    ComPtr<ID3D11Device> device;
    ComPtr<ID3D11DeviceContext> context;
    ComPtr<ID3D11RenderTargetView> target;
    ComPtr<ID3D11DepthStencilView> sceneDepth;
    D3D11_VIEWPORT sceneViewport{};
    struct Binding {
        ComPtr<ID3D11DepthStencilView> depth;
        D3D11_VIEWPORT viewport{};
    };
    std::unordered_map<ID3D11DeviceContext *, Binding> bindings;
    std::unordered_map<ID3D11DepthStencilView *, uint64_t> depthScores;
    std::set<std::pair<UINT, UINT>> observedDepthSizes;
    std::set<UINT> observedContextTypes;
    uint64_t bestDepthScore{}, indexedCalls{}, instancedCalls{}, targetCalls{};
    HWND window{};
    IDXGISwapChain *swap{};
    std::atomic<WNDPROC> previousWndProc{};
    unsigned width{}, height{}, sampleCount{}, exportWidth = 1280;
    uint64_t frames{}, depthFrame{}, sequence{}, lastPeerLog{};
    sc::PlayerSnapshot player;
    sc::Camera camera;
    bool initialized{}, nativeCamera = true, showMenu{}, edit{}, reverseDepth = true, hideOriginal = true,
                        guiOwned{};
    bool keys[256]{};
    int cursorAdjustment{};
    RECT previousClip{};
    bridge::Control control;
    std::string status = "Waiting for Minecraft and a playable Sekiro scene.";
    void cursor(bool on) {
        if (on == guiOwned)
            return;
        Flag f;
        if (on) {
            GetClipCursor(&previousClip);
            ClipCursor(nullptr);
            cursorAdjustment = 0;
            while (ShowCursor(TRUE) < 0)
                ++cursorAdjustment;
            ++cursorAdjustment;
        } else {
            for (int i = 0; i < cursorAdjustment; ++i)
                ShowCursor(FALSE);
            ClipCursor(GetForegroundWindow() == window ? &previousClip : nullptr);
            cursorAdjustment = 0;
        }
        guiOwned = on;
    }
    bool pressed(int vk) {
        bool down = (GetAsyncKeyState(vk) & 0x8000) != 0;
        bool edge = (down && !keys[vk]) || pendingKeys[vk].exchange(false);
        keys[vk] = down;
        return edge;
    }
    void update() {
        bool focused = GetForegroundWindow() == window;
        if (focused) {
            if (pressed(VK_F7))
                showMenu = !showMenu;
            if (pressed(VK_F8))
                edit = !edit;
            if (pressed(VK_F9))
                ++control.command;
        }
        player = host.player();
        auto c = host.camera(player);
        camera = c.value_or(sc::Camera{});
        auto vitals = host.vitals();
        bool scene = player.valid && camera.valid && sceneDepth && frames - depthFrame <= 2 &&
                     (!vitals.valid || vitals.hp > 0);
        bool capturing = focused && (showMenu || (scene && (mcStatus.load() & 2) != 0));
        sc::input::capture = capturing;
        sc::input::mcEdit = focused && scene && edit && !showMenu && (mcStatus.load() & 1) != 0;
        cursor(capturing);
        control.sequence = ++sequence;
        control.tickMs = GetTickCount64();
        control.epoch = epoch;
        control.flags = (scene ? bridge::Scene : 0) | (focused ? bridge::Focus : 0) |
                        (edit && !showMenu ? bridge::Edit : 0);
        std::fill(control.keys.begin(), control.keys.end(), 0);
        control.buttons = 0;
        if (focused && !showMenu) {
            for (int vk = 8; vk < 256; ++vk)
                if (GetAsyncKeyState(vk) & 0x8000)
                    control.keys[vk / 8] |= uint8_t(1 << (vk % 8));
            if (GetAsyncKeyState(VK_LBUTTON) & 0x8000)
                control.buttons |= 1;
            if (GetAsyncKeyState(VK_RBUTTON) & 0x8000)
                control.buttons |= 2;
            if (GetAsyncKeyState(VK_MBUTTON) & 0x8000)
                control.buttons |= 4;
        }
        control.wheel = pendingWheel.load();
        control.textSequence = textSequence.load();
        for (size_t i = 0; i < text.size(); ++i)
            control.text[i] = text[i].load();
        if (scene) {
            float playerValues[]{player.position.x, player.position.y, player.position.z};
            float eyeValues[]{camera.eye.x, camera.eye.y, camera.eye.z},
                forwardValues[]{camera.forward.x, camera.forward.y, camera.forward.z};
            std::copy_n(playerValues, 3, control.player);
            std::copy_n(eyeValues, 3, control.eye);
            std::copy_n(forwardValues, 3, control.forward);
            control.fovY = 2 * std::atan(1 / camera.projection.at(1, 1));
            control.aspect = camera.projection.at(1, 1) / camera.projection.at(0, 0);
            control.height = std::min(bridge::maxHeight, unsigned(std::lround(exportWidth / control.aspect)));
            control.width = unsigned(std::lround(control.height * control.aspect));
            auto p = camera.projection;
            control.nearZ = -p.at(3, 2) / p.at(2, 2);
            control.farZ = -p.at(3, 2) / (p.at(2, 2) - 1);
            if (reverseDepth) {
                camera.projection.at(2, 2) = 1 - p.at(2, 2);
                camera.projection.at(3, 2) = -p.at(3, 2);
            }
        }
        POINT point{};
        GetCursorPos(&point);
        ScreenToClient(window, &point);
        float sceneWidth = std::min(float(width), float(height) * control.aspect),
              sceneHeight = sceneWidth / control.aspect;
        control.mouseX = (point.x - (width - sceneWidth) / 2) / sceneWidth;
        control.mouseY = (point.y - (height - sceneHeight) / 2) / sceneHeight;
        memory.writeControl(control);
        if (!scene || !focused || showMenu) {
            latest.store(nullptr);
            host.avatarVisibility(false);
        }
        if (GetTickCount64() - lastPeerLog > 5000) {
            lastPeerLog = GetTickCount64();
            sc::log("scene=" + std::to_string(scene) + " mc=" + std::to_string(mcStatus.load()) +
                    " frames=" + std::to_string(compositor.submitted));
        }
    }
    void hud() {
        if (!showMenu)
            return;
        ImGui::Begin("Sekiro + Minecraft passthrough", &showMenu);
        ImGui::Text("Two real processes | protocol v1 | F7 diagnostics / F8 edit / F9 test block");
        ImGui::Text("Sekiro: %s | camera: %s | depth: %s", player.valid ? "loaded" : "not loaded",
                    camera.valid ? "live" : "missing", sceneDepth ? "captured" : "missing");
        ImGui::Text("MC: %s | composite frames: %llu",
                    mcStatus.load() & 1 ? "armed and connected" : "waiting (run /sekirobridge on)",
                    (unsigned long long)compositor.submitted);
        ImGui::Checkbox("Forward MC actions (F8)", &edit);
        ImGui::Checkbox("Hide native hero when MC frame is valid", &hideOriginal);
        ImGui::Checkbox("Host uses reversed Z", &reverseDepth);
        ImGui::TextWrapped("%s", status.c_str());
        ImGui::TextWrapped("%s", compositor.error.c_str());
        ImGui::TextWrapped(
            "Native block collision, ground raycast and cross-game damage are unavailable in this build.");
        ImGui::End();
    }
};
App *app{};
LRESULT CALLBACK modWndProc(HWND hwnd, UINT msg, WPARAM w, LPARAM l) {
    if ((msg == WM_KEYDOWN || msg == WM_SYSKEYDOWN) && w < 256 && !(l & (1LL << 30)))
        pendingKeys[w] = true;
    if (msg == WM_MOUSEWHEEL)
        pendingWheel.fetch_add(GET_WHEEL_DELTA_WPARAM(w));
    if (msg == WM_CHAR) {
        static uint32_t high{};
        uint32_t cp = uint32_t(w);
        if (cp >= 0xd800 && cp <= 0xdbff)
            high = cp;
        else {
            if (cp >= 0xdc00 && cp <= 0xdfff && high)
                cp = 0x10000 + ((high - 0xd800) << 10) + (cp - 0xdc00);
            high = 0;
            if (cp >= 32 && cp <= 0x10ffff && !(cp >= 0xd800 && cp <= 0xdfff)) {
                auto i = textSequence.load();
                text[i % 8] = cp;
                textSequence.store(i + 1);
            }
        }
    }
    std::unique_lock guard(appMutex, std::try_to_lock);
    auto previous = app ? app->previousWndProc.load() : nullptr;
    if (guard.owns_lock() && app && app->initialized) {
        if (app->showMenu)
            ImGui_ImplWin32_WndProcHandler(hwnd, msg, w, l);
        bool capture = sc::input::capture;
        if (capture &&
            ((msg >= WM_KEYFIRST && msg <= WM_KEYLAST) || (msg >= WM_MOUSEFIRST && msg <= WM_MOUSELAST)))
            return 0;
    }
    if (guard.owns_lock())
        guard.unlock();
    if (sc::input::capture &&
        ((msg >= WM_KEYFIRST && msg <= WM_KEYLAST) || (msg >= WM_MOUSEFIRST && msg <= WM_MOUSELAST)))
        return 0;
    if (sc::input::mcEdit) {
        if ((msg >= WM_LBUTTONDOWN && msg <= WM_MBUTTONDBLCLK) || msg == WM_MOUSEWHEEL || msg == WM_CHAR)
            return 0;
        if ((msg == WM_KEYDOWN || msg == WM_KEYUP) &&
            (w == 'E' || w == 'Q' || w == 'F' || (w >= '1' && w <= '9')))
            return 0;
    }
    return previous ? CallWindowProcW(previous, hwnd, msg, w, l) : DefWindowProcW(hwnd, msg, w, l);
}
bool initRender(IDXGISwapChain *swap) {
    auto &a = *app;
    DXGI_SWAP_CHAIN_DESC desc{};
    if (FAILED(swap->GetDesc(&desc)) || !desc.OutputWindow ||
        FAILED(swap->GetDevice(IID_PPV_ARGS(&a.device))))
        return false;
    a.device->GetImmediateContext(&a.context);
    ComPtr<ID3D11Texture2D> buffer;
    if (FAILED(swap->GetBuffer(0, IID_PPV_ARGS(&buffer))))
        return false;
    D3D11_TEXTURE2D_DESC d{};
    buffer->GetDesc(&d);
    a.width = d.Width;
    a.height = d.Height;
    if (FAILED(a.device->CreateRenderTargetView(buffer.Get(), nullptr, &a.target)) ||
        !a.compositor.init(a.device.Get()))
        return false;
    IMGUI_CHECKVERSION();
    ImGui::CreateContext();
    ImGui::GetIO().IniFilename = nullptr;
    ImGui::GetIO().LogFilename = nullptr;
    if (!ImGui_ImplWin32_Init(desc.OutputWindow) || !ImGui_ImplDX11_Init(a.device.Get(), a.context.Get()))
        return false;
    a.window = desc.OutputWindow;
    a.swap = swap;
    a.initialized = true;
    a.previousWndProc = reinterpret_cast<WNDPROC>(
        SetWindowLongPtrW(a.window, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(modWndProc)));
    sc::log("Passthrough D3D11 initialized " + std::to_string(a.width) + "x" + std::to_string(a.height));
    return true;
}
// Immediate/deferred context hook implementation follows below.
void recordTargets(ID3D11DeviceContext *context, ID3D11DepthStencilView *dsv) {
    auto &a = *app;
    if (!a.initialized)
        return;
    ++a.targetCalls;
    auto &binding = a.bindings[context];
    binding.depth.Reset();
    if (!dsv)
        return;
    ComPtr<ID3D11Device> device;
    context->GetDevice(&device);
    if (device.Get() != a.device.Get())
        return;
    if (a.observedContextTypes.insert(context->GetType()).second)
        sc::log("Observed D3D11 context type=" + std::to_string(context->GetType()));
    ComPtr<ID3D11Resource> res;
    dsv->GetResource(&res);
    ComPtr<ID3D11Texture2D> texture;
    if (FAILED(res.As(&texture)))
        return;
    D3D11_TEXTURE2D_DESC desc{};
    texture->GetDesc(&desc);
    if (a.observedDepthSizes.insert({desc.Width, desc.Height}).second)
        sc::log("Bound depth " + std::to_string(desc.Width) + "x" + std::to_string(desc.Height) +
                " samples=" + std::to_string(desc.SampleDesc.Count));
    float aspect = float(desc.Width) / std::max(1u, desc.Height);
    float expectedAspect = a.nativeCamera && a.camera.valid
                               ? a.camera.projection.at(1, 1) / a.camera.projection.at(0, 0)
                               : aspect;
    if (desc.Width == a.width && desc.Height <= a.height && desc.Height >= a.height / 2 &&
        desc.SampleDesc.Count == 1 && std::abs(aspect - expectedAspect) < .03f)
        binding.depth = dsv;
}
void recordDraw(ID3D11DeviceContext *context, UINT count) {
    auto &a = *app;
    if (!a.initialized || !a.player.valid || count < 100)
        return;
    auto found = a.bindings.find(context);
    if (found == a.bindings.end() || !found->second.depth)
        return;
    auto &bound = found->second;
    if (bound.viewport.Width <= 0 || bound.viewport.Height <= 0)
        return;
    auto &score = a.depthScores[bound.depth.Get()];
    score += count;
    if (score >= a.bestDepthScore) {
        a.bestDepthScore = score;
        a.sceneDepth = bound.depth;
        a.sceneViewport = bound.viewport;
        a.depthFrame = a.frames;
    }
}
template <int I>
void STDMETHODCALLTYPE hookTargets(ID3D11DeviceContext *context, UINT count,
                                   ID3D11RenderTargetView *const *views, ID3D11DepthStencilView *depth) {
    originalTargets[I](context, count, views, depth);
    if (!inMod && ready) {
        std::unique_lock guard(appMutex, std::try_to_lock);
        if (!guard.owns_lock())
            return;
        Flag flag;
        recordTargets(context, depth);
    }
}
template <int I>
void STDMETHODCALLTYPE hookTargetsUav(ID3D11DeviceContext *context, UINT count,
                                      ID3D11RenderTargetView *const *views, ID3D11DepthStencilView *depth,
                                      UINT start, UINT uavCount, ID3D11UnorderedAccessView *const *uavs,
                                      const UINT *counts) {
    originalTargetsUav[I](context, count, views, depth, start, uavCount, uavs, counts);
    if (count != D3D11_KEEP_RENDER_TARGETS_AND_DEPTH_STENCIL && !inMod && ready) {
        std::unique_lock guard(appMutex, std::try_to_lock);
        if (!guard.owns_lock())
            return;
        Flag flag;
        recordTargets(context, depth);
    }
}
template <int I>
void STDMETHODCALLTYPE hookViewports(ID3D11DeviceContext *context, UINT count, const D3D11_VIEWPORT *views) {
    originalViewports[I](context, count, views);
    if (!inMod && ready && count && views) {
        std::unique_lock guard(appMutex, std::try_to_lock);
        if (guard.owns_lock())
            app->bindings[context].viewport = views[0];
    }
}
template <int I>
void STDMETHODCALLTYPE hookInstanced(ID3D11DeviceContext *context, UINT count, UINT instances, UINT start,
                                     INT base, UINT first) {
    if (!inMod && ready) {
        std::unique_lock guard(appMutex, std::try_to_lock);
        if (guard.owns_lock()) {
            Flag flag;
            ++app->instancedCalls;
            recordDraw(context, count);
        }
    }
    originalInstanced[I](context, count, instances, start, base, first);
}
template <int I>
void STDMETHODCALLTYPE hookDraw(ID3D11DeviceContext *context, UINT count, UINT start, INT base) {
    if (!inMod && ready) {
        std::unique_lock guard(appMutex, std::try_to_lock);
        if (guard.owns_lock()) {
            Flag flag;
            ++app->indexedCalls;
            recordDraw(context, count);
        }
    }
    originalDraw[I](context, count, start, base);
}
HRESULT STDMETHODCALLTYPE hookPresent(IDXGISwapChain *swap, UINT interval, UINT flags) {
    if (inMod || !ready || flags & DXGI_PRESENT_TEST)
        return originalPresent(swap, interval, flags);
    std::unique_lock guard(appMutex, std::try_to_lock);
    if (!guard.owns_lock())
        return originalPresent(swap, interval, flags);
    Flag flag;
    auto forward = [&] {
        guard.unlock();
        return originalPresent(swap, interval, flags);
    };
    try {
        auto &a = *app;
        if (!a.initialized && !initRender(swap))
            return forward();
        if (swap != a.swap)
            return forward();
        if (!a.target) {
            ComPtr<ID3D11Texture2D> buffer;
            if (FAILED(swap->GetBuffer(0, IID_PPV_ARGS(&buffer))) ||
                FAILED(a.device->CreateRenderTargetView(buffer.Get(), nullptr, &a.target)))
                return forward();
            D3D11_TEXTURE2D_DESC d{};
            buffer->GetDesc(&d);
            a.width = d.Width;
            a.height = d.Height;
        }
        ImGui_ImplDX11_NewFrame();
        ImGui_ImplWin32_NewFrame();
        ImGui::NewFrame();
        a.update();
        auto f = a.latest.load();
        bool submitted = false;
        if (f && f->meta.epoch == a.epoch && bridge::fresh(GetTickCount64(), f->meta.tickMs) &&
            bridge::fresh(GetTickCount64(), f->meta.controlTickMs) &&
            (a.control.flags & (bridge::Scene | bridge::Focus)) == (bridge::Scene | bridge::Focus) &&
            !a.showMenu && (a.mcStatus.load() & 1)) {
            if (a.compositor.upload(a.context.Get(), *f))
                submitted = a.compositor.draw(a.context.Get(), a.target.Get(), a.sceneDepth.Get(), a.camera,
                                              float(a.width), float(a.height));
            a.status = submitted ? "Minecraft world and HUD composited with native scene depth."
                                 : "Frame received; waiting for matching scene depth or camera.";
        } else
            a.status = "Waiting for a fresh MC frame. In MC: load a dedicated world and /sekirobridge on.";
        a.host.avatarVisibility(submitted && a.hideOriginal);
        a.hud();
        ImGui::Render();
        {
            bridge::PipelineState restore(a.context.Get());
            auto target = a.target.Get();
            a.context->OMSetRenderTargets(1, &target, nullptr);
            ImGui_ImplDX11_RenderDrawData(ImGui::GetDrawData());
        }
        ++a.frames;
        a.depthScores.clear();
        a.bestDepthScore = 0;
    } catch (const std::exception &e) {
        sc::input::capture = false;
        sc::input::mcEdit = false;
        if (app) {
            app->host.avatarVisibility(false);
            app->cursor(false);
        }
        sc::log(std::string("Present: ") + e.what());
    } catch (...) {
        sc::input::capture = false;
        sc::input::mcEdit = false;
        if (app) {
            app->host.avatarVisibility(false);
            app->cursor(false);
        }
        sc::log("Present failed; frame skipped.");
    }
    return forward();
}
HRESULT STDMETHODCALLTYPE hookResize(IDXGISwapChain *swap, UINT count, UINT width, UINT height,
                                     DXGI_FORMAT format, UINT flags) {
    if (inMod || !ready)
        return originalResize(swap, count, width, height, format, flags);
    // Releasing cached resources is necessary for ResizeBuffers. Serialize this rare
    // callback against Present, then release the lock before calling the original.
    std::unique_lock guard(appMutex);
    Flag flag;
    if (app && app->initialized && swap == app->swap) {
        auto &a = *app;
        a.host.avatarVisibility(false);
        a.target.Reset();
        a.sceneDepth.Reset();
        a.bindings.clear();
        a.depthScores.clear();
        a.latest.store(nullptr);
        a.camera.valid = false;
        ImGui_ImplDX11_InvalidateDeviceObjects();
    }
    guard.unlock();
    return originalResize(swap, count, width, height, format, flags);
}
#include "install_hooks.inc"
} // namespace
DWORD WINAPI scBootstrap(void *) {
    try {
        wchar_t path[32768]{};
        GetModuleFileNameW(scModule, path, 32768);
        auto root = std::filesystem::path(path).parent_path();
        auto config = root / L"sekirobridge.ini";
        wchar_t data[32768]{};
        GetPrivateProfileStringW(L"SekiroBridge", L"data_root", L"", data, 32768, config.c_str());
        if (data[0])
            sc::dataRoot = data;
        else {
            GetEnvironmentVariableW(L"LOCALAPPDATA", data, 32768);
            sc::dataRoot = std::filesystem::path(data) / L"SekiroCraft-Passthrough";
        }
        sc::log("SekiroCraft-Passthrough 0.1.0 bootstrap; real Minecraft companion required.");
        if (!GetPrivateProfileIntW(L"SekiroBridge", L"enabled", 1, config.c_str()))
            return 0;
        app = new App();
        if (!app->host.initialize()) {
            sc::log("Unsupported host; no game or input hooks installed.");
            return 0;
        }
        HMODULE pin{};
        GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_PIN,
                           reinterpret_cast<LPCWSTR>(&scBootstrap), &pin);
        wchar_t channel[128]{};
        GetPrivateProfileStringW(L"SekiroBridge", L"channel", L"default", channel, 128, config.c_str());
        if (!app->memory.open(channel)) {
            sc::log("Invalid or incompatible shared memory channel.");
            return 0;
        }
        LARGE_INTEGER counter{};
        QueryPerformanceCounter(&counter);
        app->epoch = (uint64_t(counter.QuadPart) ^ (uint64_t(GetCurrentProcessId()) << 32)) | 1;
        app->control.yOffset =
            float(GetPrivateProfileIntW(L"SekiroBridge", L"y_offset", 128, config.c_str()));
        app->reverseDepth = GetPrivateProfileIntW(L"SekiroBridge", L"reverse_depth", 1, config.c_str()) != 0;
        app->hideOriginal = GetPrivateProfileIntW(L"SekiroBridge", L"hide_original", 1, config.c_str()) != 0;
        app->exportWidth =
            std::clamp(GetPrivateProfileIntW(L"SekiroBridge", L"capture_width", 1280, config.c_str()), 320u,
                       bridge::maxWidth);
        if (!installHooks()) {
            sc::log("Hook installation failed.");
            return 0;
        }
        sc::log(sc::input::install() ? "DirectInput capture installed." : "DirectInput capture unavailable.");
        auto user = GetModuleHandleW(L"user32.dll");
        for (auto item : std::array<std::tuple<const char *, void *, void **>, 2>{
                 std::tuple{"SetCursorPos", reinterpret_cast<void *>(hookWarp),
                            reinterpret_cast<void **>(&originalWarp)},
                 std::tuple{"ClipCursor", reinterpret_cast<void *>(hookClip),
                            reinterpret_cast<void **>(&originalClip)}}) {
            auto target = reinterpret_cast<void *>(GetProcAddress(user, std::get<0>(item)));
            if (target && MH_CreateHook(target, std::get<1>(item), std::get<2>(item)) == MH_OK)
                MH_EnableHook(target);
        }
        std::thread([] {
            uint64_t previous{};
            for (;;) {
                try {
                    auto f = app->memory.readFrame(previous, app->epoch);
                    if (f) {
                        previous = f->meta.sequence;
                        app->latest.store(std::move(f));
                    }
                    app->mcStatus = app->memory.readStatus(app->epoch);
                } catch (...) {
                    app->mcStatus = 0;
                    app->latest.store(nullptr);
                }
                Sleep(8);
            }
        }).detach();
        sc::log("Bridge ready; capabilities: camera, input, depth composite. Native collision/combat: "
                "unavailable.");
    } catch (const std::exception &e) {
        sc::log(std::string("Bootstrap: ") + e.what());
    } catch (...) {
        sc::log("Bootstrap failed.");
    }
    return 0;
}
