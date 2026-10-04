#pragma once
#include "../bridge/protocol.hpp"
#include "sekirocraft/math.hpp"
#include <d3d11.h>
#include <wrl/client.h>
#include <atomic>
#include <memory>
#include <new>
#include <vector>
#include <mutex>

namespace bridge {
// Lock order is scene -> metadata for immediate contexts. Deferred workers only
// touch metadata, so a busy Present never drops their recording boundaries.
class RecordingGate {
    std::mutex metadata_;
  public:
    std::mutex &mutex(){return metadata_;}
    template<class F> bool run(bool deferred,std::mutex &scene,F work){
        std::unique_lock<std::mutex> sceneLock(scene,std::defer_lock);
        if(!deferred && !sceneLock.try_lock())return false;
        std::lock_guard metadataLock(metadata_);work();return true;
    }
};
// Capture in the immediate command stream after scene commands execute. Later
// native clears, UI lists, or untracked commands cannot change this copy.
class DepthSnapshot {
    Microsoft::WRL::ComPtr<ID3D11Texture2D> texture_;
    Microsoft::WRL::ComPtr<ID3D11DepthStencilView> view_;
    D3D11_TEXTURE2D_DESC description_{};
  public:
    uint64_t captures{};
    ID3D11DepthStencilView *view() const { return view_.Get(); }
    void reset() { view_.Reset(); texture_.Reset(); description_ = {}; }
    bool capture(ID3D11DeviceContext *context, ID3D11DepthStencilView *source) {
        if (!context || !source || context->GetType() != D3D11_DEVICE_CONTEXT_IMMEDIATE)
            return false;
        Microsoft::WRL::ComPtr<ID3D11Resource> resource;
        source->GetResource(&resource);
        Microsoft::WRL::ComPtr<ID3D11Texture2D> texture;
        if (FAILED(resource.As(&texture)))
            return false;
        D3D11_TEXTURE2D_DESC description{};
        texture->GetDesc(&description);
        if (description.SampleDesc.Count != 1 || description.ArraySize != 1 || description.MipLevels != 1)
            return false;
        D3D11_DEPTH_STENCIL_VIEW_DESC viewDescription{};
        source->GetDesc(&viewDescription);
        if (!texture_ || description.Width != description_.Width || description.Height != description_.Height ||
            description.Format != description_.Format) {
            Microsoft::WRL::ComPtr<ID3D11Device> device;
            context->GetDevice(&device);
            auto owned = description;
            owned.Usage = D3D11_USAGE_DEFAULT;
            owned.BindFlags = D3D11_BIND_DEPTH_STENCIL;
            owned.CPUAccessFlags = owned.MiscFlags = 0;
            Microsoft::WRL::ComPtr<ID3D11Texture2D> replacement;
            Microsoft::WRL::ComPtr<ID3D11DepthStencilView> replacementView;
            if (FAILED(device->CreateTexture2D(&owned, nullptr, &replacement)) ||
                FAILED(device->CreateDepthStencilView(replacement.Get(), &viewDescription, &replacementView)))
                return false;
            texture_ = std::move(replacement);
            view_ = std::move(replacementView);
            description_ = description;
        }
        context->CopyResource(texture_.Get(), texture.Get());
        ++captures;
        return true;
    }
};
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
