#pragma once
#include "../bridge/protocol.hpp"
#include "sekirocraft/math.hpp"
#include <d3d11.h>
#include <d3dcompiler.h>
#include <wrl/client.h>
#include <string>
using Microsoft::WRL::ComPtr;

namespace bridge {
// Saves precisely the pipeline state touched by the two full-screen draws.
struct PipelineState {
    ID3D11DeviceContext *c;
    ID3D11RenderTargetView *rt[8]{};
    ID3D11DepthStencilView *dsv{};
    ComPtr<ID3D11BlendState> blend;
    float factors[4]{};
    UINT mask{}, stencil{};
    ComPtr<ID3D11DepthStencilState> depth;
    ComPtr<ID3D11RasterizerState> raster;
    D3D11_VIEWPORT viewports[16]{};
    UINT viewCount = 16;
    ComPtr<ID3D11InputLayout> layout;
    D3D11_PRIMITIVE_TOPOLOGY topology{};
    ComPtr<ID3D11VertexShader> vs;
    ComPtr<ID3D11PixelShader> ps;
    ComPtr<ID3D11GeometryShader> gs;
    ComPtr<ID3D11HullShader> hs;
    ComPtr<ID3D11DomainShader> ds;
    ID3D11ClassInstance *vc[256]{}, *pc[256]{}, *gc[256]{}, *hc[256]{}, *dc[256]{};
    UINT vn = 256, pn = 256, gn = 256, hn = 256, dn = 256;
    ID3D11ShaderResourceView *resources[3]{};
    ComPtr<ID3D11SamplerState> sampler;
    ComPtr<ID3D11Buffer> constants;
    explicit PipelineState(ID3D11DeviceContext *context) : c(context) {
        c->OMGetRenderTargets(8, rt, &dsv);
        c->OMGetBlendState(&blend, factors, &mask);
        c->OMGetDepthStencilState(&depth, &stencil);
        c->RSGetState(&raster);
        c->RSGetViewports(&viewCount, viewports);
        c->IAGetInputLayout(&layout);
        c->IAGetPrimitiveTopology(&topology);
        c->VSGetShader(&vs, vc, &vn);
        c->PSGetShader(&ps, pc, &pn);
        c->GSGetShader(&gs, gc, &gn);
        c->HSGetShader(&hs, hc, &hn);
        c->DSGetShader(&ds, dc, &dn);
        c->PSGetShaderResources(0, 3, resources);
        c->PSGetSamplers(0, 1, &sampler);
        c->PSGetConstantBuffers(0, 1, &constants);
    }
    ~PipelineState() {
        ID3D11ShaderResourceView *empty[3]{};
        c->PSSetShaderResources(0, 3, empty);
        c->OMSetRenderTargets(8, rt, dsv);
        c->OMSetBlendState(blend.Get(), factors, mask);
        c->OMSetDepthStencilState(depth.Get(), stencil);
        c->RSSetState(raster.Get());
        c->RSSetViewports(viewCount, viewports);
        c->IASetInputLayout(layout.Get());
        c->IASetPrimitiveTopology(topology);
        c->VSSetShader(vs.Get(), vc, vn);
        c->PSSetShader(ps.Get(), pc, pn);
        c->GSSetShader(gs.Get(), gc, gn);
        c->HSSetShader(hs.Get(), hc, hn);
        c->DSSetShader(ds.Get(), dc, dn);
        c->PSSetShaderResources(0, 3, resources);
        auto s = sampler.Get();
        c->PSSetSamplers(0, 1, &s);
        auto b = constants.Get();
        c->PSSetConstantBuffers(0, 1, &b);
        for (auto *r : rt)
            if (r)
                r->Release();
        if (dsv)
            dsv->Release();
        for (auto *r : resources)
            if (r)
                r->Release();
        for (UINT i = 0; i < vn; ++i)
            vc[i]->Release();
        for (UINT i = 0; i < pn; ++i)
            pc[i]->Release();
        for (UINT i = 0; i < gn; ++i)
            gc[i]->Release();
        for (UINT i = 0; i < hn; ++i)
            hc[i]->Release();
        for (UINT i = 0; i < dn; ++i)
            dc[i]->Release();
    }
};
class Compositor {
    ComPtr<ID3D11Device> device_;
    ComPtr<ID3D11VertexShader> vs_;
    ComPtr<ID3D11PixelShader> worldPS_, overlayPS_;
    ComPtr<ID3D11SamplerState> point_;
    ComPtr<ID3D11BlendState> blend_;
    ComPtr<ID3D11DepthStencilState> noDepth_;
    ComPtr<ID3D11RasterizerState> raster_;
    ComPtr<ID3D11Buffer> constants_;
    ComPtr<ID3D11Texture2D> hostDepth_;
    ComPtr<ID3D11ShaderResourceView> hostDepthView_;
    D3D11_TEXTURE2D_DESC hostDesc_{};
    struct Textures {
        ComPtr<ID3D11Texture2D> world, depth, overlay;
        ComPtr<ID3D11ShaderResourceView> worldView, depthView, overlayView;
        uint32_t width{}, height{};
    } ring_[3];
    unsigned current_{};
    uint64_t sequence_{};
    FrameMeta frameMeta_{};

  public:
    std::string error;
    uint64_t submitted{};
    static constexpr const char *shader = R"(
cbuffer Bridge : register(b0) {
    float4 CaptureEye, CaptureForward, CaptureRight, CaptureUp;
    float4 CurrentEye, CurrentForward, CurrentRight, CurrentUp;
    float4 McProjection; // cot(fov/2)/aspect, cot(fov/2), near, far
    float4 HostProjection; // projection00, projection11, projection22, projection32
    float4 Options; // bottom-up, reprojection, reserved
};
Texture2D World : register(t0); Texture2D McDepth : register(t1); Texture2D HostDepth : register(t2);
SamplerState Point : register(s0);
struct Out { float4 p:SV_POSITION; float2 uv:TEXCOORD; };
Out vs(uint id:SV_VertexID){Out o;o.uv=float2((id<<1)&2,id&2);o.p=float4(o.uv.x*2-1,1-o.uv.y*2,0,1);return o;}
float2 frameUV(float2 uv){return float2(uv.x,Options.x>.5?1-uv.y:uv.y);}
float mcZ(float2 uv){float d=McDepth.SampleLevel(Point,frameUV(uv),0).r;return McProjection.z*McProjection.w/(McProjection.w-d*(McProjection.w-McProjection.z));}
float4 world(Out i):SV_TARGET {
    float raw=HostDepth.SampleLevel(Point,i.uv,0).r;
    float zh=HostProjection.w/(raw-HostProjection.z);
    float2 uv=i.uv;
    float3 ray=CurrentForward.xyz+CurrentRight.xyz*((i.uv.x*2-1)/HostProjection.x)+CurrentUp.xyz*((1-i.uv.y*2)/HostProjection.y);
    float t=mcZ(uv), denom=dot(ray,CaptureForward.xyz);
    if(Options.y>.5) {
        if(denom<.2) discard;
        [unroll] for(int k=0;k<6;++k) {
            float3 delta=CurrentEye.xyz+ray*t-CaptureEye.xyz;
            float z=dot(delta,CaptureForward.xyz); if(z<=0) discard;
            uv=float2(.5+.5*dot(delta,CaptureRight.xyz)*McProjection.x/z,.5-.5*dot(delta,CaptureUp.xyz)*McProjection.y/z);
            if(any(uv<0)||any(uv>1)) discard;
            t+=(mcZ(uv)-z)/denom;
        }
        float3 finalDelta=CurrentEye.xyz+ray*t-CaptureEye.xyz;
        if(abs(mcZ(uv)-dot(finalDelta,CaptureForward.xyz))>max(.03,t*.002)) discard;
    }
    float d=McDepth.SampleLevel(Point,frameUV(uv),0).r;
    if(!(d>=0&&d<.9999999)||!(t>0)||!(t<=zh+.015)) discard;
    float4 color=World.SampleLevel(Point,frameUV(uv),0); clip(color.a-.005); return color;
}
float4 overlay(Out i):SV_TARGET{return World.SampleLevel(Point,frameUV(i.uv),0);}
)";
    bool init(ID3D11Device *device) {
        device_ = device;
        ComPtr<ID3DBlob> v, w, o, diagnostics;
        auto compile = [&](const char *entry, const char *profile, ComPtr<ID3DBlob> &blob) {
            auto hr = D3DCompile(shader, std::strlen(shader), nullptr, nullptr, nullptr, entry, profile,
                                 D3DCOMPILE_ENABLE_STRICTNESS, 0, &blob, &diagnostics);
            if (FAILED(hr)) {
                error = diagnostics ? std::string(static_cast<char *>(diagnostics->GetBufferPointer()),
                                                  diagnostics->GetBufferSize())
                                    : "shader compile failed";
                return false;
            }
            return true;
        };
        if (!compile("vs", "vs_4_0", v) || !compile("world", "ps_4_0", w) || !compile("overlay", "ps_4_0", o))
            return false;
        if (FAILED(device->CreateVertexShader(v->GetBufferPointer(), v->GetBufferSize(), nullptr, &vs_)) ||
            FAILED(
                device->CreatePixelShader(w->GetBufferPointer(), w->GetBufferSize(), nullptr, &worldPS_)) ||
            FAILED(
                device->CreatePixelShader(o->GetBufferPointer(), o->GetBufferSize(), nullptr, &overlayPS_)))
            return false;
        D3D11_SAMPLER_DESC sd{};
        sd.Filter = D3D11_FILTER_MIN_MAG_MIP_POINT;
        sd.AddressU = sd.AddressV = sd.AddressW = D3D11_TEXTURE_ADDRESS_CLAMP;
        sd.MaxLOD = D3D11_FLOAT32_MAX;
        D3D11_BLEND_DESC bd{};
        auto &b = bd.RenderTarget[0];
        b.BlendEnable = TRUE;
        b.SrcBlend = b.SrcBlendAlpha = D3D11_BLEND_ONE;
        b.DestBlend = b.DestBlendAlpha = D3D11_BLEND_INV_SRC_ALPHA;
        b.BlendOp = b.BlendOpAlpha = D3D11_BLEND_OP_ADD;
        b.RenderTargetWriteMask = 15;
        D3D11_DEPTH_STENCIL_DESC dd{};
        dd.DepthEnable = FALSE;
        D3D11_RASTERIZER_DESC rd{};
        rd.FillMode = D3D11_FILL_SOLID;
        rd.CullMode = D3D11_CULL_NONE;
        rd.DepthClipEnable = TRUE;
        D3D11_BUFFER_DESC cb{};
        cb.ByteWidth = 11 * 16;
        cb.Usage = D3D11_USAGE_DEFAULT;
        cb.BindFlags = D3D11_BIND_CONSTANT_BUFFER;
        return SUCCEEDED(device->CreateSamplerState(&sd, &point_)) &&
               SUCCEEDED(device->CreateBlendState(&bd, &blend_)) &&
               SUCCEEDED(device->CreateDepthStencilState(&dd, &noDepth_)) &&
               SUCCEEDED(device->CreateRasterizerState(&rd, &raster_)) &&
               SUCCEEDED(device->CreateBuffer(&cb, nullptr, &constants_));
    }
    bool upload(ID3D11DeviceContext *c, const Frame &f) {
        if (!valid(f.meta) || f.pixels.size() != frameBytes(f.meta)) {
            error = "Invalid frame";
            return false;
        }
        if (f.meta.sequence == sequence_ && f.meta.epoch == frameMeta_.epoch)
            return true;
        unsigned next = (current_ + 1) % 3;
        auto &t = ring_[next];
        if (t.width != f.meta.width || t.height != f.meta.height) {
            Textures replacement;
            D3D11_TEXTURE2D_DESC d{};
            d.Width = f.meta.width;
            d.Height = f.meta.height;
            d.MipLevels = d.ArraySize = d.SampleDesc.Count = 1;
            d.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
            d.Usage = D3D11_USAGE_DEFAULT;
            d.BindFlags = D3D11_BIND_SHADER_RESOURCE;
            if (FAILED(device_->CreateTexture2D(&d, nullptr, &replacement.world)) ||
                FAILED(device_->CreateTexture2D(&d, nullptr, &replacement.overlay)))
                return false;
            d.Format = DXGI_FORMAT_R32_FLOAT;
            if (FAILED(device_->CreateTexture2D(&d, nullptr, &replacement.depth)) ||
                FAILED(device_->CreateShaderResourceView(replacement.world.Get(), nullptr,
                                                         &replacement.worldView)) ||
                FAILED(device_->CreateShaderResourceView(replacement.depth.Get(), nullptr,
                                                         &replacement.depthView)) ||
                FAILED(device_->CreateShaderResourceView(replacement.overlay.Get(), nullptr,
                                                         &replacement.overlayView)))
                return false;
            replacement.width = d.Width;
            replacement.height = d.Height;
            t = std::move(replacement);
        }
        auto plane = size_t(f.meta.width) * f.meta.height * 4;
        c->UpdateSubresource(t.world.Get(), 0, nullptr, f.pixels.data(), f.meta.width * 4, 0);
        c->UpdateSubresource(t.depth.Get(), 0, nullptr, f.pixels.data() + plane, f.meta.width * 4, 0);
        c->UpdateSubresource(t.overlay.Get(), 0, nullptr, f.pixels.data() + plane * 2, f.meta.width * 4, 0);
        current_ = next;
        sequence_ = f.meta.sequence;
        frameMeta_ = f.meta;
        error.clear();
        return true;
    }
    bool draw(ID3D11DeviceContext *c, ID3D11RenderTargetView *target, ID3D11DepthStencilView *source,
              const sc::Camera &camera, float width, float height, bool reproject = true) {
        if (!source || !sequence_ || !camera.valid)
            return false;
        ComPtr<ID3D11Resource> resource;
        source->GetResource(&resource);
        ComPtr<ID3D11Texture2D> texture;
        if (FAILED(resource.As(&texture)))
            return false;
        D3D11_TEXTURE2D_DESC d{};
        texture->GetDesc(&d);
        if (d.SampleDesc.Count != 1 || d.ArraySize != 1 || d.MipLevels != 1) {
            error = "Unsupported host depth layout";
            return false;
        }
        if (std::abs(float(d.Width) / d.Height - camera.projection.at(1, 1) / camera.projection.at(0, 0)) >
            .03f)
            return false;
        auto nearMatch =
            sc::length(camera.eye - sc::Vec3{frameMeta_.eye[0], frameMeta_.eye[1], frameMeta_.eye[2]});
        if (nearMatch > 2 || sc::dot(camera.forward, sc::Vec3{frameMeta_.forward[0], frameMeta_.forward[1],
                                                              frameMeta_.forward[2]}) < .8f) {
            error = "Frame camera too far behind host";
            return false;
        }
        if (!hostDepth_ || hostDesc_.Width != d.Width || hostDesc_.Height != d.Height ||
            hostDesc_.Format != d.Format) {
            DXGI_FORMAT typeless{}, view{};
            switch (d.Format) {
            case DXGI_FORMAT_D32_FLOAT:
            case DXGI_FORMAT_R32_TYPELESS:
                typeless = DXGI_FORMAT_R32_TYPELESS;
                view = DXGI_FORMAT_R32_FLOAT;
                break;
            case DXGI_FORMAT_D24_UNORM_S8_UINT:
            case DXGI_FORMAT_R24G8_TYPELESS:
                typeless = DXGI_FORMAT_R24G8_TYPELESS;
                view = DXGI_FORMAT_R24_UNORM_X8_TYPELESS;
                break;
            case DXGI_FORMAT_D32_FLOAT_S8X24_UINT:
            case DXGI_FORMAT_R32G8X24_TYPELESS:
                typeless = DXGI_FORMAT_R32G8X24_TYPELESS;
                view = DXGI_FORMAT_R32_FLOAT_X8X24_TYPELESS;
                break;
            default:
                error = "Unsupported host depth format";
                return false;
            }
            auto copy = d;
            copy.Format = typeless;
            copy.Usage = D3D11_USAGE_DEFAULT;
            copy.BindFlags = D3D11_BIND_SHADER_RESOURCE;
            copy.CPUAccessFlags = copy.MiscFlags = 0;
            ComPtr<ID3D11Texture2D> depth;
            ComPtr<ID3D11ShaderResourceView> depthView;
            D3D11_SHADER_RESOURCE_VIEW_DESC vd{};
            vd.Format = view;
            vd.ViewDimension = D3D11_SRV_DIMENSION_TEXTURE2D;
            vd.Texture2D.MipLevels = 1;
            if (FAILED(device_->CreateTexture2D(&copy, nullptr, &depth)) ||
                FAILED(device_->CreateShaderResourceView(depth.Get(), &vd, &depthView)))
                return false;
            hostDepth_ = depth;
            hostDepthView_ = depthView;
            hostDesc_ = d;
        }
        struct Constants {
            float captureEye[4], captureForward[4], captureRight[4], captureUp[4], currentEye[4],
                currentForward[4], currentRight[4], currentUp[4], mc[4], host[4], options[4];
        } values{};
        auto set = [](float *dst, sc::Vec3 v) {
            dst[0] = v.x;
            dst[1] = v.y;
            dst[2] = v.z;
        };
        auto basis = [&](sc::Vec3 f, float *forward, float *right, float *up) {
            set(forward, f);
            auto r = sc::normalize(sc::Vec3{f.z, 0, -f.x});
            set(right, r);
            set(up, {f.y * r.z - f.z * r.y, f.z * r.x - f.x * r.z, f.x * r.y - f.y * r.x});
        };
        set(values.captureEye, {frameMeta_.eye[0], frameMeta_.eye[1], frameMeta_.eye[2]});
        basis({frameMeta_.forward[0], frameMeta_.forward[1], frameMeta_.forward[2]}, values.captureForward,
              values.captureRight, values.captureUp);
        set(values.currentEye, camera.eye);
        basis(camera.forward, values.currentForward, values.currentRight, values.currentUp);
        values.mc[1] = 1 / std::tan(frameMeta_.fovY / 2);
        values.mc[0] = values.mc[1] / frameMeta_.aspect;
        values.mc[2] = frameMeta_.nearZ;
        values.mc[3] = frameMeta_.farZ;
        values.host[0] = camera.projection.at(0, 0);
        values.host[1] = camera.projection.at(1, 1);
        values.host[2] = camera.projection.at(2, 2);
        values.host[3] = camera.projection.at(3, 2);
        values.options[0] = frameMeta_.flags & BottomUp ? 1.f : 0.f;
        values.options[1] = reproject ? 1.f : 0.f;
        PipelineState restore(c);
        c->OMSetRenderTargets(1, &target, nullptr);
        ID3D11ShaderResourceView *empty[3]{};
        c->PSSetShaderResources(0, 3, empty);
        c->CopyResource(hostDepth_.Get(), texture.Get());
        c->UpdateSubresource(constants_.Get(), 0, nullptr, &values, 0, 0);
        c->OMSetBlendState(blend_.Get(), nullptr, 0xffffffff);
        c->OMSetDepthStencilState(noDepth_.Get(), 0);
        c->RSSetState(raster_.Get());
        float aspect = float(d.Width) / d.Height, w = std::min(width, height * aspect), h = w / aspect;
        D3D11_VIEWPORT vp{(width - w) / 2, (height - h) / 2, w, h, 0, 1};
        c->RSSetViewports(1, &vp);
        c->IASetInputLayout(nullptr);
        c->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
        c->VSSetShader(vs_.Get(), nullptr, 0);
        c->PSSetShader(worldPS_.Get(), nullptr, 0);
        c->GSSetShader(nullptr, nullptr, 0);
        c->HSSetShader(nullptr, nullptr, 0);
        c->DSSetShader(nullptr, nullptr, 0);
        auto cb = constants_.Get();
        auto sampler = point_.Get();
        c->PSSetConstantBuffers(0, 1, &cb);
        c->PSSetSamplers(0, 1, &sampler);
        auto &t = ring_[current_];
        ID3D11ShaderResourceView *views[]{t.worldView.Get(), t.depthView.Get(), hostDepthView_.Get()};
        c->PSSetShaderResources(0, 3, views);
        c->Draw(3, 0);
        if (frameMeta_.flags & Overlay) {
            auto view = t.overlayView.Get();
            c->PSSetShaderResources(0, 1, &view);
            c->PSSetShader(overlayPS_.Get(), nullptr, 0);
            c->Draw(3, 0);
        }
        ++submitted;
        error.clear();
        return true;
    }
};
} // namespace bridge
