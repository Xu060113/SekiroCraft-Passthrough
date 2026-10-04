#include "shared_memory.hpp"
#include <jni.h>

// Handles are owned by NativeBridge and used on Minecraft's render thread only.
extern "C" {
JNIEXPORT jlong JNICALL Java_dev_sekirobridge_NativeBridge_open(JNIEnv *env, jclass, jstring channel) {
    if (!channel)
        return 0;
    const jchar *s = env->GetStringChars(channel, nullptr);
    if (!s)
        return 0;
    std::wstring name(reinterpret_cast<const wchar_t *>(s), env->GetStringLength(channel));
    env->ReleaseStringChars(channel, s);
    auto m = std::make_unique<bridge::SharedMemory>();
    if (!m->open(name))
        return 0;
    return reinterpret_cast<jlong>(m.release());
}
JNIEXPORT void JNICALL Java_dev_sekirobridge_NativeBridge_close(JNIEnv *, jclass, jlong handle) {
    delete reinterpret_cast<bridge::SharedMemory *>(handle);
}
JNIEXPORT jboolean JNICALL Java_dev_sekirobridge_NativeBridge_control(JNIEnv *env, jclass, jlong handle,
                                                                      jobject buffer) {
    if (!handle || !buffer || env->GetDirectBufferCapacity(buffer) < jlong(sizeof(bridge::Control)))
        return false;
    auto p = env->GetDirectBufferAddress(buffer);
    if (!p)
        return false;
    bridge::Control c;
    if (!reinterpret_cast<bridge::SharedMemory *>(handle)->readControl(c))
        return false;
    std::memcpy(p, &c, sizeof(c));
    return true;
}
JNIEXPORT jboolean JNICALL Java_dev_sekirobridge_NativeBridge_publish(JNIEnv *env, jclass, jlong handle,
                                                                      jobject metadata, jobject pixels) {
    if (!handle || !metadata || !pixels ||
        env->GetDirectBufferCapacity(metadata) < jlong(sizeof(bridge::FrameMeta)))
        return false;
    auto m = env->GetDirectBufferAddress(metadata);
    auto p = env->GetDirectBufferAddress(pixels);
    if (!m || !p)
        return false;
    bridge::FrameMeta meta;
    std::memcpy(&meta, m, sizeof(meta));
    if (!bridge::valid(meta) || env->GetDirectBufferCapacity(pixels) < int64_t(bridge::frameBytes(meta)))
        return false;
    return reinterpret_cast<bridge::SharedMemory *>(handle)->publish(
        meta, {static_cast<const uint8_t *>(p), size_t(bridge::frameBytes(meta))});
}
JNIEXPORT jboolean JNICALL Java_dev_sekirobridge_NativeBridge_status(JNIEnv *, jclass, jlong handle,
                                                                     jint flags, jlong epoch) {
    return handle &&
           reinterpret_cast<bridge::SharedMemory *>(handle)->status(uint32_t(flags), uint64_t(epoch));
}
JNIEXPORT jlong JNICALL Java_dev_sekirobridge_NativeBridge_clockMs(JNIEnv *, jclass) {
    return GetTickCount64();
}
JNIEXPORT jboolean JNICALL Java_dev_sekirobridge_NativeBridge_physics(JNIEnv *env, jclass, jlong handle,
                                                                      jobject buffer) {
    if (!handle || !buffer || env->GetDirectBufferCapacity(buffer) < jlong(sizeof(bridge::PhysicsPacket)))
        return false;
    auto bytes = env->GetDirectBufferAddress(buffer); if (!bytes) return false;
    bridge::PhysicsPacket p; std::memcpy(&p, bytes, sizeof(p));
    return reinterpret_cast<bridge::SharedMemory *>(handle)->physics.write(p);
}
}
