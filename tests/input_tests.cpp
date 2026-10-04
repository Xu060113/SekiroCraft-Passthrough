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
HRESULT STDMETHODCALLTYPE data(void *, DWORD, DIDEVICEOBJECTDATA *, DWORD *n, DWORD) {
    ++nativeCalls;
    if (SUCCEEDED(response) && n) {
        if (*n == INFINITE)
            ++flushes;
        *n = 2;
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
        check(nativeCalls == 9, "every intercepted call forwards once, plus one peek-drain call");
        sc::input::capture = false;
        response = S_OK;
        sc::input::mcEdit = true;
        device.type = DI8DEVTYPE_MOUSE;
        DIMOUSESTATE2 mouse{};
        check(sc::input::stateHook<0>(&device, sizeof(mouse), &mouse) == S_OK && mouse.rgbButtons[0] == 0 &&
                  mouse.rgbButtons[1] == 0 && mouse.rgbButtons[2] == 0 && mouse.rgbButtons[3] == 0x80 &&
                  mouse.lX != 0 && mouse.lY != 0,
              "MC actions isolated while native camera delta and extra buttons remain");
        device.type = DI8DEVTYPE_KEYBOARD;
        std::array<unsigned char, 256> keyboard{};
        check(sc::input::stateHook<0>(&device, keyboard.size(), keyboard.data()) == S_OK &&
                  keyboard[DIK_E] == 0 && keyboard[DIK_Q] == 0 && keyboard[DIK_F] == 0 &&
                  keyboard[DIK_1] == 0 && keyboard[DIK_9] == 0 && keyboard[DIK_W] == 0x80 &&
                  keyboard[DIK_LSHIFT] == 0x80,
              "MC keys isolated while host movement remains");
        events[0].dwOfs = DIK_E;
        events[1].dwOfs = DIK_W;
        count = 4;
        check(sc::input::dataHook<0>(&device, sizeof(events[0]), events, &count, 0) == S_OK && count == 1 &&
                  events[0].dwOfs == DIK_W,
              "buffered keyboard filtering preserves movement");
        device.type = DI8DEVTYPE_MOUSE;
        events[0].dwOfs = DIMOFS_BUTTON0;
        events[1].dwOfs = DIMOFS_X;
        count = 4;
        check(sc::input::dataHook<0>(&device, sizeof(events[0]), events, &count, DIGDD_PEEK) == S_OK &&
                  count == 1 && events[0].dwOfs == DIMOFS_X && flushes == 2,
              "buffered mouse filtering drains peeked actions");
        device.type = DI8DEVTYPE_JOYSTICK;
        check(sc::input::stateHook<0>(&device, bytes.size(), bytes.data()) == S_OK && bytes[0] == 0x80,
              "MC edit mode leaves gamepads untouched");
        sc::input::mcEdit = false;
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
