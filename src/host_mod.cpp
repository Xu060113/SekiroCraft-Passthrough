#include "MinHook.h"
#include "backends/imgui_impl_dx11.h"
#include "backends/imgui_impl_win32.h"
#include "imgui.h"
#include "compositor.hpp"
#include "deferred_scene.hpp"
#include "sekirocraft/host.hpp"
#include "../bridge/shared_memory.hpp"
#include "../bridge/latest_frame.hpp"
#include "input_capture.hpp"
#include "hotkey.hpp"
#include "movement_hook.hpp"
#include "combat_game_hook.hpp"
#include "native_animation.hpp"
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
using FinishCommands = HRESULT(STDMETHODCALLTYPE *)(ID3D11DeviceContext *, BOOL, ID3D11CommandList **);
using ExecuteCommands = void(STDMETHODCALLTYPE *)(ID3D11DeviceContext *, ID3D11CommandList *, BOOL);
using ClearDepth = void(STDMETHODCALLTYPE *)(ID3D11DeviceContext *, ID3D11DepthStencilView *, UINT, FLOAT, UINT8);
Present originalPresent{};
Resize originalResize{};
DrawIndexed originalDraw[2]{};
DrawInstanced originalInstanced[2]{};
Targets originalTargets[2]{};
TargetsUav originalTargetsUav[2]{};
Viewports originalViewports[2]{};
FinishCommands originalFinishCommands[2]{};
ExecuteCommands originalExecuteCommands[2]{};
ClearDepth originalClearDepth[2]{};
thread_local bool inMod = false;
std::atomic<bool> ready = false;
std::atomic<ID3D11Device *> nativeDevice{};
bool gameContext(ID3D11DeviceContext *context) {
    auto expected=nativeDevice.load();
    if(!expected)return false;
    Microsoft::WRL::ComPtr<ID3D11Device> device;
    context->GetDevice(&device);
    return device.Get()==expected;
}
std::mutex appMutex;
struct Flag {
    bool previous = inMod;
    Flag() { inMod = true; }
    ~Flag() { inMod = previous; }
};
std::atomic<int32_t> pendingWheel{};
std::atomic<uint64_t> textSequence{};
std::array<std::atomic<uint32_t>, 8> text{};
std::array<std::atomic<bool>, 256> pendingKeys{};
std::mutex eventMutex;
bridge::InputPacket inputQueue;
bridge::GuiDisplay inputDisplay; // Guarded by eventMutex, same as the event ring.
std::pair<float,float> guiPoint(HWND window,int x,int y) {
    RECT rect{};GetClientRect(window,&rect);
    auto point=inputDisplay.point(float(x),float(y),float(rect.right-rect.left),float(rect.bottom-rect.top));
    return {point[0],point[1]};
}
void recordInput(HWND hwnd,UINT msg,WPARAM w,LPARAM l) {
    std::lock_guard lock(eventMutex);
    bridge::InputEvent e;
    e.tick=GetTickCount64();e.guiGeneration=bridge::fresh(e.tick,inputDisplay.tick)?inputDisplay.generation:0;
    e.mods=((GetKeyState(VK_SHIFT)&0x8000)?1:0)|((GetKeyState(VK_CONTROL)&0x8000)?2:0)|
           ((GetKeyState(VK_MENU)&0x8000)?4:0);
    if((msg==WM_KEYDOWN || msg==WM_SYSKEYDOWN || msg==WM_KEYUP || msg==WM_SYSKEYUP) && w<256) {
        e.kind=1;e.code=uint32_t(w);e.action=(msg==WM_KEYUP || msg==WM_SYSKEYUP)?0:((l&(1LL<<30))?2:1);
    } else {
        if(bridge::mouseButtonEvent(msg,e.code,e.action)){
            e.kind=2;
            auto p=guiPoint(hwnd,short(LOWORD(l)),short(HIWORD(l)));e.x=p.first;e.y=p.second;
        }
        if(msg==WM_MOUSEWHEEL){e.kind=3;e.amount=GET_WHEEL_DELTA_WPARAM(w);
            POINT point{short(LOWORD(l)),short(HIWORD(l))};ScreenToClient(hwnd,&point);
            auto p=guiPoint(hwnd,point.x,point.y);e.x=p.first;e.y=p.second;}
    }
    if(!e.kind)return;
    inputQueue.events[inputQueue.sequence%bridge::inputSlots]=e;++inputQueue.sequence;
}
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
    bridge::NativeMovement movement;
    bridge::NativeDriver driver;
    bridge::NativeCombatAdapter combat;
    bridge::CombatTrace combatTrace;
    bridge::CombatGameHook combatGameHook;
    bridge::Compositor compositor;
    bridge::LatestFrame latest;
    std::shared_ptr<bridge::Frame> renderFrame;
    std::shared_ptr<bridge::Frame> cameraFrame, sceneFrame;
    bridge::DepthSnapshot sceneSnapshot;
    std::shared_ptr<bridge::Frame> snapshotFrame;
    sc::Camera snapshotCamera;
    uint64_t snapshotAt=UINT64_MAX;
    bool immediateDirty{};
    std::array<std::shared_ptr<bridge::Frame>,16> cameraHistory;
    // Deferred contexts only lock short CPU metadata work. Present's uploads,
    // GPU copies, input polling and log I/O cannot discard their command lists.
    bridge::RecordingGate recorder;
    std::atomic<unsigned> recordingWidth{},recordingHeight{};
    std::atomic<uint64_t> recordingFrame{};
    std::atomic<bool> recordingReverseDepth{true};
    std::atomic<float> recordingAspect{16.f/9};
    sc::Camera sceneCamera;
    std::atomic<uint64_t> epoch{};
    std::atomic<uint32_t> mcStatus{};
    std::atomic<uint64_t> statusBusy{}, skippedPresents{};
    ComPtr<ID3D11Device> device;
    ComPtr<ID3D11DeviceContext> context;
    ComPtr<ID3D11RenderTargetView> target;
    ComPtr<ID3D11DepthStencilView> sceneDepth;
    D3D11_VIEWPORT sceneViewport{};
    struct Binding {
        ComPtr<ID3D11DepthStencilView> depth;
        D3D11_VIEWPORT viewport{};
        uint64_t capturedAt=UINT64_MAX;
        uint64_t recordingGeneration{};
        bool deferredCaptured{};
        sc::Camera camera;
        std::shared_ptr<bridge::Frame> frame;
    };
    std::unordered_map<ID3D11DeviceContext *, Binding> bindings;
    std::unordered_map<ID3D11DeviceContext *, bridge::SceneRecording> recordings;
    // Only a resource resize invalidates recordings across all contexts.
    std::atomic<uint64_t> recordingGeneration{1};
    std::atomic<uint64_t> unknownLists{}, finishBusy{}, clearBusy{}, executeBusy{};
    uint64_t snapshotFailures{};
    uint64_t resourceEpoch=1, executedLists{};
    std::atomic<uint64_t> finishedLists{};
    std::unordered_map<ID3D11DepthStencilView *, uint64_t> depthScores;
    std::set<std::pair<UINT, UINT>> observedDepthSizes;
    std::set<UINT> observedContextTypes;
    uint64_t bestDepthScore{};
    std::atomic<uint64_t> indexedCalls{}, instancedCalls{}, targetCalls{};
    HWND window{};
    IDXGISwapChain *swap{};
    std::atomic<WNDPROC> previousWndProc{};
    unsigned width{}, height{}, sampleCount{}, exportWidth = 1280;
    uint64_t frames{}, depthFrame{}, sequence{}, lastPeerLog{};
    sc::PlayerSnapshot player;
    sc::Camera camera;
    bool initialized{}, nativeCamera = true, showMenu{}, edit=true, reverseDepth = true, hideOriginal = true,
                        guiOwned{}, heroHidden{}, playerFeatures = true;
    std::array<bridge::KeyEdge, 256> keys;
    int cursorAdjustment{};
    RECT previousClip{};
    bridge::Control control;
    bridge::ActionHandoff action;
    uint64_t traversalStarted{};
    uint64_t actionRequestSeen{},actionRequestTick{};
    std::string status = "Waiting for Minecraft and a playable Sekiro scene.";
    std::string worldReason = "Waiting for a completed world frame.", overlayReason;
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
        // Always drain the message edge, even when the physical edge is true.
        bool notified = pendingKeys[vk].exchange(false);
        return keys[vk].update(down, notified);
    }
    void update() {
        recordingWidth=width;recordingHeight=height;recordingFrame=frames;
        recordingReverseDepth=reverseDepth;
        movement.tryInstall();
        driver.tryInstall();
        bool focused = GetForegroundWindow() == window;
        if (focused) {
            if(pressed(VK_F6)){
                if(!edit){edit=true;sc::input::nativeUI=false;}
                else sc::input::nativeUI=!sc::input::nativeUI.load();
            }
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
        bool dead=vitals.valid && vitals.hp==0;
        bool scene = player.valid && camera.valid && sceneDepth && (dead || frames - depthFrame <= 2);
        bool nativeUI=sc::input::nativeUI.load() || !edit;
        bool capturing = focused && (showMenu || (!nativeUI && scene && (mcStatus.load() & 2) != 0));
        bool mcOwner=scene && playerFeatures && driver.ready() && movement.installed() &&
                     movement.canFly() && (mcStatus.load() & 8)!=0;
        bool actionAllowed=mcOwner && focused && edit && !showMenu && !nativeUI;
        bool gameplay=actionAllowed && !dead && !(mcStatus.load()&2);
        auto grappleKey=sc::input::grappleVk.load();
        bool grappleAlias=pressed('G');
        bool grappleBound=grappleKey!='G' && pressed(int(grappleKey));
        bool grapple=grappleAlias || grappleBound,attack=pressed('R');
        bridge::NativeActionRequest request;
        bool requested=memory.nativeAction.read(request) && bridge::validAction(request) &&
            request.epoch==epoch && bridge::fresh(GetTickCount64(),request.tick) &&
            (request.sequence!=actionRequestSeen || request.tick!=actionRequestTick);
        if(requested){actionRequestSeen=request.sequence;actionRequestTick=request.tick;}
        auto now=GetTickCount64();
        auto animation=bridge::readNativeAnimation(host.base());
        bool traversalHeld=gameplay && ((GetAsyncKeyState('G')&0x8000) || (GetAsyncKeyState(int(grappleKey))&0x8000));
        if(traversalHeld && !action.active())action.begin(now,animation);
        if((gameplay && (grapple || attack)) || (actionAllowed && dead && (attack || requested))){
            bool accepted=dead || action.begin(now,animation);
            if(accepted && (attack || requested))sc::input::attackUntil=now+180;
            if(accepted && grapple)sc::input::grappleUntil=now+180;
        }
        if(traversalHeld){
            if(!traversalStarted)traversalStarted=now;
            // The native action may only become eligible after gravity/ground
            // state resumes or a jump reaches a hook point. Supply new edges,
            // rather than one indefinitely-held press rejected on entry.
            sc::input::grappleUntil=(now-traversalStarted)%300<180?now+80:0;
        }else traversalStarted=0;
        bool handoff=action.update(now,animation,mcOwner && focused && !dead,nativeUI || showMenu,traversalHeld);
        combatTrace.animation(animation,(handoff?1u:0u)|(action.confirmed()?2u:0u)|(attack?4u:0u)|
            (grapple?8u:0u)|(nativeUI?16u:0u)|(mcOwner?32u:0u));
        sc::input::nativeKeys=actionAllowed && (dead || handoff);
        sc::input::nativeTraversal=actionAllowed && handoff && (traversalHeld || sc::input::grappleHeld());
        if(!sc::input::nativeKeys){sc::input::attackUntil=0;sc::input::grappleUntil=0;}
        sc::input::mcOwner=mcOwner && focused;
        sc::input::capture = capturing || (mcOwner && focused && edit && !nativeUI);
        sc::input::mcEdit = focused && scene && edit && !showMenu && !nativeUI && (mcStatus.load() & 1) != 0;
        sc::input::flying = focused && scene && edit && !showMenu && movement.canFly() &&
                            !nativeUI && (mcStatus.load() & 4) != 0;
        cursor(capturing);
        // Sekiro may hide its OS cursor again from another thread. Render a cursor
        // in the same final pass as the imported GUI, independent of ShowCursor.
        ImGui::GetIO().MouseDrawCursor = capturing;
        control.sequence = ++sequence;
        control.tickMs = GetTickCount64();
        control.epoch = epoch;
        control.flags = (scene ? bridge::Scene : 0) | (focused ? bridge::Focus : 0) |
                        (edit && !nativeUI ? bridge::Edit : 0) |
                        (showMenu ? bridge::Menu : 0) | (dead ? bridge::NativeDead : 0) |
                        (handoff ? bridge::NativeAction : 0) | (nativeUI?bridge::NativeUI:0);
        control.capabilities = 7 | (playerFeatures && movement.installed() ? bridge::constraintCapability : 0) |
                               (playerFeatures && movement.canFly() ? bridge::flightCapability : 0);
        if(playerFeatures && driver.ready() && movement.installed() && movement.canFly())
            control.capabilities |= bridge::mcOwnerCapability | bridge::terrainCapability;
        if(playerFeatures && combat.ready() && (control.capabilities & bridge::mcOwnerCapability))
            control.capabilities |= bridge::combatCapability;
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
        {
            std::unique_lock lock(eventMutex,std::try_to_lock);
            if(lock){inputQueue.tick=control.tickMs;inputQueue.epoch=control.epoch;
                auto gui=guiPoint(window,point.x,point.y);
                control.mouseX=inputQueue.mouseX=gui.first;control.mouseY=inputQueue.mouseY=gui.second;
                inputQueue.guiGeneration=bridge::fresh(control.tickMs,inputDisplay.tick)?inputDisplay.generation:0;
                inputQueue.pointerTick=control.tickMs;
                inputQueue.dx=sc::input::mouseDx.load();inputQueue.dy=sc::input::mouseDy.load();
                memory.input.write(inputQueue);}
        }
        recordingAspect=control.aspect;
        memory.writeControl(control);
        combat.pause(nativeUI || showMenu || !focused);
        movement.update(control, playerFeatures && scene && !nativeUI && edit && (mcStatus.load() & 1) != 0,
            playerFeatures && scene && (mcStatus.load()&1)!=0);
        heroHidden=mcOwner && hideOriginal && host.avatarVisibility(true);
        if(!heroHidden)host.avatarVisibility(false);
        driver.update(control,mcOwner && !nativeUI && edit,heroHidden);
        combat.releaseIfInactive(mcOwner);
        if (!scene || !focused || showMenu) {
            latest.store(nullptr);
            renderFrame.reset();
        }
        if (GetTickCount64() - lastPeerLog > 5000) {
            lastPeerLog = GetTickCount64();
            sc::log("scene=" + std::to_string(scene) + " mc=" + std::to_string(mcStatus.load()) + " frames=" +
                    std::to_string(compositor.submitted) + " hud=" + std::to_string(compositor.overlaySubmitted) +
                    " peerBusy=" + std::to_string(statusBusy.load()) +
                    " presentBusy=" + std::to_string(skippedPresents.load()));
            sc::log("heroHidden=" + std::to_string(heroHidden) + " moveHook=" +
                    std::to_string(movement.installed()) + " playerCalls=" +
                    std::to_string(movement.playerCalls.load()) + " constrained=" +
                    std::to_string(movement.correctedMoves.load()) + " flying=" +
                    std::to_string(movement.flightMoves.load()));
            sc::log("MC driver cameraCalls="+std::to_string(driver.cameraCalls.load())+
                    " controlledMoves="+std::to_string(driver.controlledMoves.load())+
                    " cameraFrame="+std::to_string(driver.cameraFrameSequence.load())+
                    " sceneFrame="+std::to_string(sceneFrame?sceneFrame->meta.sequence:0)+
                    " finishedLists="+std::to_string(finishedLists.load())+
                    " executedLists="+std::to_string(executedLists)+
                    " groundHits="+std::to_string(driver.terrainHits.load())+
                    " npcBlockCorrections="+std::to_string(movement.npcCorrections.load())+
                    " terrainSamples="+std::to_string(driver.terrainSamples.load())+
                    " mouseState="+std::to_string(sc::input::mouseStates.load())+
                    " mouseData="+std::to_string(sc::input::mouseData.load()));
            sc::log("depth snapshots="+std::to_string(sceneSnapshot.captures)+
                    " snapshotFailures="+std::to_string(snapshotFailures)+
                    " unknownLists="+std::to_string(unknownLists.load())+
                    " finishBusy="+std::to_string(finishBusy.load())+
                    " clearBusy="+std::to_string(clearBusy.load())+
                    " executeBusy="+std::to_string(executeBusy.load())+
                    " world="+(worldReason.empty()?"ok":worldReason)+
                    " hud="+(overlayReason.empty()?"ok":overlayReason));
            sc::log("combatReady="+std::to_string(combat.ready())+" postureReady="+std::to_string(combat.postureReady())+
                    " nativeHits="+std::to_string(combat.nativeHits())+" nativeDispatch="+std::to_string(combat.nativeDispatched.load())+
                    " gameCombatTicks="+std::to_string(combat.gameThreadCalls.load())+
                    " nativeHitFailure="+std::to_string(combat.nativeHitFailure())+
                    " phaseDispatch="+std::to_string(combat.phaseDispatched.load())+
                    " phaseConfirmed="+std::to_string(combat.phaseConfirmed.load())+
                    " phaseRejected="+std::to_string(combat.phaseRejected.load())+
                    " postureHits="+std::to_string(combat.appliedPosture.load())+" actors="+std::to_string(combat.publishedActors.load())+
                    " hits="+std::to_string(combat.applied.load())+" rejected="+std::to_string(combat.rejected.load()));
        }
    }
    void hud() {
        if (!showMenu)
            return;
        ImGui::Begin("Sekiro + Minecraft passthrough", &showMenu);
        ImGui::Text("F6 Sekiro UI / MC | F7 diagnostics / F8 input pause / F9 test block");
        auto animation=bridge::readNativeAnimation(host.base());
        ImGui::Text("Native animation=%d valid=%d | action=%d confirmed=%d",animation.id,animation.valid,action.active(),action.confirmed());
        ImGui::Text("G / %c: grapple (Sekiro binding: %c) | R: attack / deathblow / resurrection",
                    int(sc::input::grappleVk.load()),int(sc::input::grappleVk.load()));
        ImGui::Text("Sekiro: %s | camera: %s | depth: %s", player.valid ? "loaded" : "not loaded",
                    camera.valid ? "live" : "missing", sceneDepth ? "captured" : "missing");
        ImGui::Text("MC: %s | composite frames: %llu",
                    mcStatus.load() & 1 ? "armed and connected" : "waiting (run /sekirobridge on)",
                    (unsigned long long)compositor.submitted);
        ImGui::Text("IPC busy retries: %llu | skipped presents: %llu", (unsigned long long)statusBusy.load(),
                    (unsigned long long)skippedPresents.load());
        ImGui::Checkbox("Accept MC input (F8)", &edit);
        ImGui::Checkbox("Enable MC player / camera adapter", &playerFeatures);
        ImGui::Text("Player position hook: %s / MC movement adapter: %s",
                    movement.installed() ? "hook ready (needs game validation)" : "waiting for verified hook",
                    movement.canFly() ? "available (needs game validation)" : "unavailable");
        ImGui::Text("Native hero hidden: %s | player callbacks: %llu | corrections: %llu | flight: %llu",
                    heroHidden ? "yes" : "no", (unsigned long long)movement.playerCalls.load(),
                    (unsigned long long)movement.correctedMoves.load(), (unsigned long long)movement.flightMoves.load());
        ImGui::TextWrapped("MC controls: WASD / Space jump (double-tap creative flight) / Shift sneak / Ctrl sprint / E inventory / Q drop / F swap / F5 perspective.");
        ImGui::Text("MC camera=%llu | MC positions=%llu",(unsigned long long)driver.cameraCalls.load(),
                    (unsigned long long)driver.controlledMoves.load());
        ImGui::Text("Deferred recorded=%llu | submitted=%llu",(unsigned long long)finishedLists.load(),
                    (unsigned long long)executedLists);
        ImGui::Text("World=%llu | HUD=%llu | depth copies=%llu",(unsigned long long)compositor.submitted,
                    (unsigned long long)compositor.overlaySubmitted,(unsigned long long)sceneSnapshot.captures);
        ImGui::Text("Native ground hits=%llu / 81 | mouse state/data=%llu/%llu",
            (unsigned long long)driver.terrainHits.load(),(unsigned long long)sc::input::mouseStates.load(),
            (unsigned long long)sc::input::mouseData.load());
        ImGui::Text("Combat adapter=%s | actors=%llu | applied/rejected hits=%llu/%llu",combat.ready()?"ready":"unavailable",
            (unsigned long long)combat.publishedActors.load(),(unsigned long long)combat.applied.load(),(unsigned long long)combat.rejected.load());
        ImGui::Text("Experimental native hits: %s | dispatched=%llu",combat.nativeHits()?"on":"off",
            (unsigned long long)combat.nativeDispatched.load());
        ImGui::Checkbox("Hide native hero when MC frame is valid", &hideOriginal);
        ImGui::Checkbox("Host uses reversed Z", &reverseDepth);
        ImGui::TextWrapped("%s", status.c_str());
        ImGui::TextWrapped("%s", compositor.error.c_str());
        ImGui::TextWrapped(
            "Native hit/stage calls require live acceptance; Havok block bodies remain unavailable.");
        ImGui::End();
    }
};
App *app{};
LRESULT CALLBACK modWndProc(HWND hwnd, UINT msg, WPARAM w, LPARAM l) {
    if(msg==WM_KEYDOWN && w==VK_ESCAPE && !(l&(1LL<<30)) && sc::input::mcOwner &&
        sc::input::mcEdit && app && !(app->mcStatus.load()&2)){
        sc::input::nativeUI=true;sc::input::capture=false;sc::input::mcEdit=false;
    }
    recordInput(hwnd,msg,w,l);
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
        if (capture && !(sc::input::nativeKeys && w==sc::input::grappleVk.load() && (msg==WM_KEYDOWN || msg==WM_KEYUP)) &&
            ((msg >= WM_KEYFIRST && msg <= WM_KEYLAST) || (msg >= WM_MOUSEFIRST && msg <= WM_MOUSELAST)))
            return 0;
    }
    if (guard.owns_lock())
        guard.unlock();
    if (sc::input::capture && !(sc::input::nativeKeys && w==sc::input::grappleVk.load() && (msg==WM_KEYDOWN || msg==WM_KEYUP)) &&
        ((msg >= WM_KEYFIRST && msg <= WM_KEYLAST) || (msg >= WM_MOUSEFIRST && msg <= WM_MOUSELAST)))
        return 0;
    if (sc::input::mcEdit) {
        if ((msg >= WM_LBUTTONDOWN && msg <= WM_MBUTTONDBLCLK) || msg == WM_MOUSEWHEEL || msg == WM_CHAR)
            return 0;
        if ((msg == WM_KEYDOWN || msg == WM_KEYUP) &&
            (w == 'I' || w == 'O' || w == 'J' || (w >= '1' && w <= '9')))
            return 0;
    }
    if (sc::input::flying && (msg == WM_KEYDOWN || msg == WM_KEYUP) &&
        (w == 'W' || w == 'A' || w == 'S' || w == 'D' || w == VK_SPACE || w == VK_SHIFT || w == VK_CONTROL))
        return 0;
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
    nativeDevice=a.device.Get();
    a.previousWndProc = reinterpret_cast<WNDPROC>(
        SetWindowLongPtrW(a.window, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(modWndProc)));
    sc::log("Passthrough D3D11 initialized " + std::to_string(a.width) + "x" + std::to_string(a.height));
    return true;
}
// Immediate/deferred context hook implementation follows below.
void recordTargets(ID3D11DeviceContext *context, ID3D11DepthStencilView *dsv) {
    auto &a = *app;
    if (!nativeDevice.load())
        return;
    ++a.targetCalls;
    auto &binding = a.bindings[context];
    if(binding.depth.Get()!=dsv){binding.capturedAt=UINT64_MAX;binding.deferredCaptured=false;}
    binding.depth.Reset();
    if (!dsv)
        return;
    ComPtr<ID3D11Device> device;
    context->GetDevice(&device);
    if (device.Get() != nativeDevice.load())
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
    auto width=a.recordingWidth.load(),height=a.recordingHeight.load();
    if (desc.Width == width && desc.Height <= height && desc.Height >= height / 2 &&
        desc.SampleDesc.Count == 1 && std::abs(aspect-a.recordingAspect.load()) < .03f)
        binding.depth = dsv;
}
bool captureSceneDepth(ID3D11DeviceContext *context) {
    auto &a = *app;
    if (!a.sceneDepth || !a.sceneFrame || a.depthFrame != a.frames ||
        !bridge::Compositor::matchesCamera(a.sceneFrame->meta, a.sceneCamera))
        return false;
    if (!a.sceneSnapshot.capture(context, a.sceneDepth.Get())) {
        ++a.snapshotFailures;
        a.snapshotFrame.reset();
        a.snapshotAt = UINT64_MAX;
        a.worldReason = "The selected native depth could not be copied.";
        return false;
    }
    a.snapshotFrame = a.sceneFrame;
    a.snapshotCamera = a.sceneCamera;
    a.snapshotAt = a.frames;
    return true;
}
void flushImmediateDepth(ID3D11DeviceContext *context) {
    auto &a = *app;
    if (context->GetType() == D3D11_DEVICE_CONTEXT_IMMEDIATE && a.immediateDirty) {
        a.immediateDirty = false;
        captureSceneDepth(context);
    }
}
void submitSceneDraw(const bridge::SceneDraw &draw) {
    auto &a = *app;
    if (draw.clearsDepth) {
        a.depthScores.erase(draw.depth.Get());
        if (a.sceneDepth.Get() == draw.depth.Get()) {
            a.sceneFrame.reset();
            a.sceneCamera = {};
            a.bestDepthScore = 0;
        }
        return;
    }
    auto &score = a.depthScores[draw.depth.Get()];
    score += draw.score;
    if (score >= a.bestDepthScore) {
        a.bestDepthScore = score;
        a.sceneDepth = draw.depth;
        a.sceneViewport = draw.viewport;
        a.depthFrame = a.frames;
        a.sceneCamera = draw.camera;
        a.sceneFrame = draw.frame;
    }
}
template<class F> void withRecording(ID3D11DeviceContext *context,F work) {
    if(!gameContext(context))return;
    app->recorder.run(context->GetType()==D3D11_DEVICE_CONTEXT_DEFERRED,appMutex,work);
}
template <int I>
void STDMETHODCALLTYPE hookClearDepth(ID3D11DeviceContext *context, ID3D11DepthStencilView *depth,
                                      UINT flags, FLOAT value, UINT8 stencil) {
    if (inMod || !ready) {
        originalClearDepth[I](context, depth, flags, value, stencil);
        return;
    }
    Flag flag;
    bool tracked = (flags & D3D11_CLEAR_DEPTH) && depth && gameContext(context);
    bool immediate = context->GetType() == D3D11_DEVICE_CONTEXT_IMMEDIATE;
    if (tracked && immediate) {
        std::unique_lock guard(appMutex, std::try_to_lock);
        if (guard.owns_lock()) {
            if (app->sceneDepth.Get() == depth)
                flushImmediateDepth(context);
        } else
            app->clearBusy.fetch_add(1);
    }
    originalClearDepth[I](context, depth, flags, value, stencil);
    if (!tracked)
        return;
    withRecording(context,[&]{
    auto &a = *app;
    auto bound = a.bindings.find(context);
    if (bound != a.bindings.end() && bound->second.depth.Get() == depth) {
        bound->second.deferredCaptured = false;
        bound->second.capturedAt = UINT64_MAX;
    }
    bridge::SceneDraw clear;
    clear.depth = depth;
    clear.clearsDepth = true;
    if (!immediate)
        a.recordings[context].record(a.recordingGeneration.load(), std::move(clear));
    else
        submitSceneDraw(clear);
    });
}
void recordDraw(ID3D11DeviceContext *context, UINT count) {
    auto &a = *app;
    if (!nativeDevice.load() || count < 100)
        return;
    auto found = a.bindings.find(context);
    if (found == a.bindings.end() || !found->second.depth)
        return;
    auto &bound = found->second;
    if (bound.viewport.Width <= 0 || bound.viewport.Height <= 0)
        return;
    bool deferred = context->GetType() == D3D11_DEVICE_CONTEXT_DEFERRED;
    uint64_t generation = a.recordingGeneration.load();
    if (deferred && bound.recordingGeneration != generation) {
        bound.recordingGeneration = generation;
        bound.deferredCaptured = false;
    }
    if(deferred ? !bound.deferredCaptured : bound.capturedAt!=a.recordingFrame.load()){
        bound.capturedAt=a.recordingFrame.load();
        bound.deferredCaptured=true;
        a.driver.takeCameraFrame(a.cameraFrame);
        if(a.cameraFrame && a.cameraHistory[0]!=a.cameraFrame){
            for(size_t i=a.cameraHistory.size()-1;i>0;--i)a.cameraHistory[i]=a.cameraHistory[i-1];
            a.cameraHistory[0]=a.cameraFrame;
        }
        bound.frame.reset();
        bound.camera=a.host.camera(a.host.player()).value_or(sc::Camera{});
        if(a.recordingReverseDepth.load() && bound.camera.valid){
            bound.camera.projection.at(2,2)=1-bound.camera.projection.at(2,2);
            bound.camera.projection.at(3,2)=-bound.camera.projection.at(3,2);
        }
        // GameRend may copy ChrCam one update later. Select the completed image
        // matching the camera actually used for this native draw, never guess.
        for(const auto &frame:a.cameraHistory)
            if(frame && bridge::Compositor::matchesCamera(frame->meta,bound.camera)){
                bound.frame=frame;break;
            }
    }
    bridge::SceneDraw draw{bound.depth, bound.viewport, bound.camera, bound.frame, count};
    if (deferred)
        a.recordings[context].record(generation, std::move(draw));
    else {
        submitSceneDraw(draw);
        if (a.sceneDepth.Get() == draw.depth.Get())
            a.immediateDirty = true;
    }
}
template <int I>
HRESULT STDMETHODCALLTYPE hookFinishCommands(ID3D11DeviceContext *context, BOOL restore,
                                              ID3D11CommandList **list) {
    if (inMod || !ready)
        return originalFinishCommands[I](context, restore, list);
    Flag flag;
    HRESULT result = originalFinishCommands[I](context, restore, list);
    if(!gameContext(context))return result;
    std::vector<bridge::SceneDraw> draws;
    uint64_t epoch{};
    {
        std::lock_guard guard(app->recorder.mutex());
        auto &a = *app;
        if (context->GetType() != D3D11_DEVICE_CONTEXT_DEFERRED)
            return result;
        auto found = a.recordings.find(context);
        if (found != a.recordings.end()) {
            if (found->second.generation == a.recordingGeneration.load())
                draws = std::move(found->second.draws);
            a.recordings.erase(found);
        }
        // FinishCommandList marks a recording boundary even when the game keeps
        // the context's pipeline bindings for its next command list.
        auto bound = a.bindings.find(context);
        if (bound != a.bindings.end()) {
            bound->second.deferredCaptured = false;
            bound->second.capturedAt = UINT64_MAX;
        }
        if (!restore)
            a.bindings.erase(context);
        epoch = a.resourceEpoch;
        ++a.finishedLists;
    }
    if (SUCCEEDED(result) && list && *list)
        bridge::DeferredScene::attach(*list, epoch, std::move(draws));
    return result;
}
template <int I>
void STDMETHODCALLTYPE hookExecuteCommands(ID3D11DeviceContext *context, ID3D11CommandList *list,
                                            BOOL restore) {
    if (inMod || !ready || !gameContext(context)) {
        originalExecuteCommands[I](context, list, restore);
        return;
    }
    Flag flag;
    // Keep metadata alive across the original call, but never hold appMutex
    // while a driver submits work or invokes internal context operations.
    auto captured = bridge::DeferredScene::read(list);
    {
        std::unique_lock guard(appMutex, std::try_to_lock);
        if (guard.owns_lock())
            flushImmediateDepth(context);
    }
    originalExecuteCommands[I](context, list, restore);
    std::unique_lock guard(appMutex, std::try_to_lock);
    if (!guard.owns_lock()) {
        app->executeBusy.fetch_add(1);
        return;
    }
    auto &a = *app;
    if (!a.initialized || context->GetType() != D3D11_DEVICE_CONTEXT_IMMEDIATE)
        return;
    a.immediateDirty = false;
    if (!restore){std::lock_guard metadataLock(a.recorder.mutex());a.bindings.erase(context);}
    ++a.executedLists;
    if (!captured || captured->resourceEpoch != a.resourceEpoch) {
        // UI/postprocessing lists can be cached or unmarked. They cannot alter
        // an earlier owned depth snapshot, and must not veto that known scene.
        a.unknownLists.fetch_add(1);
        return;
    }
    for (const auto &draw : captured->draws)
        submitSceneDraw(draw);
    // Several recorded batches may write one depth view. Copy once, after the
    // whole command list has executed, and only when it wrote the final winner.
    if (std::any_of(captured->draws.begin(), captured->draws.end(), [&](const auto &draw) {
            return !draw.clearsDepth && draw.depth.Get() == a.sceneDepth.Get();
        }))
        captureSceneDepth(context);
}
template <int I>
void STDMETHODCALLTYPE hookTargets(ID3D11DeviceContext *context, UINT count,
                                   ID3D11RenderTargetView *const *views, ID3D11DepthStencilView *depth) {
    originalTargets[I](context, count, views, depth);
    if (!inMod && ready) {
        Flag flag;
        withRecording(context,[&]{
        auto found = app->bindings.find(context);
        if (found != app->bindings.end() && found->second.depth.Get() != depth)
            flushImmediateDepth(context);
        recordTargets(context, depth);
        });
    }
}
template <int I>
void STDMETHODCALLTYPE hookTargetsUav(ID3D11DeviceContext *context, UINT count,
                                      ID3D11RenderTargetView *const *views, ID3D11DepthStencilView *depth,
                                      UINT start, UINT uavCount, ID3D11UnorderedAccessView *const *uavs,
                                      const UINT *counts) {
    originalTargetsUav[I](context, count, views, depth, start, uavCount, uavs, counts);
    if (count != D3D11_KEEP_RENDER_TARGETS_AND_DEPTH_STENCIL && !inMod && ready) {
        Flag flag;
        withRecording(context,[&]{
        auto found = app->bindings.find(context);
        if (found != app->bindings.end() && found->second.depth.Get() != depth)
            flushImmediateDepth(context);
        recordTargets(context, depth);
        });
    }
}
template <int I>
void STDMETHODCALLTYPE hookViewports(ID3D11DeviceContext *context, UINT count, const D3D11_VIEWPORT *views) {
    originalViewports[I](context, count, views);
    if (!inMod && ready && count && views) {
        Flag flag;
        withRecording(context,[&]{
            app->bindings[context].viewport = views[0];
        });
    }
}
template <int I>
void STDMETHODCALLTYPE hookInstanced(ID3D11DeviceContext *context, UINT count, UINT instances, UINT start,
                                     INT base, UINT first) {
    originalInstanced[I](context, count, instances, start, base, first);
    if (!inMod && ready) {
            Flag flag;
            withRecording(context,[&]{
            ++app->instancedCalls;
            recordDraw(context, count);
            });
    }
}
template <int I>
void STDMETHODCALLTYPE hookDraw(ID3D11DeviceContext *context, UINT count, UINT start, INT base) {
    originalDraw[I](context, count, start, base);
    if (!inMod && ready) {
            Flag flag;
            withRecording(context,[&]{
            ++app->indexedCalls;
            recordDraw(context, count);
            });
    }
}
HRESULT STDMETHODCALLTYPE hookPresent(IDXGISwapChain *swap, UINT interval, UINT flags) {
    if (inMod || !ready || flags & DXGI_PRESENT_TEST)
        return originalPresent(swap, interval, flags);
    std::unique_lock guard(appMutex, std::try_to_lock);
    if (!guard.owns_lock()) {
        app->skippedPresents.fetch_add(1);
        return originalPresent(swap, interval, flags);
    }
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
        flushImmediateDepth(a.context.Get());
        ImGui_ImplDX11_NewFrame();
        ImGui_ImplWin32_NewFrame();
        // Keep ImGui hit testing and cursor drawing on the DXGI surface, too.
        {RECT client{};POINT cursor{};GetClientRect(a.window,&client);GetCursorPos(&cursor);ScreenToClient(a.window,&cursor);
            auto &io=ImGui::GetIO();io.DisplaySize=ImVec2(float(a.width),float(a.height));
            if(client.right>0 && client.bottom>0)io.MousePos=ImVec2(float(cursor.x)*a.width/client.right,float(cursor.y)*a.height/client.bottom);}
        ImGui::NewFrame();
        a.update();
        a.latest.take(a.renderFrame);
        bool frameLocked=(a.control.capabilities&bridge::mcOwnerCapability)!=0;
        const auto &f = frameLocked ? a.snapshotFrame : a.renderFrame;
        const auto &renderCamera = frameLocked ? a.snapshotCamera : a.camera;
        auto *renderDepth = frameLocked ? a.sceneSnapshot.view() : a.sceneDepth.Get();
        auto now = GetTickCount64();
        auto currentFrame = [&](const auto &frame) {
            return frame && frame->meta.epoch == a.epoch && bridge::fresh(now, frame->meta.tickMs) &&
                   bridge::fresh(now, frame->meta.controlTickMs);
        };
        std::string commonReason;
        if (!(a.control.flags & bridge::Scene)) commonReason = "Native scene is not ready.";
        else if (!(a.control.flags & bridge::Focus)) commonReason = "Sekiro is not focused.";
        else if (a.showMenu) commonReason = "Diagnostics menu is open.";
        else if(a.control.flags&bridge::NativeUI)commonReason="Sekiro UI owns input (F6 returns to MC).";
        else if (!(a.mcStatus.load() & 1)) commonReason = "MC peer is not connected.";
        bool submitted = false;
        a.worldReason = commonReason;
        if (a.worldReason.empty() && !currentFrame(f))
            a.worldReason = "No fresh MC world frame with a matching native camera.";
        if (a.worldReason.empty() && frameLocked && a.snapshotAt != a.frames)
            a.worldReason = "No executed scene depth snapshot in this present.";
        if (a.worldReason.empty() && !renderDepth)
            a.worldReason = "Native depth snapshot is unavailable.";
        if (a.worldReason.empty()) {
            if (a.compositor.upload(a.context.Get(), *f))
                submitted = a.compositor.draw(a.context.Get(), a.target.Get(), renderDepth, renderCamera,
                                              float(a.width), float(a.height), !frameLocked, false);
            if (!submitted)
                a.worldReason = a.compositor.error.empty() ? "Native depth/camera validation failed."
                                                           : a.compositor.error;
        }
        bool overlaySubmitted = false;
        a.overlayReason = commonReason;
        if (a.overlayReason.empty() && !currentFrame(a.renderFrame))
            a.overlayReason = "No fresh MC HUD frame.";
        if (a.overlayReason.empty()) {
            if (a.compositor.upload(a.context.Get(), *a.renderFrame))
                overlaySubmitted = a.compositor.drawOverlay(a.context.Get(), a.target.Get(), float(a.width), float(a.height));
            if (!overlaySubmitted)
                a.overlayReason = a.compositor.error.empty() ? "HUD upload/draw failed." : a.compositor.error;
        }
        {
            std::lock_guard lock(eventMutex);
            inputDisplay=overlaySubmitted?bridge::GuiDisplay::fit(a.renderFrame->meta,float(a.width),float(a.height),now):bridge::GuiDisplay{};
        }
        a.status = submitted ? (overlaySubmitted ? "Minecraft world and HUD displayed."
                                                : "Minecraft world displayed; HUD pending.")
                             : (overlaySubmitted ? "HUD displayed; world pending: " + a.worldReason
                                                 : "World: " + a.worldReason + " HUD: " + a.overlayReason);
        bool mcOwnsAvatar=(a.mcStatus.load()&9)==9 && (a.control.flags&bridge::Scene) &&
                           (a.control.capabilities&bridge::mcOwnerCapability);
        a.heroHidden = mcOwnsAvatar && a.hideOriginal && a.host.avatarVisibility(true);
        if (!a.heroHidden) a.host.avatarVisibility(false);
        a.hud();
        ImGui::Render();
        {
            bridge::PipelineState restore(a.context.Get());
            auto target = a.target.Get();
            a.context->OMSetRenderTargets(1, &target, nullptr);
            ImGui_ImplDX11_RenderDrawData(ImGui::GetDrawData());
        }
        ++a.frames;
        a.immediateDirty=false;
        a.sceneFrame.reset();a.sceneCamera={};
        a.depthScores.clear();
        a.bestDepthScore = 0;
    } catch (const std::exception &e) {
        sc::input::capture = false;
        sc::input::mcEdit = false;
        sc::input::flying = false;
        if (app) {
            app->movement.update(app->control, false);
            app->host.avatarVisibility(false);
            app->cursor(false);
        }
        sc::log(std::string("Present: ") + e.what());
    } catch (...) {
        sc::input::capture = false;
        sc::input::mcEdit = false;
        sc::input::flying = false;
        if (app) {
            app->movement.update(app->control, false);
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
        {std::lock_guard metadataLock(a.recorder.mutex());
            a.bindings.clear();a.recordings.clear();
            a.recordingGeneration.fetch_add(1);++a.resourceEpoch;
            a.cameraFrame.reset();a.cameraHistory={};}
        a.sceneSnapshot.reset();a.snapshotFrame.reset();a.snapshotCamera={};
        a.snapshotAt=UINT64_MAX;a.immediateDirty=false;
        a.depthScores.clear();
        a.latest.store(nullptr);
        a.renderFrame.reset();
        a.sceneFrame.reset();a.sceneCamera={};
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
        app->movement.initialize(app->host.base(), app->memory.physics);
        app->driver.initialize(app->host.base(),app->memory);
        app->combat.initialize(app->host.base(),app->memory);
        app->movement.combat(app->combat);
        app->movement.driver(app->driver);
        LARGE_INTEGER counter{};
        QueryPerformanceCounter(&counter);
        app->epoch = (uint64_t(counter.QuadPart) ^ (uint64_t(GetCurrentProcessId()) << 32)) | 1;
        app->control.yOffset =
            float(GetPrivateProfileIntW(L"SekiroBridge", L"y_offset", 128, config.c_str()));
        app->reverseDepth = GetPrivateProfileIntW(L"SekiroBridge", L"reverse_depth", 1, config.c_str()) != 0;
        app->hideOriginal = GetPrivateProfileIntW(L"SekiroBridge", L"hide_original", 1, config.c_str()) != 0;
        app->playerFeatures = GetPrivateProfileIntW(L"SekiroBridge", L"player_features", 1, config.c_str()) != 0;
        wchar_t grappleKey[8]{};
        GetPrivateProfileStringW(L"SekiroBridge",L"grapple_key",L"G",grappleKey,8,config.c_str());
        if(grappleKey[0] && !grappleKey[1])sc::input::configureGrapple(grappleKey[0]);
        sc::log("Native grapple binding="+std::string(1,char(sc::input::grappleVk.load())));
        app->exportWidth =
            std::clamp(GetPrivateProfileIntW(L"SekiroBridge", L"capture_width", 1280, config.c_str()), 320u,
                       bridge::maxWidth);
        if (!installHooks()) {
            sc::log("Hook installation failed.");
            return 0;
        }
        if(GetPrivateProfileIntW(L"SekiroBridge",L"combat_trace",0,config.c_str()))
            sc::log(app->combatTrace.install(app->host.base(),app->combat.postureReady(),sc::dataRoot)
                    ? "Combat trace hooks installed." : "Combat trace unavailable; original combat retained.");
        if(GetPrivateProfileIntW(L"SekiroBridge",L"native_hits",0,config.c_str()))
            sc::log(app->combatGameHook.install(app->host.base(),app->combat)
                    ? "Experimental native normal-hit dispatch installed on AttackManager update."
                    : "Native hit dispatch signatures unavailable; compatibility combat retained.");
        if(GetPrivateProfileIntW(L"SekiroBridge",L"native_phase_finish",0,config.c_str()))
            sc::log(app->combat.enablePhaseFinishes(true)?"Candidate remote stage profile enabled for directed acceptance.":
                "Remote stage profile disabled: native signatures unavailable.");
        if(GetPrivateProfileIntW(L"SekiroBridge",L"auto_boss_phases",0,config.c_str()))
            sc::log(app->combat.enableAutoBossPhases(app->combat.nativeHits())?
                "Simplified HP-depleted Boss stage completion enabled.":"Auto Boss stages unavailable: combat/node signatures missing.");
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
            bridge::PeerStatus peer;
            for (;;) {
                try {
                    auto f = app->memory.readFrame(previous, app->epoch);
                    if (f) {
                        previous = f->meta.sequence;
                        app->driver.receiveFrame(f);
                        app->latest.store(std::move(f));
                    }
                    // A zero-wait lock miss is not a disconnect. Cached heartbeats
                    // still expire, and a successfully read explicit off clears immediately.
                    if (!app->memory.readStatus(peer))
                        app->statusBusy.fetch_add(1);
                    app->mcStatus = peer.flagsFor(app->epoch, GetTickCount64());
                } catch (...) {
                    app->mcStatus = 0;
                    app->latest.store(nullptr);
                    app->driver.receiveFrame(nullptr);
                }
                Sleep(8);
            }
        }).detach();
        sc::log("Bridge ready: camera, input, depth composite, local entity terrain and verified native HP adapter.");
    } catch (const std::exception &e) {
        sc::log(std::string("Bootstrap: ") + e.what());
    } catch (...) {
        sc::log("Bootstrap failed.");
    }
    return 0;
}
