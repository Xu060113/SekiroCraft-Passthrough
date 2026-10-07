#include "../src/input_capture.hpp"
#include <iostream>
#include <stdexcept>
namespace {
struct Device {
    void **table;
    DWORD type = DI8DEVTYPE_KEYBOARD;
    bool wide = true;
};
HRESULT response = S_OK;
int nativeCalls{}, flushes{};
DWORD returnedEvents=2;
HRESULT STDMETHODCALLTYPE info(void *self, void *out) {
    auto &d = *static_cast<Device *>(self);
    auto size = *static_cast<DWORD *>(out);
    if ((d.wide && size != sizeof(DIDEVICEINSTANCEW)) || (!d.wide && size != sizeof(DIDEVICEINSTANCEA)))
        return DIERR_INVALIDPARAM;
    // Both layouts share dwSize, guidInstance, guidProduct, dwDevType.
    static_cast<DIDEVICEINSTANCEW *>(out)->dwDevType = d.type;
    return S_OK;
}
HRESULT STDMETHODCALLTYPE state(void *, DWORD n, void *out) {
    ++nativeCalls;
    if (SUCCEEDED(response) && out)
        std::memset(out, 0x80, n);
    return response;
}
HRESULT STDMETHODCALLTYPE zeroState(void *,DWORD n,void *out){std::memset(out,0,n);return S_OK;}
HRESULT STDMETHODCALLTYPE data(void *, DWORD, DIDEVICEOBJECTDATA *, DWORD *n, DWORD) {
    ++nativeCalls;
    if (SUCCEEDED(response) && n) {
        if (*n == INFINITE)
            ++flushes;
        *n = returnedEvents;
    }
    return response;
}
void check(bool ok, const char *why) {
    if (!ok)
        throw std::runtime_error(why);
}
} // namespace
int main() {
    try {
        std::array<void *, 16> table{};
        table[15] = reinterpret_cast<void *>(info);
        Device device{table.data()};
        sc::input::originalState[0] = state;
        sc::input::originalData[0] = data;
        std::array<unsigned char, 32> bytes{};
        sc::input::capture = false;
        check(sc::input::stateHook<0>(&device, bytes.size(), bytes.data()) == S_OK && bytes[0] == 0x80,
              "inactive input forwarding");
        sc::input::capture = true;
        check(sc::input::stateHook<0>(&device, bytes.size(), bytes.data()) == S_OK &&
                  std::all_of(bytes.begin(), bytes.end(), [](auto b) { return b == 0; }),
              "custom keyboard data format capture");
        device.type = DI8DEVTYPE_MOUSE;
        device.wide = false;
        check(sc::input::stateHook<0>(&device, bytes.size(), bytes.data()) == S_OK && bytes[0] == 0,
              "ANSI mouse metadata capture");
        device.type = DI8DEVTYPE_JOYSTICK;
        sc::input::mcOwner=true;
        device.type=DI8DEVTYPE_MOUSE;
        DIMOUSESTATE2 rawMouse{};
        check(sc::input::stateHook<0>(&device,sizeof(rawMouse),&rawMouse)==S_OK && rawMouse.lX==0 &&
            sc::input::mouseDx.load()==LONG(0x80808080) && sc::input::mouseDy.load()==LONG(0x80808080),
            "MC receives relative counts before the native input is suppressed");
        sc::input::mcOwner=false;
        device.type = DI8DEVTYPE_JOYSTICK;
        check(sc::input::stateHook<0>(&device, bytes.size(), bytes.data()) == S_OK && bytes[0] == 0x80,
              "joystick preserved despite same buffer length");
        device.type = DI8DEVTYPE_KEYBOARD;
        response = DIERR_NOTACQUIRED;
        check(sc::input::stateHook<0>(&device, bytes.size(), bytes.data()) == DIERR_NOTACQUIRED &&
                  bytes[0] == 0x80,
              "native acquisition errors preserved");
        response = DI_BUFFEROVERFLOW;
        DWORD count = 4;
        DIDEVICEOBJECTDATA events[4]{};
        check(sc::input::dataHook<0>(&device, sizeof(events[0]), events, &count, DIGDD_PEEK) ==
                      DI_BUFFEROVERFLOW &&
                  count == 0 && flushes == 1,
              "buffer overflow and peek drain");
        sc::input::capture = false;
        count = 4;
        check(sc::input::dataHook<0>(&device, sizeof(events[0]), events, &count, 0) == DI_BUFFEROVERFLOW &&
                  count == 2 && flushes == 1,
              "buffer events forward after closing");
        sc::input::capture = true;
        response = DIERR_INPUTLOST;
        count = 4;
        check(sc::input::dataHook<0>(&device, sizeof(events[0]), events, &count, 0) == DIERR_INPUTLOST &&
                  count == 4 && flushes == 1,
              "buffer acquisition error does not flush");
        check(nativeCalls == 10, "every intercepted call forwards once, plus one peek-drain call");
        sc::input::capture = false;
        response = S_OK;
        sc::input::mcEdit = true;
        device.type = DI8DEVTYPE_MOUSE;
        DIMOUSESTATE2 mouse{};
        check(sc::input::stateHook<0>(&device, sizeof(mouse), &mouse) == S_OK && mouse.rgbButtons[0] == 0 &&
                  mouse.rgbButtons[1] == 0 && mouse.rgbButtons[2] == 0 && mouse.rgbButtons[3] == 0 &&
                  mouse.rgbButtons[4] == 0 && mouse.rgbButtons[5] == 0x80 &&
                  mouse.lX != 0 && mouse.lY != 0,
              "MC five-button actions isolated while native camera delta and unsupported buttons remain");
        struct {DIMOUSESTATE state{};uint32_t sentinel=0x12345678;} shortMouse;
        check(sc::input::stateHook<0>(&device,sizeof(shortMouse.state),&shortMouse.state)==S_OK &&
              shortMouse.state.rgbButtons[3]==0 && shortMouse.sentinel==0x12345678,
              "four-button DIMOUSESTATE side capture does not overrun the native format");
        device.type = DI8DEVTYPE_KEYBOARD;
        std::array<unsigned char, 256> keyboard{};
        check(sc::input::stateHook<0>(&device, keyboard.size(), keyboard.data()) == S_OK &&
                  keyboard[DIK_I] == 0 && keyboard[DIK_O] == 0 && keyboard[DIK_J] == 0 &&
                  keyboard[DIK_E] == 0x80 && keyboard[DIK_Q] == 0x80 && keyboard[DIK_F] == 0x80 &&
                  keyboard[DIK_1] == 0 && keyboard[DIK_9] == 0 && keyboard[DIK_W] == 0x80 &&
                  keyboard[DIK_LSHIFT] == 0x80,
              "MC keys isolated while host movement remains");
        events[0].dwOfs = DIK_I;
        events[1].dwOfs = DIK_W;
        count = 4;
        check(sc::input::dataHook<0>(&device, sizeof(events[0]), events, &count, 0) == S_OK && count == 1 &&
                  events[0].dwOfs == DIK_W,
              "buffered keyboard filtering preserves movement");
        device.type = DI8DEVTYPE_MOUSE;
        events[0].dwOfs = DIMOFS_BUTTON0;
        events[1].dwOfs = DIMOFS_X;
        events[2].dwOfs = DIMOFS_BUTTON3;
        events[3].dwOfs = DIMOFS_BUTTON4;
        returnedEvents=4;
        count = 4;
        check(sc::input::dataHook<0>(&device, sizeof(events[0]), events, &count, DIGDD_PEEK) == S_OK &&
                  count == 1 && events[0].dwOfs == DIMOFS_X && flushes == 2,
              "buffered five-button filtering drains peeked actions, including both side buttons");
        returnedEvents=2;
        device.type = DI8DEVTYPE_JOYSTICK;
        check(sc::input::stateHook<0>(&device, bytes.size(), bytes.data()) == S_OK && bytes[0] == 0x80,
              "MC edit mode leaves gamepads untouched");
        sc::input::mcEdit = false;
        sc::input::flying = true;
        device.type = DI8DEVTYPE_KEYBOARD;
        check(sc::input::stateHook<0>(&device, keyboard.size(), keyboard.data()) == S_OK &&
                  keyboard[DIK_W] == 0 && keyboard[DIK_SPACE] == 0 && keyboard[DIK_LSHIFT] == 0 &&
                  keyboard[DIK_E] == 0x80 && keyboard[DIK_I] == 0x80,
              "flight movement isolated while other host keys remain");
        sc::input::flying = false;
        sc::input::mcOwner=true;sc::input::capture=true;sc::input::mouseStateTick=0;
        device.type=DI8DEVTYPE_MOUSE;
        DIDEVICEOBJECTDATA relative[2]{};
        relative[0].dwOfs=DIMOFS_X;relative[0].dwData=3;
        relative[1].dwOfs=DIMOFS_Y;relative[1].dwData=DWORD(-2);
        auto oldX=sc::input::mouseDx.load(),oldY=sc::input::mouseDy.load();count=2;
        check(sc::input::dataHook<0>(&device,sizeof(relative[0]),relative,&count,DIGDD_PEEK)==S_OK &&
            count==0 && sc::input::mouseDx.load()==oldX+3 && sc::input::mouseDy.load()==oldY-2,
            "buffered-only mouse deltas forwarded before capture and peek drain");
        sc::input::mcOwner=false;sc::input::capture=false;
        sc::input::capture=true;sc::input::nativeKeys=true;
        device.type=DI8DEVTYPE_KEYBOARD;
        sc::input::stateHook<0>(&device,keyboard.size(),keyboard.data());
        check(keyboard[DIK_G]==0 && keyboard[DIK_W]==0 && keyboard[DIK_E]==0,
            "a native action does not leak physical keys without a grapple request");
        sc::input::originalState[0]=zeroState;sc::input::grappleUntil=GetTickCount64()+1000;
        sc::input::stateHook<0>(&device,keyboard.size(),keyboard.data());
        check(keyboard[DIK_G]==0x80 && keyboard[DIK_W]==0,"message-only fast grapple tap survives release before native poll");
        count=4;sc::input::dataHook<0>(&device,sizeof(events[0]),events,&count,0);
        check(count==1 && events[0].dwOfs==DIK_G && events[0].dwData==0x80,"buffered grapple tap down");
        sc::input::grappleUntil=0;count=4;sc::input::dataHook<0>(&device,sizeof(events[0]),events,&count,0);
        check(count==1 && events[0].dwData==0,"buffered grapple tap release");
        check(sc::input::configureGrapple(L'm') && sc::input::grappleVk=='M' && sc::input::grappleScan==DIK_M,
            "user M binding maps to the real DirectInput scan code");
        sc::input::grappleUntil=GetTickCount64()+1000;
        sc::input::stateHook<0>(&device,keyboard.size(),keyboard.data());
        check(keyboard[DIK_M]==0x80 && keyboard[DIK_G]==0 && keyboard[DIK_E]==0,
            "MC grapple request targets configured M instead of the old hardcoded G");
        count=4;sc::input::dataHook<0>(&device,sizeof(events[0]),events,&count,0);
        check(count==1 && events[0].dwOfs==DIK_M && events[0].dwData==0x80,"buffered M grapple down");
        sc::input::nativeKeys=false;
        sc::input::stateHook<0>(&device,keyboard.size(),keyboard.data());
        check(keyboard[DIK_M]==0,"opening MC GUI suppresses even a pending mapped grapple pulse");
        count=4;sc::input::dataHook<0>(&device,sizeof(events[0]),events,&count,0);
        check(count==1 && events[0].dwOfs==DIK_M && events[0].dwData==0,"buffered M release on GUI entry");
        check(!sc::input::configureGrapple(L'!') && sc::input::grappleVk=='M',"invalid grapple configuration retains last valid key");
        sc::input::nativeKeys=true;sc::input::originalState[0]=state;
        sc::input::grappleUntil=GetTickCount64()+1000;
        sc::input::stateHook<0>(&device,keyboard.size(),keyboard.data());
        check(keyboard[DIK_M]==0x80 && keyboard[DIK_W]==0 && keyboard[DIK_SPACE]==0 && keyboard[DIK_LSHIFT]==0,
            "M-only grapple never forwards native movement, jump or sprint");
        count=4;sc::input::dataHook<0>(&device,sizeof(events[0]),events,&count,0);
        check(count==1 && events[0].dwOfs==DIK_M && events[0].dwData==0x80,
            "buffered M-only grapple emits only its action key despite held movement");
        sc::input::nativeKeys=false;count=4;sc::input::dataHook<0>(&device,sizeof(events[0]),events,&count,0);
        check(count==1 && events[0].dwOfs==DIK_M && events[0].dwData==0,
            "grapple interruption releases only M without native movement events");
        sc::input::grappleUntil=0;sc::input::configureGrapple(L'G');sc::input::nativeKeys=true;
        sc::input::originalState[0]=state;
        device.type=DI8DEVTYPE_MOUSE;sc::input::attackUntil=GetTickCount64()+1000;
        sc::input::stateHook<0>(&device,sizeof(mouse),&mouse);
        check(mouse.rgbButtons[0]==0x80 && mouse.rgbButtons[1]==0 && mouse.lX==0,
            "native finisher pulse never leaks inventory/use/camera input");
        count=4;sc::input::dataHook<0>(&device,sizeof(events[0]),events,&count,0);
        check(count==1 && events[0].dwOfs==DIMOFS_BUTTON0 && events[0].dwData==0x80,"buffered native attack down");
        count=4;sc::input::dataHook<0>(&device,sizeof(events[0]),events,&count,0);
        check(count==0,"held finisher pulse does not manufacture repeat downs");
        sc::input::attackUntil=0;count=4;sc::input::dataHook<0>(&device,sizeof(events[0]),events,&count,0);
        check(count==1 && events[0].dwData==0,"buffered native attack releases");
        sc::input::nativeKeys=false;sc::input::capture=false;
        check(MH_Initialize() == MH_OK, "MinHook init");
        check(sc::input::install(), "real system DirectInput hook installation");
        // This creates system devices but never acquires, reads a user's keystroke,
        // injects input, opens Sekiro or interacts with a live game.
        MH_DisableHook(MH_ALL_HOOKS);
        MH_Uninitialize();
        std::cout
            << "PASS input forwarding, keyboard/mouse capture, ANSI/Wide metadata, custom format, joystick "
               "preservation, error propagation, peek draining and real system hook installation\n";
        return 0;
    } catch (const std::exception &e) {
        std::cerr << "FAIL: " << e.what() << "\n";
        return 1;
    }
}
