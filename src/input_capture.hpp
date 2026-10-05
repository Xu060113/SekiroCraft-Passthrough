#pragma once
#ifndef DIRECTINPUT_VERSION
#define DIRECTINPUT_VERSION 0x0800
#endif
#include <windows.h>
#include <dinput.h>
#include "MinHook.h"
#include <array>
#include <algorithm>
#include <atomic>
#include <cstring>
#include <vector>
#include <type_traits>

namespace sc::input {
inline std::atomic<bool> capture{false};
inline std::atomic<bool> mcEdit{false};
inline std::atomic<bool> flying{false};
inline std::atomic<bool> mcOwner{false};
inline std::atomic<bool> nativeKeys{false};
inline std::atomic<uint64_t> attackUntil{};
inline std::atomic<uint64_t> grappleUntil{};
inline std::array<std::atomic<bool>,4> bufferedAttack{};
inline std::array<std::atomic<bool>,4> bufferedGrapple{};
inline bool attackHeld(){return GetTickCount64()<attackUntil.load(std::memory_order_relaxed);}
inline bool grappleHeld(){return GetTickCount64()<grappleUntil.load(std::memory_order_relaxed);}
inline std::atomic<int64_t> mouseDx{},mouseDy{};
inline std::atomic<uint64_t> mouseStateTick{},mouseStates{},mouseData{};
using State = HRESULT(STDMETHODCALLTYPE *)(void *, DWORD, void *);
using Data = HRESULT(STDMETHODCALLTYPE *)(void *, DWORD, DIDEVICEOBJECTDATA *, DWORD *, DWORD);
inline std::array<State, 4> originalState{};
inline std::array<Data, 4> originalData{};
inline DWORD deviceType(void *device) {
    if (!device)
        return false;
    using Info = HRESULT(STDMETHODCALLTYPE *)(void *, void *);
    auto table = *static_cast<void ***>(device);
    auto info = reinterpret_cast<Info>(table[15]);
    DIDEVICEINSTANCEW wide{};
    wide.dwSize = sizeof(wide);
    DWORD type{};
    if (SUCCEEDED(info(device, &wide)))
        type = wide.dwDevType;
    else {
        DIDEVICEINSTANCEA narrow{};
        narrow.dwSize = sizeof(narrow);
        if (FAILED(info(device, &narrow)))
            return false;
        type = narrow.dwDevType;
    }
    type = GET_DIDEVICE_TYPE(type);
    return type;
}
inline bool keyboardOrMouse(void *device) {
    auto type = deviceType(device);
    return type == DI8DEVTYPE_KEYBOARD || type == DI8DEVTYPE_MOUSE;
}
inline bool actionKey(DWORD key) {
    return key == DIK_I || key == DIK_O || key == DIK_J || (key >= DIK_1 && key <= DIK_9);
}
inline bool flightKey(DWORD key) {
    return flying.load(std::memory_order_relaxed) &&
        (key == DIK_W || key == DIK_A || key == DIK_S || key == DIK_D || key == DIK_SPACE ||
         key == DIK_LSHIFT || key == DIK_RSHIFT || key == DIK_LCONTROL || key == DIK_RCONTROL);
}
template <int I> inline HRESULT STDMETHODCALLTYPE stateHook(void *device, DWORD size, void *out) {
    auto result = originalState[I](device, size, out);
    if (SUCCEEDED(result) && out && deviceType(device)==DI8DEVTYPE_MOUSE &&
        (size==sizeof(DIMOUSESTATE) || size==sizeof(DIMOUSESTATE2))) {
        mouseStateTick=GetTickCount64();
        if(mcOwner){auto mouse=static_cast<DIMOUSESTATE*>(out);
            mouseDx.fetch_add(mouse->lX); mouseDy.fetch_add(mouse->lY);mouseStates.fetch_add(1);}
    }
    // Preserve native validation and acquisition errors; classify the device itself,
    // rather than guessing from its custom data format's buffer length.
    if (SUCCEEDED(result) && out && capture.load(std::memory_order_relaxed) && keyboardOrMouse(device)) {
        auto type=deviceType(device);
        uint8_t grapple=type==DI8DEVTYPE_KEYBOARD && size==256 && nativeKeys?
            static_cast<uint8_t*>(out)[DIK_G]:0;
        std::memset(out, 0, size);
        if(type==DI8DEVTYPE_KEYBOARD && size==256)static_cast<uint8_t*>(out)[DIK_G]=
            nativeKeys && grappleHeld()?0x80:grapple;
        if(type==DI8DEVTYPE_MOUSE && (size==sizeof(DIMOUSESTATE) || size==sizeof(DIMOUSESTATE2)) && nativeKeys && attackHeld())
            static_cast<DIMOUSESTATE*>(out)->rgbButtons[0]=0x80;
    }
    else if (SUCCEEDED(result) && out && (mcEdit.load(std::memory_order_relaxed) || flying.load(std::memory_order_relaxed))) {
        auto type = deviceType(device);
        if (type == DI8DEVTYPE_MOUSE && (size == sizeof(DIMOUSESTATE) || size == sizeof(DIMOUSESTATE2))) {
            auto mouse = static_cast<DIMOUSESTATE *>(out);
            mouse->rgbButtons[0] = mouse->rgbButtons[1] = mouse->rgbButtons[2] = 0;
            mouse->lZ = 0;
        } else if (type == DI8DEVTYPE_KEYBOARD && size == 256) {
            for (DWORD k = 0; k < 256; ++k)
                if ((mcEdit.load(std::memory_order_relaxed) && actionKey(k)) || flightKey(k))
                    static_cast<uint8_t *>(out)[k] = 0;
        }
    }
    return result;
}
template <int I>
inline HRESULT STDMETHODCALLTYPE dataHook(void *device, DWORD size, DIDEVICEOBJECTDATA *out, DWORD *count,
                                          DWORD flags) {
    DWORD capacity=count?*count:0;
    auto result = originalData[I](device, size, out, count, flags);
    if(SUCCEEDED(result) && out && count && size==sizeof(DIDEVICEOBJECTDATA) && mcOwner &&
        deviceType(device)==DI8DEVTYPE_MOUSE && GetTickCount64()-mouseStateTick.load()>100){
        // Buffered-only input users have no DIMOUSESTATE delta to sample. Do not
        // count both streams when the game is also polling mouse state. A peek
        // is drained by the capture branch below, so its deltas are consumed once.
        for(DWORD i=0;i<*count;++i){
            if(out[i].dwOfs==DIMOFS_X)mouseDx.fetch_add(LONG(out[i].dwData));
            if(out[i].dwOfs==DIMOFS_Y)mouseDy.fetch_add(LONG(out[i].dwData));
        }
        mouseData.fetch_add(1);
    }
    if (SUCCEEDED(result) && count && capture.load(std::memory_order_relaxed) && keyboardOrMouse(device)) {
        DWORD type=deviceType(device),written=0;
        // Also drain peeked events. Otherwise menu clicks could replay after closing.
        DWORD discarded = INFINITE;
        originalData[I](device, size, nullptr, &discarded, 0);
        if(out && size==sizeof(DIDEVICEOBJECTDATA) && type==DI8DEVTYPE_MOUSE && capacity){
            bool held=nativeKeys && attackHeld();
            bool old=bufferedAttack[I].exchange(held);
            if(old!=held){DIDEVICEOBJECTDATA event{};event.dwOfs=DIMOFS_BUTTON0;event.dwData=held?0x80:0;
                event.dwTimeStamp=DWORD(GetTickCount64());out[written++]=event;}
        }
        if(out && size==sizeof(DIDEVICEOBJECTDATA) && type==DI8DEVTYPE_KEYBOARD && capacity){
            bool held=nativeKeys && grappleHeld();bool old=bufferedGrapple[I].exchange(held);
            if(old!=held){DIDEVICEOBJECTDATA event{};event.dwOfs=DIK_G;event.dwData=held?0x80:0;
                event.dwTimeStamp=DWORD(GetTickCount64());out[written++]=event;}
        }
        *count = written;
    } else if (SUCCEEDED(result) && out && count && size == sizeof(DIDEVICEOBJECTDATA) &&
               (mcEdit.load(std::memory_order_relaxed) || flying.load(std::memory_order_relaxed))) {
        DWORD type = deviceType(device), written = 0;
        for (DWORD i = 0; i < *count; ++i) {
            bool drop = type == DI8DEVTYPE_MOUSE &&
                        (out[i].dwOfs >= DIMOFS_BUTTON0 && out[i].dwOfs <= DIMOFS_BUTTON2);
            drop = drop || (type == DI8DEVTYPE_MOUSE && out[i].dwOfs == DIMOFS_Z);
            drop = drop || (type == DI8DEVTYPE_KEYBOARD &&
                           ((mcEdit.load(std::memory_order_relaxed) && actionKey(out[i].dwOfs)) || flightKey(out[i].dwOfs)));
            if (!drop)
                out[written++] = out[i];
        }
        if (written != *count && (flags & DIGDD_PEEK)) {
            DWORD discarded = INFINITE;
            originalData[I](device, size, nullptr, &discarded, 0);
        }
        *count = written;
    }
    return result;
}
inline bool install() {
    wchar_t path[MAX_PATH]{};
    GetSystemDirectoryW(path, MAX_PATH);
    wcscat_s(path, L"\\dinput8.dll");
    // Keep the real implementation resident while its entry points are hooked.
    static HMODULE module = LoadLibraryW(path);
    using Create = HRESULT(WINAPI *)(HINSTANCE, DWORD, REFIID, void **, LPUNKNOWN);
    auto create = module ? reinterpret_cast<Create>(GetProcAddress(module, "DirectInput8Create")) : nullptr;
    if (!create)
        return false;
    std::vector<void *> stateTargets, dataTargets;
    auto collect = [&](auto *input) {
        for (auto id : {GUID_SysKeyboard, GUID_SysMouse}) {
            using Device = std::conditional_t<std::is_same_v<decltype(input), IDirectInput8W *>,
                                              IDirectInputDevice8W, IDirectInputDevice8A>;
            Device *device{};
            if (SUCCEEDED(input->CreateDevice(id, &device, nullptr))) {
                auto table = *reinterpret_cast<void ***>(device);
                if (std::find(stateTargets.begin(), stateTargets.end(), table[9]) == stateTargets.end())
                    stateTargets.push_back(table[9]);
                if (std::find(dataTargets.begin(), dataTargets.end(), table[10]) == dataTargets.end())
                    dataTargets.push_back(table[10]);
                device->Release();
            }
        }
    };
    IDirectInput8W *wide{};
    IDirectInput8A *narrow{};
    if (SUCCEEDED(create(GetModuleHandleW(nullptr), DIRECTINPUT_VERSION, IID_IDirectInput8W,
                         reinterpret_cast<void **>(&wide), nullptr))) {
        collect(wide);
        wide->Release();
    }
    if (SUCCEEDED(create(GetModuleHandleW(nullptr), DIRECTINPUT_VERSION, IID_IDirectInput8A,
                         reinterpret_cast<void **>(&narrow), nullptr))) {
        collect(narrow);
        narrow->Release();
    }
    if (stateTargets.empty() || dataTargets.empty() || stateTargets.size() > 4 || dataTargets.size() > 4)
        return false;
    const std::array<State, 4> stateHooks{stateHook<0>, stateHook<1>, stateHook<2>, stateHook<3>};
    const std::array<Data, 4> dataHooks{dataHook<0>, dataHook<1>, dataHook<2>, dataHook<3>};
    std::vector<void *> created;
    auto rollback = [&] {
        for (auto target : created) {
            MH_DisableHook(target);
            MH_RemoveHook(target);
        }
        return false;
    };
    for (size_t i = 0; i < stateTargets.size(); ++i) {
        if (MH_CreateHook(stateTargets[i], reinterpret_cast<void *>(stateHooks[i]),
                          reinterpret_cast<void **>(&originalState[i])) != MH_OK)
            return rollback();
        created.push_back(stateTargets[i]);
    }
    for (size_t i = 0; i < dataTargets.size(); ++i) {
        if (MH_CreateHook(dataTargets[i], reinterpret_cast<void *>(dataHooks[i]),
                          reinterpret_cast<void **>(&originalData[i])) != MH_OK)
            return rollback();
        created.push_back(dataTargets[i]);
    }
    for (auto target : created)
        if (MH_EnableHook(target) != MH_OK)
            return rollback();
    return true;
}
} // namespace sc::input
