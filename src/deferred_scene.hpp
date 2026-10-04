#pragma once
#include "../bridge/protocol.hpp"
#include "sekirocraft/math.hpp"
#include <d3d11.h>
#include <wrl/client.h>
#include <atomic>
#include <memory>
#include <new>
#include <vector>

namespace bridge {
struct SceneDraw {
    Microsoft::WRL::ComPtr<ID3D11DepthStencilView> depth;
    D3D11_VIEWPORT viewport{};
    sc::Camera camera;
    std::shared_ptr<Frame> frame;
    uint64_t score{};
    bool clearsDepth{};
};

struct SceneRecording {
    uint64_t generation{};
    std::vector<SceneDraw> draws;
    void record(uint64_t currentGeneration, SceneDraw draw) {
        if (generation != currentGeneration) {
            generation = currentGeneration;
            draws.clear();
        }
        // Preserve ordering when a command list changes views. Adjacent draws
        // with the same capture may be combined without losing camera changes.
        if (!draw.clearsDepth && !draws.empty() && !draws.back().clearsDepth &&
            draws.back().depth.Get() == draw.depth.Get() &&
            draws.back().frame == draw.frame) {
            draw.score += draws.back().score;
            draws.back() = std::move(draw);
        } else
            draws.push_back(std::move(draw));
    }
};

// The command list owns its metadata. No global map keeps lists/resources alive,
// and a released list's address can never accidentally match another recording.
// GetPrivateData adds a reference for SetPrivateDataInterface values.
class DeferredScene final : public IUnknown {
    std::atomic<ULONG> references_{1};
    inline static constexpr GUID identity_{0x88dbf13e, 0xd9a4, 0x4070,
                                           {0x8c, 0x37, 0xf2, 0x58, 0xd4, 0x27, 0x04, 0x7e}};
    DeferredScene(uint64_t epoch, std::vector<SceneDraw> recording)
        : resourceEpoch(epoch), draws(std::move(recording)) {}
    ~DeferredScene() = default;

  public:
    const uint64_t resourceEpoch;
    const std::vector<SceneDraw> draws;
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid, void **result) override {
        if (!result)
            return E_POINTER;
        *result = nullptr;
        if (!IsEqualGUID(iid, __uuidof(IUnknown)) && !IsEqualGUID(iid, identity_))
            return E_NOINTERFACE;
        *result = static_cast<IUnknown *>(this);
        AddRef();
        return S_OK;
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return ++references_; }
    ULONG STDMETHODCALLTYPE Release() override {
        ULONG remaining = --references_;
        if (!remaining)
            delete this;
        return remaining;
    }
    static bool attach(ID3D11CommandList *list, uint64_t epoch, std::vector<SceneDraw> recording) {
        if (!list)
            return false;
        auto *value = new (std::nothrow) DeferredScene(epoch, std::move(recording));
        if (!value)
            return false;
        HRESULT result = list->SetPrivateDataInterface(identity_, value);
        value->Release();
        return SUCCEEDED(result);
    }
    static Microsoft::WRL::ComPtr<DeferredScene> read(ID3D11CommandList *list) {
        Microsoft::WRL::ComPtr<DeferredScene> value;
        IUnknown *unknown{};
        UINT size = sizeof(unknown);
        if (list && SUCCEEDED(list->GetPrivateData(identity_, &size, &unknown)) && unknown) {
            void *typed{};
            if (SUCCEEDED(unknown->QueryInterface(identity_, &typed)))
                value.Attach(static_cast<DeferredScene *>(static_cast<IUnknown *>(typed)));
            unknown->Release();
        }
        return value;
    }
};
} // namespace bridge
