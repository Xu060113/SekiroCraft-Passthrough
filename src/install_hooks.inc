bool installHooks() {
    const wchar_t *cls = L"SekiroPassthroughHookProbe";
    WNDCLASSW wc{};
    wc.lpfnWndProc = DefWindowProcW;
    wc.hInstance = scModule;
    wc.lpszClassName = cls;
    RegisterClassW(&wc);
    HWND window =
        CreateWindowW(cls, L"", WS_OVERLAPPEDWINDOW, 0, 0, 32, 32, nullptr, nullptr, scModule, nullptr);
    DXGI_SWAP_CHAIN_DESC desc{};
    desc.BufferCount = 1;
    desc.BufferDesc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    desc.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
    desc.OutputWindow = window;
    desc.SampleDesc.Count = 1;
    desc.Windowed = TRUE;
    desc.SwapEffect = DXGI_SWAP_EFFECT_DISCARD;
    ComPtr<IDXGISwapChain> swap;
    ComPtr<ID3D11Device> device;
    ComPtr<ID3D11DeviceContext> context;
    HRESULT hr = D3D11CreateDeviceAndSwapChain(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr, 0, nullptr, 0,
                                               D3D11_SDK_VERSION, &desc, &swap, &device, nullptr, &context);
    if (FAILED(hr))
        hr = D3D11CreateDeviceAndSwapChain(nullptr, D3D_DRIVER_TYPE_WARP, nullptr, 0, nullptr, 0,
                                           D3D11_SDK_VERSION, &desc, &swap, &device, nullptr, &context);
    bool ok = false;
    if (SUCCEEDED(hr)) {
        auto vtable = *reinterpret_cast<void ***>(swap.Get());
        auto drawTable = *reinterpret_cast<void ***>(context.Get());
        ok = MH_Initialize() == MH_OK &&
             MH_CreateHook(vtable[8], reinterpret_cast<void *>(hookPresent),
                           reinterpret_cast<void **>(&originalPresent)) == MH_OK &&
             MH_CreateHook(vtable[13], reinterpret_cast<void *>(hookResize),
                           reinterpret_cast<void **>(&originalResize)) == MH_OK;
        ComPtr<ID3D11DeviceContext> deferred;
        device->CreateDeferredContext(0, &deferred);
        auto deferredTable = deferred ? *reinterpret_cast<void ***>(deferred.Get()) : drawTable;
        struct Method {
            int index;
            void *hooks[2];
            void **originals[2];
        };
        Method methods[]{
            {12,
             {reinterpret_cast<void *>(hookDraw<0>), reinterpret_cast<void *>(hookDraw<1>)},
             {reinterpret_cast<void **>(&originalDraw[0]), reinterpret_cast<void **>(&originalDraw[1])}},
            {20,
             {reinterpret_cast<void *>(hookInstanced<0>), reinterpret_cast<void *>(hookInstanced<1>)},
             {reinterpret_cast<void **>(&originalInstanced[0]),
              reinterpret_cast<void **>(&originalInstanced[1])}},
            {33,
             {reinterpret_cast<void *>(hookTargets<0>), reinterpret_cast<void *>(hookTargets<1>)},
             {reinterpret_cast<void **>(&originalTargets[0]),
              reinterpret_cast<void **>(&originalTargets[1])}},
            {34,
             {reinterpret_cast<void *>(hookTargetsUav<0>), reinterpret_cast<void *>(hookTargetsUav<1>)},
             {reinterpret_cast<void **>(&originalTargetsUav[0]),
              reinterpret_cast<void **>(&originalTargetsUav[1])}},
            {44,
             {reinterpret_cast<void *>(hookViewports<0>), reinterpret_cast<void *>(hookViewports<1>)},
             {reinterpret_cast<void **>(&originalViewports[0]),
              reinterpret_cast<void **>(&originalViewports[1])}},
            // ID3D11DeviceContext slots from the Windows d3d11.h interface:
            // ExecuteCommandList=58, FinishCommandList=114.
            {53,
             {reinterpret_cast<void *>(hookClearDepth<0>), reinterpret_cast<void *>(hookClearDepth<1>)},
             {reinterpret_cast<void **>(&originalClearDepth[0]),
              reinterpret_cast<void **>(&originalClearDepth[1])}},
            {58,
             {reinterpret_cast<void *>(hookExecuteCommands<0>), reinterpret_cast<void *>(hookExecuteCommands<1>)},
             {reinterpret_cast<void **>(&originalExecuteCommands[0]),
              reinterpret_cast<void **>(&originalExecuteCommands[1])}},
            {114,
             {reinterpret_cast<void *>(hookFinishCommands<0>), reinterpret_cast<void *>(hookFinishCommands<1>)},
             {reinterpret_cast<void **>(&originalFinishCommands[0]),
              reinterpret_cast<void **>(&originalFinishCommands[1])}}};
        for (auto &m : methods) {
            if (!ok)
                break;
            ok = MH_CreateHook(drawTable[m.index], m.hooks[0], m.originals[0]) == MH_OK;
            if (ok && deferredTable[m.index] != drawTable[m.index])
                ok = MH_CreateHook(deferredTable[m.index], m.hooks[1], m.originals[1]) == MH_OK;
            else
                *m.originals[1] = *m.originals[0];
        }
        if (ok) {
            ready = true;
            ok = MH_EnableHook(MH_ALL_HOOKS) == MH_OK;
        }
        if (!ok) {
            ready = false;
            MH_DisableHook(MH_ALL_HOOKS);
            MH_Uninitialize();
        }
    }
    context.Reset();
    device.Reset();
    swap.Reset();
    DestroyWindow(window);
    UnregisterClassW(cls, scModule);
    return ok;
}
