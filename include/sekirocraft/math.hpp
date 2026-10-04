#pragma once
#include "geometry.hpp"
#include <algorithm>
#include <array>
#include <cstring>
#include <optional>
#include <span>
#include <vector>
namespace sc {
// Row vectors, row-major matrices: world * view * projection.
struct Mat4 {
    std::array<float, 16> v{};
    float &at(int r, int c) { return v[r * 4 + c]; }
    float at(int r, int c) const { return v[r * 4 + c]; }
};
inline Mat4 identity() {
    Mat4 m;
    for (int i = 0; i < 4; ++i)
        m.at(i, i) = 1;
    return m;
}
inline Mat4 transpose(Mat4 a) {
    Mat4 b;
    for (int r = 0; r < 4; ++r)
        for (int c = 0; c < 4; ++c)
            b.at(r, c) = a.at(c, r);
    return b;
}
inline Mat4 multiply(Mat4 a, Mat4 b) {
    Mat4 m;
    for (int r = 0; r < 4; ++r)
        for (int c = 0; c < 4; ++c)
            for (int k = 0; k < 4; ++k)
                m.at(r, c) += a.at(r, k) * b.at(k, c);
    return m;
}
inline std::optional<Mat4> inverse(Mat4 m) {
    float a[4][8]{};
    for (int r = 0; r < 4; ++r) {
        for (int c = 0; c < 4; ++c)
            a[r][c] = m.at(r, c);
        a[r][r + 4] = 1;
    }
    for (int c = 0; c < 4; ++c) {
        int p = c;
        for (int r = c + 1; r < 4; ++r)
            if (std::abs(a[r][c]) > std::abs(a[p][c]))
                p = r;
        if (std::abs(a[p][c]) < 1e-8f)
            return {};
        if (p != c)
            for (int k = 0; k < 8; ++k)
                std::swap(a[p][k], a[c][k]);
        float d = a[c][c];
        for (int k = 0; k < 8; ++k)
            a[c][k] /= d;
        for (int r = 0; r < 4; ++r)
            if (r != c) {
                float f = a[r][c];
                for (int k = 0; k < 8; ++k)
                    a[r][k] -= f * a[c][k];
            }
    }
    Mat4 result;
    for (int r = 0; r < 4; ++r)
        for (int c = 0; c < 4; ++c)
            result.at(r, c) = a[r][c + 4];
    return result;
}
inline bool isView(Mat4 m) {
    for (float f : m.v)
        if (!std::isfinite(f))
            return false;
    if (std::abs(m.at(3, 3) - 1) > .002f ||
        std::abs(m.at(0, 3)) + std::abs(m.at(1, 3)) + std::abs(m.at(2, 3)) > .002f)
        return false;
    Vec3 x{m.at(0, 0), m.at(1, 0), m.at(2, 0)}, y{m.at(0, 1), m.at(1, 1), m.at(2, 1)},
        z{m.at(0, 2), m.at(1, 2), m.at(2, 2)};
    return std::abs(dot(x, x) - 1) < .01f && std::abs(dot(y, y) - 1) < .01f &&
           std::abs(dot(z, z) - 1) < .01f && std::abs(dot(x, y)) < .01f && std::abs(dot(x, z)) < .01f &&
           std::abs(dot(y, z)) < .01f;
}
inline bool isProjection(Mat4 m) {
    for (float f : m.v)
        if (!std::isfinite(f))
            return false;
    if (m.at(0, 0) < .2f || m.at(0, 0) > 5 || m.at(1, 1) < .3f || m.at(1, 1) > 6 ||
        std::abs(std::abs(m.at(2, 3)) - 1) > .001f || std::abs(m.at(3, 3)) > .001f ||
        std::abs(m.at(3, 2)) < .00001f)
        return false;
    for (int r = 0; r < 4; ++r)
        for (int c = 0; c < 4; ++c)
            if (!((r == c && r < 3) || (r == 2 && c == 3) || (r == 3 && c == 2) || (r == 2 && c < 2)) &&
                std::abs(m.at(r, c)) > .002f)
                return false;
    return true;
}
struct Camera {
    Mat4 view{}, projection{};
    Vec3 eye{}, forward{};
    bool valid{};
};
inline std::optional<Camera> findCamera(std::span<const uint8_t> data, Vec3 player) {
    std::vector<Mat4> views, projections;
    for (size_t i = 0; i + 64 <= data.size(); i += 16) {
        Mat4 raw;
        std::memcpy(raw.v.data(), data.data() + i, 64);
        for (auto m : {raw, transpose(raw)}) {
            if (isView(m))
                views.push_back(m);
            if (isProjection(m))
                projections.push_back(m);
        }
    }
    float best = 20;
    std::optional<Camera> result;
    for (auto v : views) {
        auto inv = inverse(v);
        if (!inv)
            continue;
        Vec3 eye{inv->at(3, 0), inv->at(3, 1), inv->at(3, 2)};
        float dist = length(eye - player);
        if (dist < .15f || dist > best)
            continue;
        for (auto p : projections) {
            Camera c{v, p, eye,
                     normalize({inv->at(2, 0) * p.at(2, 3), inv->at(2, 1) * p.at(2, 3),
                                inv->at(2, 2) * p.at(2, 3)}),
                     true};
            best = dist;
            result = c;
        }
    }
    return result;
}
inline Mat4 perspective(float fov, float aspect, float nearZ = .05f, float farZ = 500) {
    Mat4 p;
    float f = 1 / std::tan(fov / 2);
    p.at(0, 0) = f / aspect;
    p.at(1, 1) = f;
    p.at(2, 2) = farZ / (farZ - nearZ);
    p.at(2, 3) = 1;
    p.at(3, 2) = -nearZ * farZ / (farZ - nearZ);
    return p;
}
inline Mat4 lookAt(Vec3 eye, Vec3 target) {
    auto cross = [](Vec3 a, Vec3 b) {
        return Vec3{a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x};
    };
    auto z = normalize(target - eye), x = normalize(cross({0, 1, 0}, z)), y = cross(z, x);
    auto v = identity();
    for (int r = 0; r < 3; ++r) {
        float xa[3]{x.x, x.y, x.z}, ya[3]{y.x, y.y, y.z}, za[3]{z.x, z.y, z.z};
        v.at(r, 0) = xa[r];
        v.at(r, 1) = ya[r];
        v.at(r, 2) = za[r];
    }
    v.at(3, 0) = -dot(eye, x);
    v.at(3, 1) = -dot(eye, y);
    v.at(3, 2) = -dot(eye, z);
    return v;
}
inline std::optional<Camera> cameraFromNativePose(const Mat4 &pose, std::array<float, 4> lens, Vec3 player) {
    if (!isView(pose) || !finite(player))
        return {};
    Vec3 eye{pose.at(3, 0), pose.at(3, 1), pose.at(3, 2)};
    float distance = length(eye - player);
    if (!finite(eye) || distance > 30 || distance < .05f || !std::isfinite(lens[0]) ||
        !std::isfinite(lens[1]) || !std::isfinite(lens[2]) || !std::isfinite(lens[3]) || lens[0] < .025f ||
        lens[0] > 3.05f || lens[1] < .7f || lens[1] > 4 || lens[2] <= 0 || lens[2] > 10 ||
        lens[3] <= lens[2] || lens[3] > 100000)
        return {};
    auto view = inverse(pose);
    if (!view)
        return {};
    return Camera{*view, perspective(lens[0], lens[1], lens[2], lens[3]), eye,
                  normalize({pose.at(2, 0), pose.at(2, 1), pose.at(2, 2)}), true};
}
} // namespace sc
