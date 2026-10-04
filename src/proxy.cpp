#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#define DIRECTINPUT_VERSION 0x0800
#include <dinput.h>
#include <mutex>
#include <windows.h>

HMODULE scModule{};
extern DWORD WINAPI scBootstrap(void *);
namespace {
HMODULE realInput{};
std::once_flag loadFlag, bootFlag;
FARPROC forward(const char *name) {
    std::call_once(loadFlag, [] {
        wchar_t path[MAX_PATH]{};
        GetSystemDirectoryW(path, MAX_PATH);
        wcscat_s(path, L"\\dinput8.dll");
        realInput = LoadLibraryW(path);
    });
    return realInput ? GetProcAddress(realInput, name) : nullptr;
}
void boot() {
    std::call_once(bootFlag, [] {
        HANDLE thread = CreateThread(nullptr, 0, scBootstrap, nullptr, 0, nullptr);
        if (thread)
            CloseHandle(thread);
    });
}
} // namespace
extern "C" HRESULT WINAPI DirectInput8Create(HINSTANCE h, DWORD version, REFIID iid, LPVOID *out,
                                             LPUNKNOWN unknown) {
    using F = HRESULT(WINAPI *)(HINSTANCE, DWORD, REFIID, LPVOID *, LPUNKNOWN);
    auto f = reinterpret_cast<F>(forward("DirectInput8Create"));
    if (!f)
        return E_FAIL;
    auto hr = f(h, version, iid, out, unknown);
    boot();
    return hr;
}
extern "C" HRESULT WINAPI DllGetClassObject(REFCLSID cls, REFIID iid, LPVOID *out) {
    using F = HRESULT(WINAPI *)(REFCLSID, REFIID, LPVOID *);
    auto f = reinterpret_cast<F>(forward("DllGetClassObject"));
    boot();
    return f ? f(cls, iid, out) : E_FAIL;
}
extern "C" HRESULT WINAPI DllCanUnloadNow() {
    using F = HRESULT(WINAPI *)();
    auto f = reinterpret_cast<F>(forward("DllCanUnloadNow"));
    return f ? f() : S_FALSE;
}
extern "C" HRESULT WINAPI DllRegisterServer() {
    using F = HRESULT(WINAPI *)();
    auto f = reinterpret_cast<F>(forward("DllRegisterServer"));
    return f ? f() : E_FAIL;
}
extern "C" HRESULT WINAPI DllUnregisterServer() {
    using F = HRESULT(WINAPI *)();
    auto f = reinterpret_cast<F>(forward("DllUnregisterServer"));
    return f ? f() : E_FAIL;
}
extern "C" const DIDATAFORMAT *WINAPI GetdfDIJoystick() {
    using F = const DIDATAFORMAT *(WINAPI *)();
    auto f = reinterpret_cast<F>(forward("GetdfDIJoystick"));
    return f ? f() : nullptr;
}
BOOL WINAPI DllMain(HINSTANCE instance, DWORD reason, LPVOID) {
    if (reason == DLL_PROCESS_ATTACH) {
        scModule = instance;
        DisableThreadLibraryCalls(instance);
    }
    return TRUE;
}
